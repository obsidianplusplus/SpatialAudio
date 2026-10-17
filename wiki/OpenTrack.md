# OpenTrack 与其它追踪器

除了 AirPods，本模组还能接收 [OpenTrack](https://github.com/opentrack/opentrack) 的“UDP over network”输出。OpenTrack 是开源的头部追踪中枢，支持大量输入设备，因此几乎任何头部追踪方案都能接入：

| 追踪方式 | 例子 |
| --- | --- |
| 摄像头人脸追踪 | OpenTrack 自带的 neuralnet tracker、AITrack |
| 红外点追踪 | TrackIR、DIY 红外帽（PointTracker） |
| 手机 | 各类把手机陀螺仪或前置摄像头数据发送到 OpenTrack 的应用 |
| VR 头显 | SteamVR tracker |
| 惯性传感器 | Arduino + IMU、Hatire |
| 其它耳机 | 任何能把姿态转成 OpenTrack UDP 的工具 |

## 设置步骤

1. 在模组设置中把“追踪数据源”设为 **自动** 或 **OpenTrack**（Windows / Linux 上“自动”即只接收 OpenTrack）。
2. 在 OpenTrack 中：
   - **Input**：选择你的追踪器；
   - **Output**：选择 **UDP over network**；
   - 点击 Output 旁的设置按钮，IP 填 `127.0.0.1`，端口填 `4242`（与模组的“UDP 端口”一致）。
3. 点击 OpenTrack 的 **Start**。
4. 游戏中状态应变为“OpenTrack 追踪中”，正对屏幕按 `F6` 回中。

OpenTrack 运行在另一台电脑上时，把 IP 改为游戏电脑的局域网地址，并在模组设置中开启“监听所有网卡”。

## 数据格式

每个数据包为 6 个小端序 `double`（共 48 字节）：

| 序号 | 字段 | 单位 |
| --- | --- | --- |
| 0 | X 平移 | 厘米 |
| 1 | Y 平移 | 厘米 |
| 2 | Z 平移 | 厘米 |
| 3 | 偏航 Yaw | 角度 |
| 4 | 俯仰 Pitch | 角度 |
| 5 | 横滚 Roll | 角度 |

部分发送端会在末尾追加 4 字节帧号（共 52 字节），同样可以识别。数值非有限或超出合理范围（角度超过 ±360°、平移超过 ±5 米）的数据包会被丢弃。

## 轴向约定

| | OpenTrack | 模组内部 |
| --- | --- | --- |
| 偏航 | 向右转头为正 | 向左转头为正（接收时取反） |
| 俯仰 | 抬头为正 | 抬头为正 |
| 横滚 | 依追踪器而定 | 向右歪头为正 |

不同追踪器、不同 OpenTrack 映射设置下方向可能相反。校准方法：

1. 打开调试叠加层（`/spatialaudio hud`）；
2. 向左转头，偏航应为正；抬头，俯仰应为正；向右歪头，横滚应为正；
3. 若某一轴方向相反，在设置中开启该轴的“反转”，或在 OpenTrack 的 Options → Output 中反转对应轴。

## 头部平移

OpenTrack 的 6DoF 数据包含头部平移。开启“使用头部平移”后，平移会叠加到听者位置（按摄像机朝向换算到世界坐标），实现靠近、偏头时的细微声场变化。默认关闭，因为大多数追踪器的平移噪声较大。

## 与 AirPods 同时使用

“自动”模式下 macOS 上同时接收 AirPods 与 OpenTrack，AirPods 优先：最近 0.5 秒内收到过 AirPods 数据时忽略 OpenTrack，避免两路数据互相干扰。

## 端口被占用

如果 4242 端口已被其它程序占用，模组会改用系统分配的临时端口（AirPods 助手会自动使用新端口），状态显示“端口被占用”，此时 OpenTrack 无法连接。在设置中把“UDP 端口”改为其它值（同时修改 OpenTrack 的输出端口），点击“重启头部追踪”即可。
