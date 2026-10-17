# SAHT 协议

SAHT（SpatialAudio Head Tracking）是 AirPods 头部追踪助手与模组之间的本机 UDP 协议。助手只向 `127.0.0.1:<端口>` 发送，模组只接收；双方没有握手。

同一个助手在 [Apple 空间音频](Apple-Spatial-Audio.md) 渲染方式下还会通过另一条本机 TCP 连接接收声场、报告渲染状态，那部分使用 [SAAB 协议](SAAB-Protocol.md)，与 SAHT 相互独立。

协议定义同时存在于两处，修改时必须保持一致：

- Java：`src/main/java/io/github/obsidianplusplus/spatialaudio/headtracking/SahtProtocol.java`
- Swift：`native/macos/SpatialAudioHeadTracker/main.swift`（`Saht` 与 `encodePacket`）

## 数据包布局

所有字段为**小端序**，单个数据包固定 **88 字节**：

| 偏移 | 类型 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | `u8[4]` | 魔数 | ASCII `SAHT`（`53 41 48 54`） |
| 4 | `u8` | 版本 | 当前为 `1` |
| 5 | `u8` | 类型 | `1` 姿态，`2` 状态 |
| 6 | `u16` | 标志位 | bit0 姿态有效，bit1 含角速度 |
| 8 | `i32` | 状态码 | 见下表 |
| 12 | `u32` | 序号 | 每发送一个包加 1，溢出回绕 |
| 16 | `f64` | 时间戳 | 姿态包为 `CMLogItem.timestamp`（系统启动以来的秒数）；状态包为系统运行时间 |
| 24 | `f64` | qx | 姿态四元数 x |
| 32 | `f64` | qy | 姿态四元数 y |
| 40 | `f64` | qz | 姿态四元数 z |
| 48 | `f64` | qw | 姿态四元数 w |
| 56 | `f64` | ωx | 陀螺仪角速度 x（弧度/秒） |
| 64 | `f64` | ωy | 陀螺仪角速度 y |
| 72 | `f64` | ωz | 陀螺仪角速度 z |
| 80 | `i32` | PID | 助手进程号（游戏退出时用于结束助手） |
| 84 | `i32` | 授权状态 | `CMAuthorizationStatus` 原始值：0 未决定、1 受限、2 拒绝、3 已授权 |

姿态四元数与角速度均位于 AirPods 坐标系（x 右、y 前、z 上），即 `CMDeviceMotion.attitude.quaternion` 与 `CMDeviceMotion.rotationRate` 的原始值。

## 状态码

| 值 | 名称 | 含义 |
| --- | --- | --- |
| 0 | `STARTING` | 启动中 |
| 1 | `WAITING_FOR_HEADPHONES` | 已授权但近 1 秒没有运动数据（耳机未连接或未佩戴） |
| 2 | `ACTIVE` | 正在输出姿态 |
| 3 | `NOT_AVAILABLE` | 系统不支持耳机运动数据 |
| 4 | `NOT_AUTHORIZED` | 用户拒绝或系统限制了“运动与健身”权限 |
| 5 | `ERROR` | CoreMotion 回调报告了错误 |
| 6 | `AWAITING_AUTHORIZATION` | 正在等待用户在弹窗中授权 |

## 发送时机

| 包类型 | 时机 |
| --- | --- |
| 姿态包 | 每次 CoreMotion 回调（AirPods 通常 25~50 Hz） |
| 状态包 | 每 0.5 秒一次，无论是否有姿态 |

## 接收端行为

- 长度不足 88 字节、魔数或版本不匹配的数据包会被忽略（48/52 字节的包按 OpenTrack 解析）；
- 只有类型为姿态、标志位 bit0 置位、四元数为有限值的包才会进入姿态处理；
- 3 秒内没有状态包视为助手状态未知；启动 15 秒后仍未收到任何包，显示“助手无响应”；
- 收到的 PID 用于游戏退出时结束助手（仅当该 PID 对应的可执行文件确为本助手时）。助手以 `--port 0` 启动时不发送 SAHT，模组改从 SAAB 的 HELLO 消息中取得 PID。

## 助手命令行参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--port <端口>` | `4242` | 目标端口。为 `0` 时不启动 CoreMotion、不读取耳机运动数据，也不发送 SAHT |
| `--parent-pid <PID>` | `0` | 游戏进程 PID；该进程退出后助手自动退出；0 表示不检查 |
| `--audio-port <端口>` | | SAAB 音频桥的 TCP 端口（`127.0.0.1`）；提供后助手连接模组并启动 Apple 空间音频渲染，见 [SAAB 协议](SAAB-Protocol.md#连接) |
| `--audio-token <令牌>` | | SAAB 连接令牌（32 个小写十六进制字符），必须与 `--audio-port` 一起提供 |
| `--self-test` | | 打印可用性、授权状态、数据包长度与助手的授权（entitlement）情况，校验 SAHT / SAAB 数据格式，并做 Apple 空间音频的离线渲染检查（见 [构建与测试](Building-and-Testing.md#自检中的-apple-渲染检查)）；不请求权限、不需要音频设备；输出 `selfTest=PASS` 或 `selfTest=FAIL`，失败时退出码非 0 |

### 模组何时传 `--port 0`

助手同时承担头部追踪与 Apple 空间音频渲染，模组按需要组合参数：

| 情况 | `--port` | `--audio-port` / `--audio-token` |
| --- | --- | --- |
| 模组内置渲染，数据源为“自动”或“AirPods” | UDP 接收器的端口 | 不传 |
| Apple 空间音频，数据源为“自动”或“AirPods” | UDP 接收器的端口 | 传 |
| Apple 空间音频，数据源为“OpenTrack”或“关闭” | `0` | 传 |
| 其它情况（不需要 AirPods 姿态，也不需要渲染声场） | 不启动助手 | |

选择了 Apple 空间音频但已 [回退](Apple-Spatial-Audio.md#自动回退) 时，按模组内置渲染的情况处理。“Apple 系统空间化立体声”方式下本模组停止自己的头部追踪，也不需要渲染声场，因此不启动助手。

模组启动助手的完整命令：

```bash
/usr/bin/open -n -g -j -a <助手路径>/SpatialAudioHeadTracker.app \
    --args --port <端口> --parent-pid <游戏 PID> \
    [--audio-port <音频桥端口> --audio-token <令牌>]
```

`-n` 总是启动新实例，`-g` 不抢前台，`-j` 隐藏启动。存在 `config/spatialaudio/helper/custom/SpatialAudioHeadTracker.app` 时，模组以同样的参数原样启动这个自备助手（见 [自备带授权的助手](Apple-Spatial-Audio.md#自备带授权的助手可选)）。
