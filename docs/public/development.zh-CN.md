# 开发与操作说明

[English](development.md) · [首页](../../README.zh-CN.md)

## 构建与测试

在仓库根目录使用 Python 3 和 JDK 运行 `python3 scripts/run_tests.py`。该命令运行 Python 和独立 Java 测试，无需 Android SDK 或设备；设备行为及端到端定位精度仍需单独验证。

助手支持 Android API 31 或更高版本。安装 JDK 17 或更新版本、Android SDK API 36 平台及构建工具，然后运行：

```sh
python3 android/helper/build.py --sdk /path/to/android-sdk --build-tools 36.0.0
```

也可设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`；`JAVA_HOME` 用于选择 JDK。输出为 `android/apks/location-bridge-source-0.3.0.apk`。构建会创建本地签名密钥；请安全保存以便后续更新，切勿提交或分享。CI 中无需可安装签名 APK 时可使用 `--unsigned`。源码发布不包含 SDK、密钥和 APK。

## 准备运行环境

在安装 Python 和 pip 的 Mac 上运行以下命令，准备固定版本的 Linux ARM64 客户环境镜像和带哈希锁定的 Python wheel：

```sh
python3 scripts/prepare_runtime.py
```

该步骤需要联网，在 `android/repo/` 下生成 OCI 归档、wheel 包和校验清单。缓存已准备好时可使用 `--offline` 校验并使用缓存。这些生成文件不随源码发布。校验清单只能检查传输完整性，并非签名来源证明；请通过可信本地通道传输清单和文件。

Termux 安装脚本需要 Python、curl、`proot-distro`、`sha256sum` 和 tar，还需要在 `127.0.0.1:18765` 提供转发的本地服务，以提供文件并接收报告请求。`android/repo/install-runtime.sh` 校验清单并安装客户环境；`install-service.sh` 部署工作进程；`configure-source.sh` 启动本地助手配对。运行 `install-service.sh` 前请按下文保留目标配置。这些脚本是基础组件，不能自动完成全新设备设置：请自行准备本地服务和文件传输、配置 USB 或 Wi-Fi，并单独配对 iPad。本次源码发布尚未完整实现远程配对的自动初始化。运行前请审阅脚本及其前提条件。

### 安装服务前配置目标设备

对于目标设备标识保存在旧版 `bridge_service.py` 中的安装，请将 `migrate-target.py` 传到 Termux，并在 `install-service.sh` 替换旧服务**之前**运行。脚本以私有权限创建 `~/location-bridge/bridge-config.json`，不会覆盖已有配置。如果提示未找到旧目标，请按下述方式手动配置。

新安装用户请在启动服务前私密创建 `~/location-bridge/bridge-config.json`。例如在 Termux 中运行以下命令，并按提示输入目标 iPad 的 UDID：

```sh
mkdir -p ~/location-bridge
umask 077
python -c 'import json, pathlib; p = pathlib.Path.home() / "location-bridge" / "bridge-config.json"; p.write_text(json.dumps({"udid": input("Target iPad UDID: ").strip()}) + "\n"); p.chmod(0o600)'
```

请保密此文件，不要提交到仓库。然后运行 `install-service.sh`，使用 `configure-source.sh` 配对助手，并单独完成 iPad 信任和远程配对。本次发布尚未完全自动化配对。

## 数据流

Android 定位提供器 → 助手选源和可选的有时限惯性推算 → 经认证的本地助手接口 → Termux 服务／工作进程 → pymobiledevice3 隧道和 DVT → iPad 模拟经纬度。USB 和 Wi-Fi 是不同的传输通道。助手的状态和其他测量值不代表 iPad 已接收这些数据；BLE 接收目前仅用于诊断。

## 测试与反馈

使用启用了开发者模式并完成信任／配对的测试设备。启动前确认目标设备和传输方式；测试结束后停止桥接，并独立确认正常位置已恢复。断线可能导致清除失败。系统版本、权限、后台限制和设备配对均可能影响结果。

请勿提交配对记录、签名密钥、凭据、真实坐标、设备标识、截图、私人日志或本地配置。反馈问题时请提供经过脱敏的最小复现步骤、系统／工具版本，并说明位置来自模拟还是真实测量。
