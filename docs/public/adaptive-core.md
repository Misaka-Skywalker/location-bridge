# Adaptive positioning core

[简体中文](adaptive-core.zh-CN.md) · [Home](../../README.md)

Version 0.4.0 adds an experimental adaptive core to the Android helper. Its algorithms and diagnostics do not change the iPad transport, which still sends latitude and longitude only. Device checks confirmed native location, GNSS diagnostics and authenticated Termux snapshots. Moving end-to-end and long-duration validation are still pending. It is not intended for safety-critical use.

## Location selection and requests

The helper requests high-accuracy locations from the Android platform GPS, fused and network providers when available. It checks freshness and quality and chooses among candidates. A recent, quality-checked GNSS ground-speed estimate increases the age penalty used when comparing candidates; the measurement's reported accuracy is preserved, not rewritten. Depending on recent location and speed, GPS and fused requests adapt between 500 ms and 1,000 ms with hysteresis; network requests remain at 1,000 ms. These are requested intervals, not a guarantee of 2 Hz or any particular delivery rate. Availability, provider behavior and system power management affect results. [Android LocationRequest documentation](https://developer.android.com/reference/android/location/LocationRequest.Builder)

## Source changes and stationary drift

Since 0.6.1, GPS and fused fixes of similar quality keep the active source when their timestamps differ by up to one normal reporting interval (one second). The active source keeps publishing during a challenge. Repeated evidence of better accuracy or a stalled source can trigger a handover.

When GNSS reports near-zero speed continuously for two seconds with usable speed accuracy, the helper smooths successive positions from the same GPS or fused provider with a five-second time constant. Motion, missing speed evidence, a source change or a large correction bypasses smoothing. The POS page indicates when the filter is active. In the snapshot, `sample` contains the forwarded coordinates, `native_sample` contains the raw fix, and `accuracy_m` remains the provider's reported accuracy for that fix.

