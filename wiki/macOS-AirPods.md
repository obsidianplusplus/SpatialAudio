# macOS 与 AirPods 设置

本页说明在 macOS 上使用 AirPods 头部追踪所需的全部设置、三种渲染方式对系统空间音频的不同要求，以及 macOS 助手的工作原理。

## 支持的设备与系统

| 项目 | 要求 |
| --- | --- |
| macOS | 14 Sonoma 或更新（`CMHeadphoneMotionManager` 自 macOS 14 起提供）；“Apple 空间音频”渲染方式同样需要 macOS 14+ |
| 芯片（模组内置渲染 + 本模组的 AirPods 头部追踪） | 助手为通用二进制（Apple 芯片 + Intel）；在 Intel Mac 上能否读取 AirPods 姿态，取决于系统是否提供耳机运动数据（不提供时状态显示“设备不支持”） |
| 芯片（Apple 的空间音频） | Apple 说明 Mac 上的空间音频与头部追踪需要 Apple 芯片：“Apple 系统空间化立体声”只能在 Apple 芯片的 Mac（macOS 12.3+）上使用，“Apple 空间音频”渲染方式也推荐 Apple 芯片 |
| 耳机 | AirPods（第 3、4 代）、AirPods Pro（全部世代）、AirPods Max、支持头部追踪的 Beats（如 Beats Fit Pro） |
| 连接 | 耳机需连接到这台 Mac，并设为当前声音输出设备 |

不带运动传感器的耳机（AirPods 第 1、2 代、普通有线耳机）同样能享受 HRTF 双耳渲染与声学模拟，只是没有头部追踪。

## 为什么要关闭系统空间音频

macOS 会为不支持空间音频的应用提供“空间化立体声”（Spatialize Stereo）：把普通立体声当作屏幕前的两只虚拟音箱，再叠加头部追踪。在默认的 **模组内置** 渲染方式下，本模组已经用 HRTF 把每个声源渲染到准确的三维位置，并自行完成头部追踪。若系统再处理一遍：

- 声音会被“双重空间化”，方位感变得模糊、发闷；
- 系统的头部追踪与模组的头部追踪叠加，转头时声场会转动两倍。

**设置方法**：Minecraft 运行时，点击菜单栏中的 AirPods 图标（或打开 控制中心 → 声音），在“空间音频”中选择 **关闭**。macOS 会按应用记住这个设置，只需设置一次。

> 对 Java 进程关闭系统空间音频不会影响其它应用（音乐、视频）的空间音频。

### 不同渲染方式的系统设置

设置界面“通用”分组中的 **空间音频渲染方式** 决定了系统空间音频应该怎样设置（菜单栏 AirPods 图标；若菜单栏中没有，打开 **系统设置 → 菜单栏**，在“菜单栏控制”中选择“声音”）：

| 渲染方式 | 系统空间音频 | 原因 |
| --- | --- | --- |
| **模组内置**（默认） | Java：**关闭** | 见上文：避免双重空间化，避免转头时声场转动两倍 |
| **Apple 空间音频** | 若列出了 **SpatialAudio Head Tracker**：**关闭** | 助手输出的已经是 Apple 渲染好的双耳声音，系统不应再处理一遍。Minecraft 本身不再直接发声，Java 的设置此时不起作用；保持“关闭”即可，自动回退到模组内置渲染时仍然需要它 |
| **Apple 系统空间化立体声** | Java：**头部追踪**（或“固定”） | 本模组只输出普通立体声并停止自己的头部追踪，空间化与头部追踪全部交给 macOS。同时确认 **系统设置 → 辅助功能 → 音频 →“空间音频跟随头部移动”** 处于开启状态（默认开启）。本模组无法检测或代为打开这个设置 |

“Apple 系统空间化立体声”只有左右两个声道，游戏中的前后与高度方位会丢失。三种方式的原理与取舍见 [Apple 空间音频渲染](Apple-Spatial-Audio.md)。

## “运动与健身”权限

首次启动时 macOS 会弹出：

> “SpatialAudio Head Tracker”想要访问你的运动与健身活动。

请点击 **允许**。如果不小心点了“不允许”：

1. 打开 **系统设置 → 隐私与安全性 → 运动与健身**；
2. 打开 **SpatialAudio Head Tracker** 的开关；
3. 在游戏中执行 `/spatialaudio restart`，或在设置界面点击“重启追踪与渲染”。

助手只在追踪数据源为“自动”或“AirPods”时通过 CoreMotion 读取 AirPods 姿态；数据源为“OpenTrack”或“关闭”时它不启动 CoreMotion。“Apple 系统空间化立体声”方式下本模组停止自己的头部追踪，不读取 AirPods 姿态。

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
| 不监听端口 | 把姿态以 [SAHT 协议](SAHT-Protocol.md) 发送到 `127.0.0.1` 的游戏端口；Apple 空间音频方式下主动连接模组在 `127.0.0.1` 上监听的 [SAAB](SAAB-Protocol.md) 端口。助手自己不监听任何网络端口 |
| 自动退出 | 每 0.5 秒检查游戏进程是否存在，游戏退出后自行结束；游戏也会在退出时结束它 |
| ad-hoc 签名 | 满足 Apple Silicon 对可执行代码必须签名的要求；本机生成的文件不带隔离属性，Gatekeeper 不会拦截。ad-hoc 签名的程序无法携带 Apple 的受限授权（见下文“自备助手”） |

### Apple 空间音频方式下的助手

渲染方式为 **Apple 空间音频** 时，同一个助手还负责播放声音：

