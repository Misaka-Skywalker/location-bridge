# Third-party notices

Location Bridge's original contributions are offered under GPL-3.0-or-later. Third-party projects retain their own copyrights and license terms. This document identifies significant dependencies, not a complete inventory of every transitive dependency or a substitute for the notices required when distributing binaries.

| Component | Role | License and source |
| --- | --- | --- |
| [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) | Python worker imports its iPad communication and DVT interfaces; the historical ARM64 runtime pins 11.52.1. | The 11.52.1 wheel metadata identifies **GPL-3.0-or-later**; see its included license and [upstream LICENSE](https://github.com/doronz88/pymobiledevice3/blob/master/LICENSE). |
| [Android SDK and build tools](https://developer.android.com/studio/terms) | Local helper build and Android platform APIs. | Subject to the applicable SDK terms and licenses supplied with the SDK; obtained separately. |
| [Termux](https://github.com/termux/termux-app) and [Termux:API](https://github.com/termux/termux-api) | Android runtime and device integration used by historical setup scripts. | Each component and its packages have their own licenses; consult the corresponding upstream distributions. |
| [proot-distro](https://github.com/termux/proot-distro), Linux image and Python packages | Historical isolated ARM64 runtime. | Each has separate license terms and may have additional dependencies. The pinned Python package list is `android/repo/wheels/requirements.txt`. |

The public source release excludes the historical APKs, wheel files, Debian packages, SDK, and container images. If redistributing a combined runtime, APK, container or dependencies, identify the actual versions and bundled components, preserve their applicable licenses and notices, and satisfy any source-distribution and other obligations. The project uses GPL-3.0-or-later to align its original code with the imported `pymobiledevice3` dependency. This does not replace other dependencies' license terms or remove applicable distribution obligations.

The project is independent of Apple, Google and the third-party projects listed above; their names are used solely to identify compatibility and dependencies.
