"""Read-only adapter for authenticated BLE observations; never injects the iPad."""
import math
import re
from external_location import ExternalFix, validate_fix

def _integer(value, minimum=0):
    return type(value) is int and value >= minimum

def _fix(raw):
    sample=raw['sample']
    required={'source_type','sequence','latitude','longitude','coordinate_system','position_status',
              'accuracy_m','is_mock','timestamp_ms','received_timestamp_ms',
              'received_elapsed_realtime_nanos','altitude_m','altitude_datum','speed_m_s','course_deg'}
    if not isinstance(sample,dict) or not required <= sample.keys(): raise ValueError('invalid_external_sample')
    if (sample['source_type']!='ble_lns' or type(sample['position_status']) is not int
            or sample['position_status']!=1 or not _integer(sample['sequence'],1)
            or not _integer(sample['received_timestamp_ms'],1)
            or not _integer(sample['received_elapsed_realtime_nanos'],1)
            or sample['received_elapsed_realtime_nanos']>raw['elapsed_realtime_nanos']
            or (sample['timestamp_ms'] is not None and not _integer(sample['timestamp_ms'],1))):
        raise ValueError('invalid_external_sample')
    return ExternalFix(source_type=sample['source_type'],receiver_id=raw['receiver_id'],
        latitude=sample['latitude'],longitude=sample['longitude'],
        sample_time_utc_s=None if sample['timestamp_ms'] is None else sample['timestamp_ms']/1000,
        received_time_utc_s=sample['received_timestamp_ms']/1000,
        received_monotonic_s=sample['received_elapsed_realtime_nanos']/1e9,
        accuracy_m=sample['accuracy_m'],altitude_m=sample['altitude_m'],
        altitude_datum=sample['altitude_datum'],speed_m_s=sample['speed_m_s'],course_deg=sample['course_deg'],
        is_mock=sample['is_mock'],valid=True,coordinate_system=sample['coordinate_system'])

def validate_external_snapshot(raw):
    if (not isinstance(raw,dict) or type(raw.get('protocol')) is not int or raw['protocol']!=1
            or raw.get('kind')!='external_gnss' or type(raw.get('connected')) is not bool
            or not isinstance(raw.get('state'),str) or not re.fullmatch('[a-z_]{1,64}',raw['state'])
            or not _integer(raw.get('elapsed_realtime_nanos'),1) or 'sample' not in raw):
        raise ValueError('invalid_external_envelope')
    if 'selection' in raw:
        selection=raw['selection']
        reasons={'eligible','manual_excluded','static_txt','waiting_position','stale_position','disconnected'}
        if (not isinstance(selection,dict)
                or any(type(selection.get(k)) is not bool for k in ('eligible','manual_excluded','waiting_after_txt'))
                or type(selection.get('reason')) is not str or selection['reason'] not in reasons
                or selection['eligible'] != (selection['reason']=='eligible')
                or (selection['eligible'] and (not raw['connected'] or selection['manual_excluded'] or selection['waiting_after_txt']))
                or (selection['manual_excluded'] and selection['reason']!='manual_excluded')
                or (selection['reason']=='manual_excluded' and not selection['manual_excluded'])
                or (selection['reason']=='static_txt' and not selection['waiting_after_txt'])):
            raise ValueError('invalid_external_selection')
    if raw['sample'] is not None:
        if not raw['connected']: raise ValueError('disconnected_sample')
        fix=_fix(raw)
        validate_fix(fix,now_utc_s=fix.received_time_utc_s,now_monotonic_s=raw['elapsed_realtime_nanos']/1e9)
    return raw

def parse_external_snapshot(raw, *, now_utc_s, now_boottime_s):
    validate_external_snapshot(raw)
    # Older helpers remain readable for diagnostics but cannot bypass source exclusion.
    if 'selection' not in raw or not raw['selection']['eligible']: return None,None
    if not raw['connected'] or raw['sample'] is None: return None,None
    fix=_fix(raw)
    validate_fix(fix,now_utc_s=now_utc_s,now_monotonic_s=now_boottime_s)
    # Receipt age alone cannot prove a receiver's fix is fresh when sample time is unknown.
    # The contract retains unknown time; the existing worker will not inject this channel.
    age=now_boottime_s-fix.received_monotonic_s
    if fix.sample_time_utc_s is not None: age+=fix.received_time_utc_s-fix.sample_time_utc_s
    if age>10: return None,None
    return fix,(raw['receiver_id'],raw['sample']['sequence'])
