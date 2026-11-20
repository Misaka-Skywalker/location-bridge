# Adaptive positioning core

[简体中文](adaptive-core.zh-CN.md) · [Home](../../README.md)

Version 0.4.0 adds an experimental adaptive core to the Android helper. Its algorithms and diagnostics do not change the iPad transport, which still sends latitude and longitude only. Device checks confirmed native location, GNSS diagnostics and authenticated Termux snapshots. Moving end-to-end and long-duration validation are still pending. It is not intended for safety-critical use.

## Location selection and requests

The helper requests high-accuracy locations from the Android platform GPS, fused and network providers when available. It checks freshness and quality and chooses among candidates. A recent, quality-checked GNSS ground-speed estimate increases the age penalty used when comparing candidates; the measurement's reported accuracy is preserved, not rewritten. Depending on recent location and speed, GPS and fused requests adapt between 500 ms and 1,000 ms with hysteresis; network requests remain at 1,000 ms. These are requested intervals, not a guarantee of 2 Hz or any particular delivery rate. Availability, provider behavior and system power management affect results. [Android LocationRequest documentation](https://developer.android.com/reference/android/location/LocationRequest.Builder)

## GNSS diagnostics

The helper requests GNSS full tracking by default and provides a setting to turn that request off. A request can be accepted without the system reporting whether full tracking actually occurred; the reported state may remain unknown. Turning this request off does not force other clients or the firmware to stop full tracking. Full tracking can consume more power and its operation depends on the device and OS. [Android full-tracking documentation](https://developer.android.com/reference/android/location/GnssMeasurementRequest.Builder#setFullTracking(boolean))

The diagnostics summarize available satellite status, observed constellations and bands, raw measurement counts, automatic gain control (AGC) and antenna capabilities when the platform exposes them. The receiver page counts distinct constellation/signal types, not independent RF bands or antenna channels. Satellite totals are deduplicated across signal types. These observations are diagnostics; this is not RTK or a custom raw-observation positioning solver, and a measurement count does not establish position accuracy. [Android GNSS measurements](https://developer.android.com/reference/android/location/GnssMeasurementsEvent) · [Android GNSS capabilities](https://developer.android.com/reference/android/location/GnssCapabilities)

## Motion and pressure

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
