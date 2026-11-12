"""Validation-only contract for future external GNSS adapters; never injects locations."""
from dataclasses import dataclass
import math
from typing import Optional, Tuple

SOURCE_TYPES = frozenset({'ble_lns', 'bluetooth_nmea', 'usb_nmea', 'tcp_nmea', 'udp_nmea'})
ALTITUDE_DATUMS = frozenset({'wgs84_ellipsoid', 'mean_sea_level', 'unknown'})


@dataclass(frozen=True)
class ExternalFix:
    source_type: str
    receiver_id: str
    latitude: float
    longitude: float
    sample_time_utc_s: Optional[float]
    received_time_utc_s: float
    received_monotonic_s: float
    accuracy_m: Optional[float]
    altitude_m: Optional[float]
    altitude_datum: Optional[str]
    speed_m_s: Optional[float]
    course_deg: Optional[float]
    is_mock: Optional[bool]
    valid: bool
    coordinate_system: str = 'WGS84'


@dataclass(frozen=True)
class WorkerCompatibility:
    compatible: bool
    reasons: Tuple[str, ...]
    # No worker payload: current modes do not recognize external source types.


def _number(name, value, *, optional=False):
    if value is None and optional:
        return
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f'{name} must be a finite number' + (' or None' if optional else ''))


def validate_fix(fix: ExternalFix, *, now_utc_s: float, now_monotonic_s: float) -> ExternalFix:
    """Validate at reception; times use seconds, UTC epoch and this host's monotonic clock."""
    if not isinstance(fix, ExternalFix):
        raise ValueError('expected ExternalFix')
    if type(fix.source_type) is not str or fix.source_type not in SOURCE_TYPES or type(fix.receiver_id) is not str or not fix.receiver_id.strip() or len(fix.receiver_id) > 128:
        raise ValueError('invalid source_type or receiver_id')
    if fix.coordinate_system != 'WGS84' or fix.valid is not True or fix.is_mock not in (False, None) or type(fix.is_mock) not in (bool, type(None)):
        raise ValueError('invalid coordinate system, validity or mock status')
    for name in ('latitude', 'longitude', 'received_time_utc_s', 'received_monotonic_s'):
        _number(name, getattr(fix, name))
    for name in ('sample_time_utc_s', 'accuracy_m', 'altitude_m', 'speed_m_s', 'course_deg'):
        _number(name, getattr(fix, name), optional=True)
    _number('now_utc_s', now_utc_s)
    _number('now_monotonic_s', now_monotonic_s)
    if not (-90 <= fix.latitude <= 90 and -180 <= fix.longitude <= 180):
        raise ValueError('coordinates out of range')
    if (fix.received_time_utc_s < 0 or fix.received_time_utc_s > now_utc_s or
        fix.received_monotonic_s < 0 or fix.received_monotonic_s > now_monotonic_s or
        (fix.sample_time_utc_s is not None and not 0 <= fix.sample_time_utc_s <= fix.received_time_utc_s)):
        raise ValueError('future or invalid timestamp')
    if fix.accuracy_m is not None and fix.accuracy_m < 0:
        raise ValueError('accuracy_m must be nonnegative')
    if fix.speed_m_s is not None and fix.speed_m_s < 0:
        raise ValueError('speed_m_s must be nonnegative')
    if fix.course_deg is not None and not 0 <= fix.course_deg < 360:
        raise ValueError('course_deg must be in [0, 360)')
    if (fix.altitude_m is None and fix.altitude_datum is not None) or (fix.altitude_m is not None and (type(fix.altitude_datum) is not str or fix.altitude_datum not in ALTITUDE_DATUMS)):
        raise ValueError('altitude and datum must be supplied together')
    return fix


def worker_compatibility(fix: ExternalFix, *, now_utc_s: float, now_monotonic_s: float) -> WorkerCompatibility:
    """Assess blockers only; this does not build a payload or authorize injection."""
    validate_fix(fix, now_utc_s=now_utc_s, now_monotonic_s=now_monotonic_s)
    reasons = ['unsupported_external_source']
    if fix.accuracy_m is None:
        reasons.append('missing_accuracy')
    if fix.is_mock is None:
        reasons.append('unknown_mock_status')
    if fix.sample_time_utc_s is None:
        reasons.append('unknown_sample_time')
    elif (fix.received_time_utc_s - fix.sample_time_utc_s + now_monotonic_s - fix.received_monotonic_s) > 10:
        reasons.append('stale_sample')
    return WorkerCompatibility(False, tuple(reasons))
