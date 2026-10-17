# macOS 与 AirPods 设置

本页说明在 macOS 上使用 AirPods 头部追踪所需的全部设置，以及头部追踪助手的工作原理。

## 支持的设备与系统

| 项目 | 要求 |
| --- | --- |
| macOS | 14 Sonoma 或更新（`CMHeadphoneMotionManager` 自 macOS 14 起提供） |
| 芯片 | Apple Silicon 与 Intel 均可（助手为通用二进制） |
| 耳机 | AirPods（第 3、4 代）、AirPods Pro（全部世代）、AirPods Max、支持头部追踪的 Beats（如 Beats Fit Pro） |
| 连接 | 耳机需连接到这台 Mac，并设为当前声音输出设备 |

不带运动传感器的耳机（AirPods 第 1、2 代、普通有线耳机）同样能享受 HRTF 双耳渲染与声学模拟，只是没有头部追踪。

## 为什么要关闭系统空间音频

macOS 会为不支持空间音频的应用提供“空间化立体声”（Spatialize Stereo）：把普通立体声当作屏幕前的两只虚拟音箱，再叠加头部追踪。本模组已经用 HRTF 把每个声源渲染到准确的三维位置，并自行完成头部追踪。若系统再处理一遍：

- 声音会被“双重空间化”，方位感变得模糊、发闷；
- 系统的头部追踪与模组的头部追踪叠加，转头时声场会转动两倍。

**设置方法**：Minecraft 运行时，点击菜单栏中的 AirPods 图标（或打开 控制中心 → 声音），在“空间音频”中选择 **关闭**。macOS 会按应用记住这个设置，只需设置一次。

> 对 Java 进程关闭系统空间音频不会影响其它应用（音乐、视频）的空间音频。

## “运动与健身”权限

首次启动时 macOS 会弹出：

> “SpatialAudio Head Tracker”想要访问你的运动与健身活动。

请点击 **允许**。如果不小心点了“不允许”：

1. 打开 **系统设置 → 隐私与安全性 → 运动与健身**；
2. 打开 **SpatialAudio Head Tracker** 的开关；
3. 在游戏中执行 `/spatialaudio restart`，或在设置界面点击“重启头部追踪”。

## 头部追踪助手

### 为什么不在游戏进程里直接读取

Apple 文档对 `CMHeadphoneMotionManager` 有明确要求：应用的 Info.plist 必须包含 `NSMotionUsageDescription`，**否则开始读取运动数据时系统会直接终止该进程**。Minecraft 运行在启动器拉起的 `java` 进程中，它的“责任进程”（TCC 判定权限归属的进程）是启动器，模组无法为它添加这一声明。在游戏进程内调用 CoreMotion 会导致整个游戏崩溃。

### 解决方案

模组附带一个极小的独立应用 **SpatialAudioHeadTracker.app**：

| 特点 | 说明 |
| --- | --- |
| 有完整的 Info.plist | 声明了 `NSMotionUsageDescription`，并把同一份 Info.plist 嵌入可执行文件的 `__TEXT,__info_plist` 段 |
| 独立的责任进程 | 通过 `/usr/bin/open -n -g -j -a` 启动，由 LaunchServices 创建，系统以它的名义请求权限 |
| 无界面 | `LSUIElement = true`，不显示在程序坞，不抢焦点 |
| 只发不收 | 把姿态以 [SAHT 协议](SAHT-Protocol.md) 发送到 `127.0.0.1` 的游戏端口，不监听任何网络端口 |
| 自动退出 | 每 0.5 秒检查游戏进程是否存在，游戏退出后自行结束；游戏也会在退出时结束它 |
| ad-hoc 签名 | 满足 Apple Silicon 对可执行代码必须签名的要求；本机生成的文件不带隔离属性，Gatekeeper 不会拦截 |

### 助手从哪里来

1. **Release 版 jar（推荐）**：内含在 GitHub Actions 的 macOS 环境中编译（arm64 + x86_64 通用二进制）、签名并自检过的 .app。模组启动时把它解压到 `.minecraft/config/spatialaudio/helper/`，重新签名后启动；之后只有版本变化时才重新解压。
2. **自行构建的 jar**：如果构建时没有 macOS 环境，jar 里只有 Swift 源码。模组检测到 Xcode 命令行工具后会在本机编译（首次约 10~60 秒，状态显示“正在编译助手”）。没有命令行工具时，状态显示“缺少助手”，在终端执行 `xcode-select --install` 后重启游戏即可。

在设置界面点击 **“打开助手文件夹”** 可以查看助手文件。也可以手动运行它测试：

```bash
cd ~/Library/Application\ Support/minecraft/config/spatialaudio/helper
./SpatialAudioHeadTracker.app/Contents/MacOS/SpatialAudioHeadTracker --self-test
```

自检会输出耳机运动是否可用、授权状态与数据包长度，不会触发权限弹窗。

## 使用建议

- **回中**：正对屏幕、保持平时的坐姿按 `F6`。模组默认使用“完全回中”，适合笔记本略低头的姿势。
- **漂移**：AirPods 只有陀螺仪和加速度计，没有磁力计，偏航会缓慢漂移。模组默认开启“漂移校正”：当头部基本正对屏幕且静止超过 1 秒时，缓慢把参考方向拉回。若仍觉得偏，按一次 `F6` 即可。
- **延迟**：AirPods 的蓝牙音频延迟约 150~250 ms，姿态数据本身约 25~50 Hz。模组用陀螺仪角速度把姿态向前预测（默认 35 ms），可在“姿态预测”中调整；觉得转头急停时声场“过冲”，就把它调小。
- **摘下耳机**：数据中断后声场会在约 0.35 秒内平滑回到正前方；重新戴上时会自动回中（可在“连接时自动回中”中关闭）。

## 常见问题

| 现象 | 原因与解决 |
| --- | --- |
| 状态一直是“等待耳机” | 耳机未佩戴或不是当前输出设备；入耳检测关闭时也可能不发送数据 |
| 状态是“设备不支持” | 系统低于 macOS 14，或耳机不支持头部追踪 |
| 状态是“未授权” | 在 系统设置 → 隐私与安全性 → 运动与健身 中允许 SpatialAudio Head Tracker |
| 转头时声场转动过大 | 系统空间音频没有关闭（见上文） |
| 方向反了 | 打开调试叠加层观察姿态角，开启对应轴的“反转” |
| 助手无响应 | 可能被安全软件拦截，或 4242 端口被占用；在设置中更换 UDP 端口后重启追踪 |

更多问题见 [常见问题与排障](Troubleshooting.md)。
