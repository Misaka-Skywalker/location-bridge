"""Interface-scoped RemotePairing discovery for the Android worker."""
import asyncio
from contextlib import aclosing, suppress
from functools import partial
import ipaddress
import logging
import socket
import struct
import time


def parse_address(payload):
    if len(payload) < 8:
        return None
    family, prefix, _, _, index = struct.unpack_from('=4BI', payload)
    width = {socket.AF_INET: 4, socket.AF_INET6: 16}.get(family)
    if not index or width is None or prefix > width * 8:
        return None
    addresses = {}
    offset = 8
    while offset + 4 <= len(payload):
        length, kind = struct.unpack_from('=HH', payload, offset)
        if length < 4 or offset + length > len(payload):
            return None
        if kind in (1, 2) and length == width + 4:  # IFA_ADDRESS, IFA_LOCAL
            addresses[kind] = payload[offset + 4:offset + length]
        offset += (length + 3) & ~3
    raw = addresses.get(2, addresses.get(1))
    if raw is None:
        return None
    ip = ipaddress.ip_address(raw)
    if ip.is_loopback or ip.is_unspecified or ip.is_multicast:
        return None
    return index, ipaddress.ip_interface((ip, prefix))


def local_addresses():
    # RTM_GETADDR works when Android denies the RTM_GETLINK used by glibc getifaddrs().
    request = struct.pack('=IHHII4BI', 24, 22, 0x301, 1, 0, socket.AF_UNSPEC, 0, 0, 0, 0)
    addresses = set()
    expires = time.monotonic() + 1
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, socket.NETLINK_ROUTE) as sock:
        sock.bind((0, 0))
        sock.sendto(request, (0, 0))
        while (left := expires - time.monotonic()) > 0:
            sock.settimeout(left)
            reply, sender = sock.recvfrom(65536)
            if sender[0] != 0:
                continue
            offset = 0
            while offset + 16 <= len(reply):
                length, kind, _, seq, _ = struct.unpack_from('=IHHII', reply, offset)
                if length < 16 or offset + length > len(reply):
                    raise OSError('invalid address dump')
                if seq == 1 and kind == 3:  # NLMSG_DONE
                    return sorted(addresses, key=lambda entry: (entry[0], entry[1].version, int(entry[1].ip)))
                if seq == 1 and kind == 2:  # NLMSG_ERROR
                    raise OSError('local interface addresses unavailable')
                if seq == 1 and kind == 20:  # RTM_NEWADDR
                    address = parse_address(reply[offset + 16:offset + length])
                    if address:
                        addresses.add(address)
                offset += (length + 3) & ~3
    raise TimeoutError('local interface address query timed out')


def open_link(index, local):
    family = socket.AF_INET if local.version == 4 else socket.AF_INET6
    sock = socket.socket(family, socket.SOCK_DGRAM)
    try:
        # An ephemeral source port requests a unicast mDNS reply (RFC 6762, §6.7).
        if family == socket.AF_INET:
            sock.bind((str(local.ip), 0))
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, local.ip.packed)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            destination = ('224.0.0.251', 5353)
        else:
            sock.bind((str(local.ip), 0, 0, index if local.ip.is_link_local else 0))
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, index)
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 255)
            destination = ('ff02::fb', 5353, 0, index)
        return sock, destination
    except BaseException:
        sock.close()
        raise


class Replies(asyncio.DatagramProtocol):
    def __init__(self, queue, link):
        self.queue = queue
        self.link = link

    def datagram_received(self, data, address):
        with suppress(asyncio.QueueFull):
            self.queue.put_nowait((self.link, data, address))


def update_records(dns, state, packet, link):
    index, local = link
    for rr in dns.parse_mdns_message(packet):
        kind, name = rr['type'], rr['name'].lower()
        if not rr['ttl']:
            continue
        if kind == dns.QTYPE_PTR and name == dns.REMOTEPAIRING_SERVICE_NAME and rr.get('ptrdname'):
            state.ptr_targets.add(rr['ptrdname'].lower())
        elif kind == dns.QTYPE_SRV and rr.get('target') and rr.get('port'):
            srv = {'target': rr['target'].lower(), 'port': rr['port']}
            if srv not in state.srv_map[name]:
                state.srv_map[name].append(srv)
        elif kind == dns.QTYPE_TXT:
            state.txt_map[name] = rr['txt']
        elif kind in (dns.QTYPE_A, dns.QTYPE_AAAA) and rr.get('address'):
            ip = ipaddress.ip_address(rr['address'])
            if ip in local.network and ip != local.ip and not ip.is_multicast and not ip.is_unspecified:
                value = dns.Address(ip=str(ip), iface=str(index))
                if value not in state.host_addrs[name]:
                    state.host_addrs[name].append(value)


async def browse_scoped(dns, timeout):
    loop = asyncio.get_running_loop()
    queue = asyncio.Queue(maxsize=128)
    links = {}
    try:
        try:
            addresses = await asyncio.to_thread(local_addresses)
        except OSError:
            addresses = []
        for link in addresses:
            try:
                sock, destination = open_link(*link)
                try:
                    transport, _ = await loop.create_datagram_endpoint(lambda link=link: Replies(queue, link), sock=sock)
                except BaseException:
                    sock.close()
                    raise
            except OSError:
                continue
            links[link] = transport, destination, dns._BrowseState()
        if not links:
            logging.info('Local mDNS interfaces unavailable; trying default discovery')
            return

        query = dns.build_query(dns.REMOTEPAIRING_SERVICE_NAME, dns.QTYPE_PTR, unicast=True)
        expires = loop.time() + timeout
        resend = 0
        seen = set()
        while loop.time() < expires:
            if loop.time() >= resend:
                for transport, destination, _ in links.values():
                    with suppress(OSError):
                        transport.sendto(query, destination)
                resend = loop.time() + 1
            try:
                link, packet, source = await asyncio.wait_for(queue.get(), min(resend, expires) - loop.time())
            except asyncio.TimeoutError:
                continue
            try:
                if source[1] != 5353 or ipaddress.ip_address(source[0].split('%')[0]) not in link[1].network:
                    continue
                if not 12 <= len(packet) <= 9000 or not packet[2] & 0x80:
                    continue
                if sum(struct.unpack_from('!4H', packet, 4)) > 256:
                    continue
                _, _, state = links[link]
                update_records(dns, state, packet, link)
            except (ValueError, KeyError, IndexError, struct.error):
                continue
            for answer in dns._assemble_instances(state):
                tag = answer.properties.get('authTag')
                identifier = answer.properties.get('identifier')
                key = (link, answer.instance, answer.port, tuple(a.full_ip for a in answer.addresses), identifier, tag)
                if answer.addresses and identifier and tag and key not in seen:
                    seen.add(key)
                    yield answer
    finally:
        for transport, _, _ in links.values():
            transport.close()


async def browse_remotepairing(dns, fallback, timeout):
    async with aclosing(browse_scoped(dns, timeout=timeout)) as answers:
        async for answer in answers:
            yield answer
    # Retain ordinary multicast browsing for responders which omit a unicast reply.
    async with aclosing(fallback(timeout=min(timeout, 3))) as answers:
        async for answer in answers:
            yield answer


def configure():
    from pymobiledevice3 import bonjour
    from pymobiledevice3.remote import tunnel_service

    # Keep the pinned library's altIRK/authTag matching and RemotePairing verification.
    tunnel_service.iter_browse_remotepairing = partial(
        browse_remotepairing, bonjour, tunnel_service.iter_browse_remotepairing)
