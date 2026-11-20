# 自适应定位内核

[English](adaptive-core.md) · [首页](../../README.zh-CN.md)

0.4.0 为 Android 助手加入实验性自适应内核。其算法和诊断不改变 iPad 传输方式：当前仍仅发送经纬度。已完成本机定位、GNSS 诊断与 Termux 认证快照的设备检查；真实移动全链路和长时间运行仍待验证，不适用于安全关键用途。

## 定位选源与请求

助手在可用时向 Android 平台 GPS、fused 和 network 提供器请求高精度定位，检查候选位置的时效和质量后选源。近期通过质量检查的 GNSS 地速越高，比较候选位置时对数据年龄的惩罚可越大；原始测量报告的 accuracy 保留不变。GPS 和 fused 的请求间隔按近期位置和速度在 500 毫秒与 1,000 毫秒间自适应切换，并设置滞回；network 仍请求 1,000 毫秒。这些是请求间隔，不保证 2 Hz 或任何特定实际频率；提供器、系统和电源管理均会影响结果。[Android LocationRequest 文档](https://developer.android.com/reference/android/location/LocationRequest.Builder)

## GNSS 诊断

助手默认请求 GNSS 连续跟踪，可在设置中关闭该请求。请求被接受不代表系统一定报告实际跟踪状态，报告值可能保持未知。关闭本助手的请求不会强制其他应用或固件退出连续跟踪。连续跟踪可能增加耗电，其实际行为取决于设备和系统。[Android 连续跟踪文档](https://developer.android.com/reference/android/location/GnssMeasurementRequest.Builder#setFullTracking(boolean))

平台提供数据时，诊断会汇总卫星状态、观测到的星座和频段、原始测量数量、自动增益控制（AGC）及天线能力。接收机页面的信号类型按星座和信号名称区分，不代表独立射频频段或天线通道数量；卫星数量已跨信号去重。这些仅是诊断信息；本项目不是 RTK，也不自行解算原始观测值，测量数量不能证明定位精度。[Android GNSS 测量](https://developer.android.com/reference/android/location/GnssMeasurementsEvent) · [Android GNSS 能力](https://developer.android.com/reference/android/location/GnssCapabilities)

## 运动传感器和气压

优先使用系统旋转矢量和线性加速度。Android 旋转矢量是融合姿态传感器，通常使用陀螺仪和磁场等输入；底层实现和可用传感器因设备而异。首选数据流缺失或注册失败时，可在满足质量与时效门控的条件下使用地磁旋转矢量，或从加速度计数据中减去时间匹配的重力样本。[Android 位置传感器](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position) · [Android 运动传感器](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)

启用惯性采集时，如有气压传感器则以 hPa 报告气压；气压不会替代几何高度。

## 0.5.0 持续惯导

系统 `fused` 提供器与 GPS、network 一起实际参与选源，使用的是 `LocationManager`，并非 Google Play 服务的融合定位 SDK。系统如何组合输入由手机实现决定；注册成功不代表某种无线电或传感器一定参与了该次定位。SYS 页显示注册状态，POS 页显示当前采用来源。[Android 融合提供器](https://developer.android.com/reference/android/location/LocationManager#FUSED_PROVIDER)

有效实测位置始终优先；全部不可用时，由 GNSS 初始化的 INS 可以持续接替，不设经过时间上限，也不再因模型误差超过 150 米而停止。APK 和 Termux 脚本需要同步更新。自 0.6.0 起，切换来源时发送序号继续递增，每个点保留原始采样时间。另一来源采样稍早、仍有效且通过筛选的定位点也可以接替。

INS 仍需近期、通过质量检查的 GNSS 位置与速度完成初始化。足够精确的近零地速可在静止时完成校准，无需运动航迹角。姿态和加速度仍须满足质量与时序检查：加速度中断超过 250 毫秒，或推算样本超过 500 毫秒未更新，均不能继续输出。无效姿态、数值错误、超限加速度或速度、超出支持纬度范围也会停止推算；不满足条件时不会伪造初始位置。

推算按当地 WGS84 坐标逐步更新，保留最后一次有效 GNSS 的磁差参考直到新参考到达，诊断中显示参考年龄；长距离移动后该参考可能失准。传感器降级时增加模型误差增长量，不再缩短时限。手机传感器偏差会累积，因此不限时不等于长时间保持准确。

INS 页显示独立惯导经纬度、模型误差、地速、距 GNSS 校准时间和上次校正差。校正差指**校准前的惯导位置减去同一测量时刻的新 GNSS 位置**，显示总距离和东西、南北分量，正数分别表示偏东、偏北。它保留至下次有效校准，并显示距上次校正时间；GNSS 本身也有误差，因此它不是独立测得的惯导误差。定位中断时无法获得新的实测偏差。

地速同时显示 km/h 与 kts；接口字段和计算仍使用 m/s。快照新增可选字段 `inertial.position` 与 `inertial.last_correction`：位置为 null 表示没有当前可用惯导解，校正差为 null 表示尚无记录。`maximum_horizon_s` 和传感器 `prediction_horizon_s` 为 null，`time_limit_enabled=false`。

离线回归覆盖一小时合成轨迹、误差增长、延迟 GNSS 校正、实测来源优先与过期运动数据拒绝。这些测试不能验证真实传感器漂移或手机移动中的持续运行表现。

基础功能不需要 root，助手不会调用 `su` 或修改厂商 HAL。新内核尚未在 Redmi K60 Pro 上实测，不能假定不同机型具有相同的传感器、精度或性能。