- 模组把 OpenAL 渲染出的多声道声场（默认 3 阶 Ambisonics）经本机 TCP（[SAAB 协议](SAAB-Protocol.md)，以每次启动助手时随机生成的令牌认证）交给助手，助手用 `AVAudioEngine` 与 `AVAudioEnvironmentNode` 交给 Apple 的空间音频引擎渲染，再输出到耳机；
- 头部转动由助手作用于 Apple 引擎的听者朝向：默认使用本模组处理后的头部姿态（AirPods 或 OpenTrack），只有具备授权的自备助手在 macOS 15+ 上才能改用 Apple 自动头部追踪；
- 即使追踪数据源是“OpenTrack”或“关闭”，助手也会启动，只是不读取 AirPods 姿态（以 `--port 0` 启动）；
- 助手无法启动、长时间没有连接或渲染引擎持续出错时，模组自动回退到内置渲染，见 [自动回退](Apple-Spatial-Audio.md#自动回退)。

渲染方式为 **Apple 系统空间化立体声** 时不需要助手，本模组的头部追踪也会停止。

### 助手从哪里来

1. **Release 版 jar（推荐）**：内含在 GitHub Actions 的 macOS 环境中编译（arm64 + x86_64 通用二进制）、签名并自检过的 .app。模组启动时把它解压到 `.minecraft/config/spatialaudio/helper/`，重新签名后启动；之后只有版本变化时才重新解压。
2. **自行构建的 jar**：如果构建时没有 macOS 环境，jar 里只有 Swift 源码。模组检测到 Xcode 命令行工具后会在本机编译（首次约 10~60 秒，状态显示“正在编译助手”）。没有命令行工具时，状态显示“缺少助手”，在终端执行 `xcode-select --install` 后重启游戏即可。

**自备助手（可选）**：在以上两种方式之前，模组会先检查 `.minecraft/config/spatialaudio/helper/custom/SpatialAudioHeadTracker.app`。它存在时，模组原样启动它，不解压覆盖，也不重新签名。随附的助手是 ad-hoc 签名，无法携带 Apple 自动头部追踪（`com.apple.developer.coremotion.head-pose`）与个性化空间音频（`com.apple.developer.spatial-audio.profile-access`）这两项受限授权；拥有付费 Apple 开发者账号的用户可以自行构建、签名带授权的版本放到这里。设置界面“Apple 空间音频”分组中的 **“打开自备助手文件夹”** 按钮会创建并打开这个文件夹，构建步骤见 [Apple 空间音频渲染](Apple-Spatial-Audio.md#自备带授权的助手可选)。

在设置界面点击 **“打开助手文件夹”** 可以查看助手文件。也可以手动运行它测试：

```bash
cd ~/Library/Application\ Support/minecraft/config/spatialaudio/helper
./SpatialAudioHeadTracker.app/Contents/MacOS/SpatialAudioHeadTracker --self-test
```

自检会输出耳机运动是否可用、授权状态与数据包长度，并以离线渲染检查 Apple 空间音频声场的方向，最后输出 `selfTest=PASS` 或 `selfTest=FAIL`；不会触发权限弹窗，也不需要音频设备。

## 使用建议

- **回中**：正对屏幕、保持平时的坐姿按 `F6`。模组默认使用“完全回中”，适合笔记本略低头的姿势。
- **漂移**：AirPods 只有陀螺仪和加速度计，没有磁力计，偏航会缓慢漂移。模组默认开启“漂移校正”：当头部基本正对屏幕且静止超过 1 秒时，缓慢把参考方向拉回。若仍觉得偏，按一次 `F6` 即可。
- **延迟**：AirPods 的蓝牙音频延迟约 150~250 ms，姿态数据本身约 25~50 Hz。模组用陀螺仪角速度把姿态向前预测（默认 35 ms），可在“姿态预测”中调整；觉得转头急停时声场“过冲”，就把它调小。Apple 空间音频方式下声音还会多出“Apple 缓冲时长”（默认 30 毫秒）的延迟，头部姿态不经过这个缓冲，转头响应不受影响。
- **摘下耳机**：数据中断后声场会在约 0.35 秒内平滑回到正前方；重新戴上时会自动回中（可在“连接时自动回中”中关闭）。

## 常见问题

| 现象 | 原因与解决 |
| --- | --- |
| 状态一直是“等待耳机” | 耳机未佩戴或不是当前输出设备；入耳检测关闭时也可能不发送数据 |
| 状态是“设备不支持” | 系统低于 macOS 14，或耳机不支持头部追踪 |
| 状态是“未授权” | 在 系统设置 → 隐私与安全性 → 运动与健身 中允许 SpatialAudio Head Tracker |
| 转头时声场转动过大 | 系统空间音频没有关闭：模组内置渲染时检查 Java，Apple 空间音频时检查 SpatialAudio Head Tracker（见上文） |
| 选择了 Apple 空间音频，状态显示“已回退到模组内置渲染” | 状态面板会附带原因；排除问题后点击“重启追踪与渲染”或执行 `/spatialaudio restart`，见 [自动回退](Apple-Spatial-Audio.md#自动回退) |
| 选择了 Apple 系统空间化立体声，但声音没有空间感 | 在菜单栏 AirPods 图标中对 Java 选择“头部追踪”（见上文）；这种方式只有左右方位 |
| 方向反了 | 打开调试叠加层观察姿态角，开启对应轴的“反转” |
| 助手无响应 | 可能被安全软件拦截，或 4242 端口被占用；在设置中更换 UDP 端口后重启追踪 |

更多问题见 [常见问题与排障](Troubleshooting.md)。
