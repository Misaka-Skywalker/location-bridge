import asyncio
from collections import defaultdict
from contextlib import aclosing
import ipaddress
from pathlib import Path
import socket
import struct
import sys
import unittest
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android/repo'))
import bridge_discovery


class LocalDiscoveryTests(unittest.TestCase):
    def test_hotspot_is_selected_from_address_dump_without_a_default_route(self):
        def address(family, prefix, index, local):
            encoded = socket.inet_pton(family, local)
            return struct.pack('=4BIHH', family, prefix, 0, 0, index, len(encoded) + 4, 2) + encoded

        records = [address(socket.AF_INET, 24, 1, '127.0.0.1'),
                   address(socket.AF_INET, 24, 22, '192.168.231.1'),
                   address(socket.AF_INET6, 64, 22, 'fe80::1')]
        selected = [link for item in records if (link := bridge_discovery.parse_address(item)) is not None]
        self.assertEqual(selected, [(22, ipaddress.ip_interface('192.168.231.1/24')),
                                    (22, ipaddress.ip_interface('fe80::1/64'))])

    def test_ipv4_query_uses_hotspot_address_and_unicast_reply_socket(self):
        sock = mock.Mock()
        with mock.patch.object(bridge_discovery.socket, 'socket', return_value=sock):
            actual, destination = bridge_discovery.open_link(22, ipaddress.ip_interface('192.168.231.1/24'))
        self.assertIs(actual, sock)
        self.assertEqual(destination, ('224.0.0.251', 5353))
        sock.bind.assert_called_once_with(('192.168.231.1', 0))
        sock.setsockopt.assert_any_call(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton('192.168.231.1'))

    def test_ipv6_query_uses_link_scope(self):
        sock = mock.Mock()
        with mock.patch.object(bridge_discovery.socket, 'socket', return_value=sock):
            _, destination = bridge_discovery.open_link(22, ipaddress.ip_interface('fe80::1/64'))
        self.assertEqual(destination, ('ff02::fb', 5353, 0, 22))
        sock.bind.assert_called_once_with(('fe80::1', 0, 0, 22))
        sock.setsockopt.assert_any_call(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, 22)


class AdvertisementTests(unittest.IsolatedAsyncioTestCase):
    async def test_hotspot_advertisement_requires_local_address_and_pairing_records(self):
        hotspot = (22, ipaddress.ip_interface('192.168.231.1/24'))
        service = '_remotepairing._tcp.local.'
        instance = 'remote.' + service
        host = 'peer.local.'
        tag = {'identifier': 'rotating-id', 'authTag': 'challenge-response'}
        packet = b'\0\0\x80' + b'\0' * 9
        records = [
            dict(type=12, name=service, ptrdname=instance),
            dict(type=33, name=instance, target=host, port=42100),
            dict(type=16, name=instance, txt=tag),
            dict(type=1, name=host, address='10.0.0.39'),
            dict(type=1, name=host, address='192.168.231.11'),
        ]
        def make_state():
            return SimpleNamespace(ptr_targets=set(), srv_map=defaultdict(list),
                                   txt_map={}, host_addrs=defaultdict(list))
        def assemble(state):
            return [SimpleNamespace(instance=name, port=item['port'], addresses=state.host_addrs[item['target']],
                                    properties=state.txt_map.get(name, {}))
                    for name in state.ptr_targets for item in state.srv_map[name]]
        dns = SimpleNamespace(QTYPE_PTR=12, QTYPE_SRV=33, QTYPE_TXT=16, QTYPE_A=1, QTYPE_AAAA=28,
                              REMOTEPAIRING_SERVICE_NAME=service, _BrowseState=make_state,
                              _assemble_instances=assemble, build_query=mock.Mock(return_value=b'query'),
                              parse_mdns_message=lambda raw: [dict(rr, ttl=120) for rr in records],
                              Address=lambda ip, iface: SimpleNamespace(ip=ip, full_ip=ip, iface=iface))
        transport = mock.Mock()
        def endpoint(factory, **kwargs):
            protocol = factory()
            protocol.datagram_received(packet, ('10.0.0.39', 5353))
            protocol.datagram_received(packet, ('192.168.231.11', 5353))
            return transport, protocol
        loop = asyncio.get_running_loop()
        with mock.patch.object(bridge_discovery, 'local_addresses', return_value=[hotspot]), \
             mock.patch.object(bridge_discovery, 'open_link', return_value=(mock.Mock(), ('224.0.0.251', 5353))), \
             mock.patch.object(loop, 'create_datagram_endpoint', side_effect=endpoint):
            async with aclosing(bridge_discovery.browse_scoped(dns, timeout=1)) as replies:
                answer = await anext(replies)
        transport.sendto.assert_called_once_with(b'query', ('224.0.0.251', 5353))
        transport.close.assert_called_once()
        self.assertEqual(answer.properties, tag)
        self.assertEqual([(address.ip, address.iface) for address in answer.addresses], [('192.168.231.11', '22')])
