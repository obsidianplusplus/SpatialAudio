<div align="center">

<img src="src/main/resources/assets/spatialaudio/icon.png" width="96" alt="SpatialAudio 图标">

# SpatialAudio

**为 Minecraft Java 版带来 AirPods 空间音频与头部追踪**

HRTF 双耳渲染 · AirPods 头部追踪 · 射线追踪混响 · 遮挡与绕射 · 远距离回声 · 全部声音类别适配

[![构建](https://github.com/obsidianplusplus/SpatialAudio/actions/workflows/build.yml/badge.svg)](https://github.com/obsidianplusplus/SpatialAudio/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Loader](https://img.shields.io/badge/Fabric-0.19.5%2B-DBD0B4)
![Java](https://img.shields.io/badge/Java-25-E76F00)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

---

## 这是什么

SpatialAudio 是一个**纯客户端**的 Fabric 模组。戴上 AirPods（Pro / Max / 第 3、4 代）或其它支持头部追踪的耳机，转动你的头，游戏里的声音会像真实世界一样**固定在原地**：苦力怕在你左后方嘶嘶作响，向左转头它就移到你的正后方；唱片机在门外播放，声音会从门口传进来；走进石头洞穴，脚步声带着长长的回响。

它不只是“开个 HRTF”，而是一整套空间音频管线：

| 能力 | 说明 |
| --- | --- |
| 🎧 **HRTF 双耳渲染** | 强制以立体声 HRTF 模式输出，可选择 HRTF 数据集，还原上下、前后、左右的三维方位 |
| 🧭 **头部追踪** | macOS 上直接读取 AirPods 的 CoreMotion 姿态；也支持任意 OpenTrack 兼容追踪器（摄像头、手机、VR 头显……） |
| 🎯 **低延迟处理** | One Euro 自适应滤波 + 陀螺仪角速度预测 + 独立于帧率的 100 Hz 听者刷新 + 偏航漂移校正 |
| 🖥️ **屏幕锚定** | 音乐、界面音效固定在屏幕方向，转头时保持在屏幕前方（与 Apple 空间音频一致） |
| 🏛️ **环境混响** | 射线探测周围空间，用 Eyring 公式实时估算混响时间，材质决定音色（石头明亮、羊毛沉闷） |
| 🧱 **遮挡与绕射** | 墙体按材质与厚度让声音变闷；用 A* 在空气中寻路，声音会从门洞、窗户、拐角传来 |
| 🏔️ **远距离回声** | 山谷、峡谷中远处的岩壁会产生可感知的回声 |
| 🌧️ **空气吸收与介质** | 远处声音高频衰减（雨天更明显）；水下、岩浆、细雪中声音沉闷 |
| 🚀 **多普勒效应** | 可选：鞘翅高速飞过时的音高变化 |
| 🔊 **全部声音适配** | 音乐、唱片、天气、方块、敌对/友好生物、玩家、环境、语音、界面——每个类别都可单独设置 |

## 系统要求

- Minecraft Java 版 **26.3**
- [Fabric Loader](https://fabricmc.net/use/) **0.19.5** 或更新、[Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**（官方启动器自带）
- AirPods 头部追踪：**macOS 14 Sonoma** 或更新，AirPods (第 3/4 代)、AirPods Pro、AirPods Max 或支持头部追踪的 Beats
- 可选：[Mod Menu](https://modrinth.com/mod/modmenu)（在模组列表中打开设置）

> Windows / Linux 同样可以使用 HRTF 与全部声学功能，并可通过 OpenTrack 实现头部追踪。

## 安装

1. 安装 Fabric Loader 与 Fabric API。
2. 从 [Releases](https://github.com/obsidianplusplus/SpatialAudio/releases) 下载 `spatialaudio-x.y.z.jar`（Release 版内置预编译的 AirPods 助手），放入 `.minecraft/mods`。
3. 启动游戏。

## AirPods 快速上手（macOS）

1. 佩戴 AirPods，并在 macOS 中把它设为当前的声音输出设备。
2. **关闭系统对 Java 的空间音频**：打开 Minecraft 后，点击菜单栏的 AirPods 图标（或 控制中心 → 声音），把“空间音频”设为 **关闭**。本模组自己完成空间化与头部追踪，系统再处理一遍会导致双重空间化。
3. 首次启动时 macOS 会弹出：“SpatialAudio Head Tracker 想要访问你的运动与健身活动”，点击 **允许**。
4. 进入世界，正对屏幕按 **F6** 回中（Mac 键盘需按 `fn + F6`）。
5. 按 **F7** 打开设置界面，顶部的实时状态面板会显示“AirPods 追踪中”和实时姿态角。

详细说明见 Wiki：[macOS 与 AirPods 设置](wiki/macOS-AirPods.md)。

### 为什么需要一个“助手”程序？

Apple 规定：使用 `CMHeadphoneMotionManager` 读取耳机姿态的进程必须在 Info.plist 中声明 `NSMotionUsageDescription`，**否则系统会直接终止该进程**。Minecraft 运行在启动器拉起的 Java 进程里，无法添加这个声明，在游戏进程内调用会让游戏崩溃。

因此模组自带一个只有几百 KB 的小程序 **SpatialAudio Head Tracker.app**：它拥有完整的 Info.plist，通过 `open` 命令以独立应用的身份启动，读取 AirPods 姿态后经本机 UDP（127.0.0.1）发送给游戏。它没有界面、不在程序坞显示，游戏退出后自动结束。

- Release 版 jar 内含在 GitHub Actions macOS 环境中编译、签名并自检过的通用二进制（Apple Silicon + Intel）。
- 如果你自己在 Linux/Windows 上构建了 jar（不含预编译助手），模组会在检测到 Xcode 命令行工具时自动在本机编译助手（`xcode-select --install`）。

## 其它头部追踪器（OpenTrack）

在 [OpenTrack](https://github.com/opentrack/opentrack) 中把输出设为 **UDP over network**，目标 `127.0.0.1`，端口 `4242`，即可把任何追踪器（摄像头人脸追踪、手机陀螺仪、TrackIR、VR 头显等）接入本模组。详见 [OpenTrack 与其它追踪器](wiki/OpenTrack.md)。

## 按键与命令

| 按键（默认） | 功能 |
| --- | --- |
| `F6` | 头部追踪回中（正对屏幕时按下） |
| `F7` | 打开空间音频设置 |
| 未绑定 | 开关头部追踪 |
| 未绑定 | 开关调试叠加层 |

| 命令 | 功能 |
| --- | --- |
| `/spatialaudio` 或 `/spatialaudio status` | 显示追踪状态、头部姿态、HRTF 与声学环境 |
| `/spatialaudio recenter` | 回中 |
| `/spatialaudio restart` | 重启头部追踪与 AirPods 助手 |
| `/spatialaudio reload` | 从磁盘重新读取配置 |
| `/spatialaudio hud` | 开关调试叠加层 |
| `/spatialaudio hrtf` | 列出可用的 HRTF 数据集 |
| `/spatialaudio config` | 打开设置界面 |

## 配置

所有选项都可以在游戏内设置界面修改（`F7` 或 Mod Menu），也可以直接编辑 `.minecraft/config/spatialaudio.json`。完整说明见 [配置参考](wiki/Configuration.md)。

## 兼容性

- **Sound Physics Remastered**：两者都会修改声源的滤波与混响。检测到它时，本模组默认自动让出声学模拟，只保留 HRTF 与头部追踪（可在设置中改为同时启用）。
- **Simple Voice Chat** 等使用 Minecraft OpenAL 上下文的语音模组：语音同样会获得 HRTF 双耳渲染与头部追踪。
- 资源包与其它模组添加的声音：全部自动适配；世界中的立体声声音也会被强制三维定位。

更多信息见 [兼容性](wiki/Compatibility.md)。

## 文档（Wiki）

| 页面 | 内容 |
| --- | --- |
| [首页](wiki/Home.md) | Wiki 导航 |
| [安装与快速上手](wiki/Quick-Start.md) | 安装、首次使用、验证是否生效 |
| [macOS 与 AirPods 设置](wiki/macOS-AirPods.md) | 系统设置、权限、助手原理与排障 |
| [空间音频与 HRTF](wiki/Spatial-Audio-HRTF.md) | HRTF、OpenAL Soft 扩展、屏幕锚定、立体声定位 |
| [头部追踪原理](wiki/Head-Tracking.md) | 坐标系、回中、One Euro 滤波、延迟预测、漂移校正 |
| [声学模拟](wiki/Acoustics.md) | 混响、遮挡、绕射寻路、回声、空气吸收、介质、多普勒 |
| [声音类别适配](wiki/Sound-Categories.md) | 每个声音类别的处理方式 |
| [配置参考](wiki/Configuration.md) | 全部配置项、默认值与取值范围 |
| [OpenTrack 与其它追踪器](wiki/OpenTrack.md) | 接入方式与轴向约定 |
| [SAHT 协议](wiki/SAHT-Protocol.md) | 助手与模组之间的 UDP 协议 |
| [架构与源码导读](wiki/Architecture.md) | 模块划分、线程模型、注入点 |
| [构建与测试](wiki/Building-and-Testing.md) | 从源码构建、单元测试、客户端集成测试 |
| [兼容性](wiki/Compatibility.md) | 与其它模组、系统、设备的兼容情况 |
| [常见问题与排障](wiki/Troubleshooting.md) | 各状态的含义与解决办法 |
| [相关项目与参考文献](wiki/References.md) | 调研过的项目、论文与技术资料 |

## 从源码构建

```bash
git clone https://github.com/obsidianplusplus/SpatialAudio.git
cd SpatialAudio
./gradlew build            # 构建 jar 并运行单元测试（需要 JDK 25）
```

- 在 macOS 上构建时会自动编译 AirPods 助手（需要 Xcode 命令行工具），产物位于 `build/libs/`。
- `./gradlew runClient` 启动开发客户端；`./gradlew runClientGameTest` 运行客户端集成测试（Linux 可配合 `xvfb-run`）。
- 详见 [构建与测试](wiki/Building-and-Testing.md)。

## 致谢与参考

- [OpenAL Soft](https://github.com/kcat/openal-soft)（HRTF 渲染与 EFX 效果，随 LWJGL 内置于 Minecraft）
- [Sound Physics Remastered](https://github.com/henkelmax/sound-physics-remastered)（Minecraft 声学模拟的先行者）
- Apple CoreMotion `CMHeadphoneMotionManager` 文档
- Casiez 等，《1€ Filter》，CHI 2012
- Amanatides & Woo，《A Fast Voxel Traversal Algorithm for Ray Tracing》，1987
- Eyring，《Reverberation Time in "Dead" Rooms》，1930

完整列表见 [相关项目与参考文献](wiki/References.md)。

## 许可证

[MIT](LICENSE) © 2026 obsidianplusplus
