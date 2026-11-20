# 界面术语

[English](display-terms.md) · [首页](../../README.zh-CN.md)

英文界面采用简洁仪表标签，页面、状态和操作键使用大写；权限说明保留完整句子。这是一套显示约定，并非对某型飞机系统的复刻。

| 标签 | 本应用中的含义 |
| --- | --- |
| POS / LAT / LON | 位置／纬度／经度。 |
| GS | 地速，同时显示 km/h 和 kts，不是空速。 |
| TRK TRUE / TRK MAG | 相对真北／磁北的运动方向，不是飞机或手机的机头朝向。 |
| MAG VAR (EST) | Android 磁场模型估计的磁差；真航迹转磁航迹的计算以东偏为正。 |
| WGS84 ELLIP ALT | 相对 WGS84 椭球面的高度，同时显示米和英尺。 |
| H ACC / V ACC / GS ACC | 来源报告的水平、垂直和地速精度，不改称为保护级。 |
| INS | 由 GNSS 初始化的水平惯性推算。 |
| INS EPU | Estimated Position Uncertainty，当前惯导模型估计的位置不确定度；置信水平尚未标定，不等同于已验证的 95% 包含界限、ANP、HFOM 或保护级输出。 |
| LAST POS Δ | 上次校准前，惯导位置减去同一时刻 GNSS 位置的差；其中也包含 GNSS 本身的不确定性。 |
| ΔE (+E) / ΔN (+N) | 该位置差的东西／南北分量，正值分别表示偏东／偏北。 |
| GNSS UPD AGE / UPDATE AGE | 距相应 GNSS 校准点、位置样本或校正记录的时间，具体依字段而定。 |
| ALIGN WAIT / ALIGNED / IN USE | 等待有效 GNSS 校准点／已初始化推算／惯导已被选作输出。ALIGNED 不表示当前一定使用惯导输出。 |
| ON / OFF | 功能已启用／停用，不表示硬件适航或完好性。 |
| UNKNOWN / UNAVAIL / NO DATA | 状态未知／资源不可用／缺少测量。读数暂缺时，在字段原位显示 `---`。 |
| ATT / ACCEL | 姿态／加速度，ACCEL 与表示精度的 ACC 分开。 |
| SV USED / SV VISIBLE | 参与 GNSS 解算／已观测卫星数，跨信号去重。 |
| FULL TRK REQ / HW CONFIRMED | GNSS 连续跟踪请求／硬件是否报告实际连续跟踪；请求被接受不等于实际运行已确认。 |
| RX / TX | 接收／发送；LAST TX 指桥接进程报告的上次已应用来源，不只是手机当前选源。 |
| BLE / RSSI / MTU | 低功耗蓝牙／接收信号强度指示／最大传输单元，保留协议原意。 |
| DESELECT / RESELECT | 拒选／恢复外部诊断定位的使用资格，同时保持蓝牙连接。 |
| LSK | 行选键，即操作文字旁的侧键。 |

**POS START / POS STOP** 控制手机本机定位采集；**ENABLE INS / DISABLE INS** 控制惯导功能；**CONNECT / DISCONNECT** 控制 iPad 桥接。按键显示将执行的动作，当前状态另行显示。**SETUP** 打开设置。

GS、TRK、GNSS、INS 等用词参考 [FAA 航空缩写](https://www.faa.gov/air_traffic/publications/atpubs/cnt_html/chap2_section_2.html)，EPE／EPU／ANP 的术语参考 [FAA 导航资料](https://www.faa.gov/air_traffic/publications/atpubs/AIM/aim0102.html)。来源报告的位置、高度、速度与精度遵循 [Android Location](https://developer.android.com/reference/android/location/Location) 定义。改名不会改变计算方法或置信水平。
