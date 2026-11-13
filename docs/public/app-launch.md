# Start the bridge from the helper

[简体中文](app-launch.zh-CN.md) · [Home](../../README.md)

Version 0.4.1 adds buttons to request fixed bridge commands from the Android helper. The bridge runtime, helper pairing and iPad trust and pairing still need to be set up as described in the [development notes](development.md).

Validated on 2026-11-13 with OPPO K12s, Android 16 and Termux 0.118.3: the status command, command completion callback and authenticated status return worked. In the subsequent USB test with iPad Wi-Fi disabled, the user confirmed that tapping **Connect iPad** automatically established the connection and the iPad received location data, without using the desktop widget or entering a terminal command. The new **Disconnect** button was not exercised in this test; other phone models remain unverified.

## One-time setup

1. Install or update the helper and the Termux-side bridge files. The installer downloads `configure-app-launch.py`; it does not enable external app commands automatically.
2. In Termux, explicitly enable external app commands, then reload settings:

   ```sh
   python ~/location-bridge/configure-app-launch.py --enable
   termux-reload-settings
   ```

3. When the helper requests **Run commands in Termux environment**, read the system prompt and grant it only if you trust the helper. This permission allows the app to execute commands in the Termux environment and access its files. The helper's interface sends only fixed bridge commands.

The Termux property also permits other apps that already hold `RUN_COMMAND` to make requests; changing it does not grant that permission to any app. To undo access, revoke the helper's Termux command permission in Android app settings and set `allow-external-apps=false` in `~/.termux/termux.properties`, then run `termux-reload-settings`.

## Use

Tap **Connect iPad** on the home screen to start the system-fused source over USB; tap **Disconnect** to request a stop. Settings provides a Wi-Fi connection option. Under **Termux launch settings**, **Check launch interface** requests a status command, while **Open Termux only** opens the terminal without starting the bridge.

The helper explicitly opens Termux for its fixed terminal command. If Android still blocks the terminal session, tap the Termux notification and check the session. A pending request or completed command does **not** mean the bridge is connected: use the authenticated bridge status to determine whether a connection is established. On disconnect or interrupted cleanup, independently verify that the iPad has returned to its normal location state.