In 0.6.2, the POS page shows **TRACK SMOOTHING** when reliable GNSS ground speed and course cover the interval between two fixes from the same GPS or fused provider. The helper integrates the changing velocity between those measurement times and smooths the difference between the expected and measured positions. This allows a turn or a speed change to appear in the current fix. The adjustment is capped at the smaller of 8 m and 1.5 times the reported accuracy. A source change, a large position correction or a stop passes the first new fix through immediately. The helper retains the original measurement timestamp. [Android speed and bearing accuracy](https://developer.android.com/reference/android/location/Location)

When velocity samples do not bracket the interval, three seconds of consistent low-speed travel can still enable the cross-track filter. It preserves distance along the measured course. An existing 0.6.0 Termux bridge can use both filters after upgrading the helper.

## GNSS diagnostics

The helper requests GNSS full tracking by default and provides a setting to turn that request off. A request can be accepted without the system reporting whether full tracking actually occurred; the reported state may remain unknown. Turning this request off does not force other clients or the firmware to stop full tracking. Full tracking can consume more power and its operation depends on the device and OS. [Android full-tracking documentation](https://developer.android.com/reference/android/location/GnssMeasurementRequest.Builder#setFullTracking(boolean))

The diagnostics summarize available satellite status, observed constellations and bands, raw measurement counts, automatic gain control (AGC) and antenna capabilities when the platform exposes them. The receiver page counts distinct constellation/signal types, not independent RF bands or antenna channels. Satellite totals are deduplicated across signal types. These observations are diagnostics; this is not RTK or a custom raw-observation positioning solver, and a measurement count does not establish position accuracy. [Android GNSS measurements](https://developer.android.com/reference/android/location/GnssMeasurementsEvent) · [Android GNSS capabilities](https://developer.android.com/reference/android/location/GnssCapabilities)

## Motion and pressure

The INS integrates the average horizontal acceleration over closely spaced sample pairs. This dampens rapid vibration and preserves a sustained acceleration or turn. If fast phone rotation coincides with a sharp change in linear acceleration, the INS can briefly propagate at the last estimated ground velocity. A new disturbance can extend this period. Clean samples resume normal integration 150 ms after the last trigger. The INS page shows **INS COAST** during these intervals, and the modeled position uncertainty grows to account for the missing acceleration. Sensor gaps and excessive horizontal acceleration still invalidate the estimate.

Bench replays cover horizontal vibration, vertical motion with a tilted phone, and a real change in horizontal velocity. The short disturbance detector can also trigger on genuine vehicle motion with similar sensor readings. These checks do not establish performance in flight.

On devices with a calibrated gyroscope, the helper uses recent angular-rate samples to align the attitude with the linear-acceleration timestamp over intervals of up to 100 ms. The resulting horizontal acceleration updates the ground-velocity vector. Rotating the phone alone does not supply a ground-track change. Missing, saturated or unreliable gyroscope samples leave the existing rotation-vector path available.

The preferred motion backend uses the system rotation vector and linear acceleration. Android's rotation vector is a fused orientation sensor, typically using gyroscope and magnetic-field inputs; its underlying implementation and available sensors vary by device. If the preferred streams are missing or cannot be registered, the helper can use a geomagnetic rotation vector or subtract a suitably timed gravity sample from accelerometer data, subject to quality and freshness gates. [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position) · [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)

A pressure sensor, when available, reports pressure in hPa while inertial collection is enabled; pressure is not substituted for geometric altitude.

## Continuous INS in 0.5.0

The platform `fused` provider is a real input to location selection alongside GPS and network. This uses `LocationManager`, not the Google Play services fused-location SDK. The phone determines how its fused provider combines inputs; registering it does not prove that a particular radio or sensor contributed to a fix. See the SYS page for registration and the POS page for the selected source. [Android fused provider](https://developer.android.com/reference/android/location/LocationManager#FUSED_PROVIDER)

Usable measured locations always take priority. When none remain, GNSS-initialized INS can supply positions with no elapsed-time limit and no 150 m model-uncertainty cutoff. Both the APK and Termux worker must be updated together. As of 0.6.0, sequence numbers increase across source changes. Each source retains its original measurement time. A fresh, eligible fix from a different provider can take over even when it was measured slightly earlier.

INS needs a recent, quality-checked GNSS position and velocity to initialize. A sufficiently precise near-zero speed can initialize at rest without a movement track. Rotation and acceleration data must continue to meet quality and timing checks: an acceleration gap over 250 ms or a prediction older than 500 ms prevents continued output. Invalid orientation, numerical failure, excessive acceleration or speed, and the supported latitude limit can also stop propagation. An anchor is not fabricated when these conditions fail.

Propagation advances in local WGS84 coordinates. The last accepted GNSS magnetic reference remains in use until a new one arrives, with its age available in diagnostics; this reference can become inaccurate over a long journey. Degraded sensor backends increase modeled uncertainty rather than shorten a time limit. Phone sensor bias accumulates, so unlimited duration does not imply reliable long-term accuracy.

The INS page displays its own latitude/longitude, modeled uncertainty, ground speed, time since GNSS alignment, and the last correction residual. The residual is the **pre-correction INS position minus the incoming GNSS position at the same measurement time**, with total distance and signed east/north components. Positive values mean east/north. It is retained with its age until the next successful correction; GNSS error contributes to it, so it is not an independent measurement of INS error. During an outage, no new measured residual is available.

Ground speed is displayed simultaneously in km/h and kts; protocol fields and calculations stay in m/s. Optional snapshot fields `inertial.position` and `inertial.last_correction` carry the diagnostics. A null position means no current usable INS estimate; a null correction means none has been recorded. `maximum_horizon_s` and sensor `prediction_horizon_s` are null, with `time_limit_enabled=false`.

Offline regression tests cover a one-hour synthetic track, growing uncertainty, delayed GNSS correction, measured-source priority and stale-motion rejection. They do not validate physical sensor drift or sustained operation on a moving phone.

Root access is not required. The helper does not invoke `su` or modify a vendor HAL. The core has not been tested on a Redmi K60 Pro, and the same sensor availability, accuracy or performance should not be assumed across models.
