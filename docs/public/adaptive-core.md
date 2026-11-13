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

GNSS-assisted inertial prediction is experimental: its maximum horizon is eight seconds with the preferred backend and known heading accuracy, and three seconds for a degraded backend or unknown heading accuracy. Other checks can stop it earlier. A pressure sensor, when available, reports pressure in hPa while inertial collection is enabled; pressure is not substituted for geometric altitude.

Root access is not required. The helper does not invoke `su` or modify a vendor HAL. The core has not been tested on a Redmi K60 Pro, and the same sensor availability, accuracy or performance should not be assumed across models.
