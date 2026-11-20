# Display terminology

[简体中文](display-terms.zh-CN.md) · [Home](../../README.md)

The English interface uses short instrument-style labels. Page titles, status lines and action keys use uppercase text; permission explanations retain full sentences. This is a display convention, not a reproduction of one aircraft system.

| Label | Meaning in this app |
| --- | --- |
| POS / LAT / LON | Position / latitude / longitude. |
| GS | Ground speed, displayed in km/h and kts. It is not airspeed. |
| TRK TRUE / TRK MAG | Direction of movement relative to true / magnetic north. These are not aircraft or phone heading. |
| MAG VAR (EST) | Estimated magnetic variation from Android's magnetic model. East is positive in the true-to-magnetic track calculation. |
| WGS84 ELLIP ALT | Height above the WGS84 ellipsoid, shown in metres and feet. |
| H ACC / V ACC / GS ACC | The source-reported horizontal, vertical and speed accuracy. The app does not relabel these as protection levels. |
| INS | The app's GNSS-initialized horizontal inertial estimate. |
| INS EPU | Estimated Position Uncertainty from the current INS model. Its confidence level is uncalibrated; this is not a validated 95% containment bound, ANP, HFOM or protection-level output. |
| LAST POS Δ | Last pre-correction INS position minus the same-time GNSS position; GNSS uncertainty also contributes to the difference. |
| ΔE (+E) / ΔN (+N) | Signed east / north components of that difference. |
| GNSS UPD AGE / POS Δ AGE | Time since the last GNSS alignment. The INS page shows a separate age for the recorded position difference only when it came from an earlier alignment. |
| ALIGN WAIT / ALIGNED / IN USE | Waiting for a valid GNSS anchor / estimate initialized / INS selected for output. ALIGNED does not mean that the INS source is currently selected. |
| ON / OFF | Feature enabled / disabled, not a declaration of hardware serviceability. |
| UNKNOWN / UNAVAIL / NO DATA | State unknown / resource unavailable / measurement absent. Missing readings are shown as `---` in their usual positions. |
| ATT / ACCEL | Attitude / acceleration. ACCEL is kept distinct from accuracy (ACC). |
| SV USED / SV VISIBLE | Satellites used in a GNSS fix / observed satellites, deduplicated across signals. |
| FULL TRK REQ / HW CONFIRMED | GNSS full-tracking request / whether the hardware reports full tracking. An accepted request alone does not confirm operation. |
| RX / TX | Reception / transmission. LAST TX refers to the bridge's reported last applied source, not simply the phone's currently selected source. |
| BLE / RSSI / MTU | Bluetooth Low Energy / received signal strength indicator / maximum transmission unit. These retain their protocol meanings. |
| DESELECT / RESELECT | Exclude / restore eligibility of the external BLE position while retaining its Bluetooth connection. |
| LSK | Line-select key: the side key beside an action label. |

**POS START / POS STOP** control native location collection. **ENABLE INS / DISABLE INS** change the inertial feature setting. **CONNECT / DISCONNECT** control the iPad bridge. The label describes the requested action; status is shown separately. **SETUP** opens settings.

The naming follows established terminology where its meaning matches the data. See [FAA aeronautical contractions](https://www.faa.gov/air_traffic/publications/atpubs/cnt_html/chap2_section_2.html) for GS, TRK, GNSS and INS, and [FAA navigation guidance](https://www.faa.gov/air_traffic/publications/atpubs/AIM/aim0102.html) for EPE/EPU/ANP terminology. [Android Location](https://developer.android.com/reference/android/location/Location) defines the reported position, altitude, speed and accuracy fields. Renaming a display field does not change its calculation or confidence level.
