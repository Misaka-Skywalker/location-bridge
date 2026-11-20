# Location Bridge

[English](README.md)

<img src="docs/assets/icon.svg" width="96" height="96" alt="Location Bridge 备用导航显示器风格图标">

Location Bridge 是一个实验性的 Android 到 iPad 定位桥。Android 助手采集并检查定位样本；Termux 中的 Python 进程通过 `pymobiledevice3` 和 iPad 开发者位置模拟服务发送经纬度。助手还以仪表风格界面显示定位和运动信息。

**仅供研究和测试，不可用于安全关键决策。** 模拟位置会影响 iPad 上的其他应用。断开连接后清除命令可能无法送达；测试结束后请确认 iPad 已恢复正常位置状态。

0.4.0 加入[实验性自适应定位内核](docs/public/adaptive-core.zh-CN.md)；已完成本机定位、GNSS 诊断与 Termux 认证快照的设备检查；真实移动全链路仍待验证。

0.4.1 增加[助手调用 Termux 启动控制](docs/public/app-launch.zh-CN.md)；已在 OPPO K12s 上确认 App 内启动 USB 连接后，iPad 能收到位置。

0.4.2 加入不含文字的备用 ND 风格矢量应用图标，并提供主题图标使用的单色版本。

0.5.0 增加 km/h 与 kts 双单位地速、持续惯导接替，以及惯导位置与校正差显示。选源优先级和有效条件详见[自适应内核说明](docs/public/adaptive-core.zh-CN.md)。

0.5.1 统一英文仪表缩写、POS／INS 监控页和操作键。`INS EPU` 表示模型估计的位置不确定度，尚未标定置信水平；`LAST POS Δ` 表示同一时刻、校准前的 INS−GNSS 位置差，两者含义不同。 [界面术语](docs/public/display-terms.zh-CN.md)

0.5.2 将仪表页签固定在底部，滚动和横竖屏切换时都能继续查看当前页面。惯导在最高 200 Hz 的输入下保留两秒运动记录，供延迟到达的 GNSS 定位点回放校准。较新的桥接报告优先于先前的启动命令，报告过期后仍按最后已知状态提示。

0.5.3 的惯导页用五行固定显示位置与校准数据。高度字段标明 WGS84 椭球高，同时显示米和英尺。配对完成后，可在**设置 → Termux 配对信息**中查看指纹。

0.6.0 的首页连接键在启动时自动选择 USB 或 Wi-Fi。使用手机热点时，让已配对的 iPad 加入该热点即可。惯导页用一条状态和四行固定读数显示位置与校准信息。

## 当前状态与限制

- 已观察到 Wi-Fi 和 USB 下的固定点及模拟轨迹；测试过的 USB 通道不依赖局域网。真实移动场景下 Android 到 iPad 的持续全链路尚未验证，长时间运行和锁屏场景也未验证。
- 当前 iPad DVT 模拟调用**仅发送经纬度**。高度、精度、地速、真／磁航迹和磁差仅用于助手显示或元数据，不会通过该通道传给 iPad。
- 实验性 GNSS 辅助惯性推算不再按经过时间终止；仅在所有实测位置均不可用时接替，仍需初始 GNSS 校准和连续有效的运动数据。模型误差随时间增长，不再因超过精度阈值自动退出；模型值不等于实测漂移或完整性保证。
- 融合模式优先使用手机有效的实测位置（GNSS、系统融合、网络）。这些位置都不可用时，可用且带定位时间的蓝牙位置优先于惯导。手动拒选和静止 TXT 同样会影响实际发送。无法与该点匹配的蓝牙精度保留为未知。
- 助手可在 **设置 → Language / 语言** 中切换简体中文和 English；选择会保存，切换时不停止服务。
- `GS` 为地速，`ALT` 为高度，`TRK TRUE`／`TRK MAG` 为相对真北／磁北的运动航迹，`MAG VAR` 为磁差。航迹表示运动方向，不等于设备朝向。Android 高度是 WGS84 椭球高，不是气压高度或平均海平面高度。

## 仓库与开发

- `android/helper/`：原生 Android 定位助手及 Java 测试（Android 12 / API 31 或更高）。
- `android/repo/`：Termux 服务、定位源选择、配对和 Python 工作进程。
- `tests/`：Python 单元测试；`scripts/`：开发、运行环境准备及发布检查。
- [开发和操作说明](docs/public/development.zh-CN.md) · [贡献指南](CONTRIBUTING.md) · [第三方许可说明](THIRD_PARTY_NOTICES.md)

使用 Python 3 和 JDK 运行离线单元测试：

```sh
python3 scripts/run_tests.py
```

Android 构建需要本地 Android SDK、构建工具和 JDK，详见开发说明。本源码发布不包含预编译 APK、SDK、wheel、容器镜像或设备配对记录。可在本地准备运行环境，但设备配对和安装仍需手动设置；详见开发说明。

## 许可

版权所有 2026 Location Bridge contributors。项目原创贡献采用 [GNU General Public License v3.0 or later](LICENSE)；依赖遵循各自许可，尤其是 Python 工作进程导入的 `pymobiledevice3` 采用 GPL-3.0-or-later。分发组合运行环境前请阅读[第三方许可说明](THIRD_PARTY_NOTICES.md)。
