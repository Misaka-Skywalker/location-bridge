# Development and operating notes

[简体中文](development.zh-CN.md) · [Home](../../README.md)

## Build and test

From the repository root, run `python3 scripts/run_tests.py` with Python 3 and a JDK. The runner exercises Python and standalone Java tests without an Android SDK or device. Hardware behavior and end-to-end location accuracy require separate testing.

The helper supports Android API 31 or newer. Install a JDK 17 or newer, Android SDK platform API 36 and build tools, then run:

```sh
python3 android/helper/build.py --sdk /path/to/android-sdk --build-tools 36.0.0
```

Alternatively set `ANDROID_HOME` or `ANDROID_SDK_ROOT`; `JAVA_HOME` selects the JDK. The output is `android/apks/location-bridge-source-0.3.0.apk`. The build creates a local signing key; retain it securely for upgrades and never commit or share it. Use `--unsigned` for CI builds that do not need an installable signed APK. SDKs, keys and APKs are not shipped in this source release.

## Runtime preparation

On a Mac with Python and pip, prepare the pinned Linux ARM64 guest image and hash-locked Python wheels with:

```sh
python3 scripts/prepare_runtime.py
```

This needs Internet access and produces an OCI archive, wheel bundle and checksum manifest under `android/repo/`. With already populated caches, `--offline` validates and uses those caches. These generated artifacts are excluded from the source release. The manifest checks transfer integrity; it is not a signed provenance statement, so transfer it and the artifacts over a trusted local channel.

The Termux installation scripts expect Python, curl, `proot-distro`, `sha256sum` and tar, plus a local forwarded server at `127.0.0.1:18765` serving the staged files and accepting their report requests. `android/repo/install-runtime.sh` verifies the manifest and installs the guest runtime; `install-service.sh` stages the worker; `configure-source.sh` starts local helper pairing. Before running `install-service.sh`, preserve the target configuration as described below. These scripts are building blocks, not a fully automatic fresh-device setup: arrange the local server and file transfer, configure USB or Wi-Fi access, and pair the iPad separately. Remote pairing is not fully bootstrapped by this source release. Review the scripts and their prerequisites before running them.

### Target configuration before service installation

On an existing installation with a target embedded in the old `bridge_service.py`, transfer `migrate-target.py` to Termux and run it **before** `install-service.sh` replaces the old service. It creates `~/location-bridge/bridge-config.json` with private permissions and does not overwrite an existing configuration. If migration reports no legacy target, configure it manually as below.

On a new installation, create `~/location-bridge/bridge-config.json` privately before starting the service. For example, run this in Termux and enter the target iPad UDID when prompted:

```sh
mkdir -p ~/location-bridge
umask 077
python -c 'import json, pathlib; p = pathlib.Path.home() / "location-bridge" / "bridge-config.json"; p.write_text(json.dumps({"udid": input("Target iPad UDID: ").strip()}) + "\n"); p.chmod(0o600)'
```

Keep this file private and do not commit it. Then run `install-service.sh`, pair the helper with `configure-source.sh`, and complete iPad trust and remote pairing separately. Pairing is not fully automated by this release.

## Data flow

Android location providers → helper selection and optional bounded inertial estimate → authenticated local helper interface → Termux service/worker → pymobiledevice3 tunnel and DVT → iPad simulated latitude/longitude. USB and Wi-Fi are separate transport paths. Helper status and additional measurements do not imply that the iPad receives those measurements. BLE reception has a diagnostic path only.

## Safe testing and reporting

Use a test device with Developer Mode and required trust/pairing configured. Confirm the selected device and transport before starting a test; stop the bridge afterwards and independently verify that normal location has returned. Disconnects may prevent cleanup. Hardware behavior, OS versions, permissions, background restrictions and device pairing can affect results.

Do not commit pairing records, signing keys, credentials, real coordinates, device identifiers, screenshots, private logs, or local configuration. When reporting an issue, provide a minimal redacted reproduction and OS/tool versions, and describe whether a test used simulated or measured positions.
