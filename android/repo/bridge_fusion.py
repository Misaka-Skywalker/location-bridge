"""Strict client for the locally authenticated Android location helper."""
import json
import math
import re
import socket
import hashlib
import hmac
import secrets
import time
import uuid

SOCKET_ADDRESS = ('127.0.0.1', 18766)
TIMEOUT_SECONDS = 2.0
MAX_RESPONSE_BYTES = 16 * 1024
PROVIDERS = ('fused', 'gps', 'network')


def _integer(value, *, minimum=0):
    return type(value) is int and value >= minimum


def _number(value, minimum, maximum):
    if type(value) not in (int, float) or not math.isfinite(value) or not minimum <= value <= maximum:
        raise ValueError('invalid_number')
    return value


def _pairs_unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('invalid_json')
        result[key] = value
    return result


def _reject_constant(_value):
    raise ValueError('invalid_json')


def validate_snapshot(value):
    if not isinstance(value, dict) or type(value.get('protocol')) is not int or value['protocol'] != 1:
        raise ValueError('invalid_protocol')
    try:
        session = value['session_id']
        if not isinstance(session, str) or str(uuid.UUID(session)) != session: raise ValueError
    except (ValueError, KeyError, AttributeError):
        raise ValueError('invalid_session') from None
    if type(value.get('running')) is not bool: raise ValueError('invalid_running')
    stamp = value.get('elapsed_realtime_nanos')
    if not _integer(stamp, minimum=1): raise ValueError('invalid_snapshot_time')
    providers = value.get('providers')
    if not isinstance(providers, dict): raise ValueError('invalid_providers')
    for provider in PROVIDERS:
        entry = providers.get(provider)
        if not isinstance(entry, dict) or type(entry.get('available')) is not bool or type(entry.get('enabled')) is not bool or 'error' not in entry:
            raise ValueError('invalid_providers')
        err = entry['error']
        if err is not None and (not isinstance(err, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', err)):
            raise ValueError('invalid_providers')
    if 'sample' not in value: raise ValueError('invalid_sample')
    sample = value['sample']
    if sample is None: return value
    if not isinstance(sample, dict) or sample.get('source') not in (*PROVIDERS, 'inertial'):
        raise ValueError('invalid_sample')
    if not _integer(sample.get('sequence')): raise ValueError('invalid_sequence')
    sample_stamp = sample.get('elapsed_realtime_nanos')
    if not _integer(sample_stamp, minimum=1) or sample_stamp > stamp: raise ValueError('invalid_sample_time')
    if not _integer(sample.get('timestamp_ms'), minimum=1): raise ValueError('invalid_timestamp')
    if sample.get('is_mock') is not False: raise ValueError('mock_location')
    if sample.get('coordinate_system') != 'WGS84': raise ValueError('invalid_coordinate_system')
    _number(sample.get('latitude'), -90, 90)
    _number(sample.get('longitude'), -180, 180)
    _number(sample.get('accuracy_m'), 0, math.inf if sample['source'] == 'inertial' else 1e9)
    if sample['source'] == 'inertial':
        anchor = sample.get('anchor_elapsed_realtime_nanos')
        predicted = sample.get('prediction_age_ms')
        if (sample.get('accuracy_kind') != 'model_estimate' or sample.get('anchor_source') != 'gps'
                or not _integer(anchor, minimum=1) or anchor > sample_stamp):
            raise ValueError('invalid_inertial_metadata')
        _number(predicted, 1500, math.inf)
        if predicted <= 1500 or not math.isclose(predicted, (sample_stamp-anchor)/1e6, rel_tol=0, abs_tol=1e-6):
            raise ValueError('invalid_inertial_metadata')
    return value


def parse_snapshot(raw, run_id, sequence, now, roundtrip_seconds):
    validate_snapshot(raw)
    if not isinstance(run_id, str) or not run_id or not _integer(sequence): raise ValueError('invalid_run')
    _number(now, 0, 1e12)
    _number(roundtrip_seconds, 0, 600)
    sample = raw['sample']
    if not raw['running'] or sample is None: return None, None
    age_ms = (raw['elapsed_realtime_nanos'] - sample['elapsed_realtime_nanos']) / 1e6 + roundtrip_seconds * 1000
    if sample['source'] == 'inertial':
        sample_time = sample['elapsed_realtime_nanos'] / 1e9
        anchor_time = sample['anchor_elapsed_realtime_nanos'] / 1e9
        snapshot_time = raw['elapsed_realtime_nanos'] / 1e9
        if snapshot_time > now or sample_time > now or anchor_time > now:
            raise ValueError('invalid_sample_time')
        if max(age_ms / 1000, now - sample_time) > .5:
            return None, None
    elif age_ms > 10000: return None, None
    fix = {'run_id':run_id, 'sequence':sequence, 'source':sample['source'],
           'latitude':sample['latitude'], 'longitude':sample['longitude'], 'accuracy_m':sample['accuracy_m'],
           'received_monotonic':now, 'age_at_receive_ms':age_ms}
    if sample['source'] == 'inertial':
        for key in ('accuracy_kind','anchor_source','anchor_elapsed_realtime_nanos',
                    'elapsed_realtime_nanos','prediction_age_ms'):
            fix[key] = sample[key]
    return fix, (raw['session_id'], sample['sequence'])


def decode_json(data):
    try:
        return json.loads(data, parse_constant=_reject_constant, object_pairs_hook=_pairs_unique)
    except (UnicodeError, json.JSONDecodeError, RecursionError, ValueError):
        raise ValueError('invalid_json') from None


def request_snapshot(key_hex, operation='snapshot'):
    if not isinstance(key_hex, str) or not re.fullmatch(r'[0-9a-f]{64}', key_hex):
        raise ValueError('invalid_key')
    if operation not in ('snapshot', 'activate', 'stop', 'external_snapshot'): raise ValueError('invalid_operation')
    key = bytes.fromhex(key_hex)
    nonce = secrets.token_hex(32)
    def mac(message): return hmac.new(key, message.encode('utf-8'), hashlib.sha256).hexdigest()
    request = {'nonce': nonce, 'operation': operation, 'mac': mac('request\n' + nonce + '\n' + operation)}
    deadline = time.monotonic() + TIMEOUT_SECONDS
    def remaining(sock):
        left = deadline - time.monotonic()
        if left <= 0: raise ValueError('timeout')
        sock.settimeout(left)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            remaining(sock)
            sock.connect(SOCKET_ADDRESS)
            remaining(sock)
            sock.sendall(json.dumps(request,separators=(',',':')).encode('ascii') + b'\n')
            data = bytearray()
            while b'\n' not in data:
                remaining(sock)
                chunk = sock.recv(min(4096, MAX_RESPONSE_BYTES + 1 - len(data)))
                if not chunk: raise ValueError('incomplete_response')
                data.extend(chunk)
                if len(data) > MAX_RESPONSE_BYTES: raise ValueError('response_too_large')
                if len(data) == MAX_RESPONSE_BYTES and b'\n' not in data: raise ValueError('response_too_large')
            line, extra = bytes(data).split(b'\n', 1)
            if extra: raise ValueError('invalid_response')
            envelope = decode_json(line)
            if not isinstance(envelope,dict) or set(envelope) != {'nonce','payload','mac'}:
                raise ValueError('invalid_response')
            if (envelope['nonce'] != nonce or not isinstance(envelope['payload'],str)
                    or not isinstance(envelope['mac'],str) or not re.fullmatch(r'[0-9a-f]{64}',envelope['mac'])):
                raise ValueError('authentication_failed')
            if not hmac.compare_digest(envelope['mac'], mac('response\n'+nonce+'\n'+envelope['payload'])):
                raise ValueError('authentication_failed')
            value = decode_json(envelope['payload'])
            if operation == 'external_snapshot':
                from bridge_external import validate_external_snapshot
                return validate_external_snapshot(value)
            return validate_snapshot(value)
    except socket.timeout as exc:
        raise ValueError('timeout') from exc
    except OSError as exc:
        raise ValueError('socket_error') from exc
