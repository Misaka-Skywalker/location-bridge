# 从助手启动定位桥

[English](app-launch.md) · [首页](../../README.zh-CN.md)

0.4.1 增加了从 Android 助手请求固定桥接命令的按钮。已在 OPPO K12s（Android 16、Termux 0.118.3）上验证状态命令、命令完成回执和认证状态回传；新按钮的 USB 启动与停止仍待实测。仍需按[开发说明](development.zh-CN.md)准备桥接运行环境、助手配对及 iPad 信任和配对。

## 一次性设置

1. 安装或更新助手和 Termux 侧桥接文件。安装程序会下载 `configure-app-launch.py`，但不会自动启用外部应用命令。
2. 在 Termux 中明确启用外部应用命令，然后重新加载设置：

   ```sh
   python ~/location-bridge/configure-app-launch.py --enable
   termux-reload-settings
   ```

3. 助手请求“在 Termux 环境中运行命令”权限时，请阅读系统提示，仅在信任助手时授予。该权限允许应用在 Termux 环境中执行命令并访问其中的文件；助手界面只发送固定的桥接命令。

该 Termux 属性也会允许其他已获得 `RUN_COMMAND` 权限的应用发起请求；修改属性本身不会向任何应用授予权限。如需撤销，请在 Android 应用设置中撤销助手的 Termux 命令权限，并将 `~/.termux/termux.properties` 中的 `allow-external-apps` 设为 `false`，然后运行 `termux-reload-settings`。

## 使用

在首页点击**连接 iPad**，默认通过 USB 启动系统融合定位源；点击**断开**请求停止。设置中提供 Wi-Fi 连接选项。“Termux 启动设置”中的**检查启动接口**会请求状态命令，**仅打开终端**只打开 Termux，不启动桥接。

助手会显式打开 Termux 以执行固定终端命令。如果 Android 仍阻止终端会话，请点击 Termux 通知并检查会话。请求等待中或命令完成**不代表桥接已连接**：请以经过认证的桥接状态判断连接是否建立。断线或清理中断后，请独立确认 iPad 已恢复正常位置状态。
