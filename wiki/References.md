# 相关项目与参考文献

本页列出开发过程中调研过的同类项目、平台文档与学术资料，并说明本模组借鉴了什么、做法有何不同。

## Minecraft 中的声音相关项目

| 项目 | 做了什么 | 与本模组的关系 |
| --- | --- | --- |
| 原版“定向音频”选项（1.19 起） | 在 OpenAL Soft 中开启 HRTF 双耳渲染 | 本模组的“HRTF 双耳渲染：跟随原版”即沿用此开关；“强制开启”额外请求立体声 HRTF 输出模式、指定数据集并开启输出限幅 |
| [Sound Physics](https://github.com/sonicether/Sound-Physics)（sonicether，原版） | 首个用射线追踪驱动 OpenAL EFX 混响与遮挡的 Minecraft 模组 | 证明了“射线 + EFX”在 Minecraft 中可行 |
| [Sound Physics Remastered](https://github.com/henkelmax/sound-physics-remastered) | 上者的现代重写：多条反射射线、四个混响发送槽、方块材质反射率 | 功能上与本模组的声学部分重叠，因此默认自动让位（见 [兼容性](Compatibility.md)）。本模组的不同之处：用 Eyring 公式由统计量直接得出混响时间并驱动单个 EAX 混响槽；用确定性的 A* 空气路径寻找绕射路径并把声音重定向到拐角；单独的峡谷回声；声学计算在主线程按预算分摊，OpenAL 写入全部在声音线程平滑完成 |
| [Simple Voice Chat](https://github.com/henkelmax/simple-voice-chat) | 近距离语音，使用独立的 OpenAL 设备与上下文 | 不经过原版声音引擎，二者互不干扰 |
| [AmbientSounds](https://github.com/CreativeMD/AmbientSounds)、[Presence Footsteps](https://github.com/Sollace/Presence-Footsteps) | 添加环境声与脚步声 | 它们的声音经过原版声音引擎，自动获得本模组的全部处理，是“全部声音适配”的验证对象 |

## 头部追踪

| 资料 | 用途 |
| --- | --- |
| Apple 开发者文档：[`CMHeadphoneMotionManager`](https://developer.apple.com/documentation/coremotion/cmheadphonemotionmanager) | AirPods 姿态与角速度的唯一公开来源；macOS 14 起提供 |
| Apple 开发者文档：[`NSMotionUsageDescription`](https://developer.apple.com/documentation/bundleresources/information-property-list/nsmotionusagedescription) | 未声明此键的进程访问运动数据会被系统终止，这是必须使用独立助手 `.app` 的原因（见 [macOS 与 AirPods](macOS-AirPods.md)） |
| Apple PHASE 框架与“个性化空间音频” | 系统级空间音频的做法；本模组改为在游戏内自行渲染，因此需要对 Java 关闭系统空间音频，避免双重处理 |
| [OpenTrack](https://github.com/opentrack/opentrack) | 开源头部追踪中转软件；本模组兼容其“UDP over network”输出（6 个小端 double），见 [OpenTrack 与其它追踪器](OpenTrack.md) |

## 渲染与音频引擎

| 资料 | 用途 |
| --- | --- |
| [OpenAL Soft](https://github.com/kcat/openal-soft)（随 LWJGL 内置于 Minecraft） | HRTF 卷积渲染、EFX 效果；扩展 `ALC_SOFT_HRTF`、`ALC_SOFT_output_mode`、`ALC_SOFT_output_limiter`、`AL_SOFT_source_spatialize`、`AL_EXT_STEREO_ANGLES`、`AL_SOFT_deferred_updates`；`makemhr` 工具用于把 SOFA 数据转换为 `.mhr` |
| Creative Labs，《OpenAL Effects Extension Guide》（EFX） | EAX 混响、回声、低通滤波器、辅助发送槽、`AL_AIR_ABSORPTION_FACTOR` 的参数含义与取值范围 |
| [LWJGL 3](https://www.lwjgl.org/) | Java 侧的 OpenAL 绑定（`ALC10`、`SOFTHRTF`、`EXTEfx` 等） |
| AES69（SOFA 格式） | 公开 HRTF 数据库的通用格式 |
| [Steam Audio](https://valvesoftware.github.io/steam-audio/)、[Resonance Audio](https://resonance-audio.github.io/resonance-audio/)、Microsoft Project Acoustics | 游戏空间音频引擎的设计参考：遮挡与透射分离、按声源平滑参数、以首条可达路径的方向作为感知方向 |

## 论文与教材

### 空间听觉与 HRTF

- Blauert, J. *Spatial Hearing: The Psychophysics of Human Sound Localization*. MIT Press, 1997.
- Begault, D. R. *3-D Sound for Virtual Reality and Multimedia*. Academic Press, 1994.
- Wenzel, E. M., Arruda, M., Kistler, D. J., Wightman, F. L. "Localization using nonindividualized head-related transfer functions." *JASA* 94(1), 1993. —— 通用 HRTF 的前后混淆问题。
- Wightman, F. L., Kistler, D. J. "Resolution of front–back ambiguity in spatial hearing by listener and source movement." *JASA* 105(5), 1999. —— 头部转动可以消除前后混淆，是头部追踪的核心依据。
- Algazi, V. R., Duda, R. O., Thompson, D. M., Avendano, C. "The CIPIC HRTF Database." *IEEE WASPAA*, 2001.
- Gardner, B., Martin, K. "HRTF Measurements of a KEMAR Dummy-Head Microphone." MIT Media Lab, 1994.

### 头部追踪信号处理

- Casiez, G., Roussel, N., Vogel, D. "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems." *CHI*, 2012. —— 本模组把它推广到四元数（见 [头部追踪原理](Head-Tracking.md)）。
- Shoemake, K. "Animating Rotation with Quaternion Curves." *SIGGRAPH*, 1985. —— 球面线性插值。
- Brungart, D. S., Simpson, B. D., Kordik, A. J. "The detectability of headtracker latency in virtual audio displays." *ICAD*, 2005. —— 头部追踪延迟的可感知阈值，决定了姿态预测的意义。

### 室内声学与传播

- Sabine, W. C. *Collected Papers on Acoustics*. Harvard University Press, 1922.
- Eyring, C. F. "Reverberation Time in 'Dead' Rooms." *JASA* 1(2A), 1930. —— 本模组的混响时间公式。
- Kuttruff, H. *Room Acoustics*, 6th ed. CRC Press, 2016. —— 平均自由程 `4V/S`、吸声系数数据。
- ISO 9613-1:1993. *Acoustics — Attenuation of sound during propagation outdoors — Part 1: Calculation of the absorption of sound by the atmosphere.* —— 高频空气吸收随湿度变化，对应雨天的空气吸收增强。
- Tsingos, N., Funkhouser, T., Ngan, A., Carlbom, I. "Modeling acoustics in virtual environments using the uniform theory of diffraction." *SIGGRAPH*, 2001. —— 绕射路径与拐角处的衰减。
- Raghuvanshi, N., Snyder, J. "Parametric wave field coding for precomputed sound propagation." *SIGGRAPH*, 2014. —— 用首个到达路径的方向作为感知方向，对应本模组的“绕射重定向”。

### 几何算法

- Amanatides, J., Woo, A. "A Fast Voxel Traversal Algorithm for Ray Tracing." *Eurographics*, 1987. —— 体素射线遍历。
- Hart, P. E., Nilsson, N. J., Raphael, B. "A Formal Basis for the Heuristic Determination of Minimum Cost Paths." *IEEE TSSC* 4(2), 1968. —— A* 寻路；本模组使用加权启发（ε = 1.5）换取速度。
- González, Á. "Measurement of areas on a sphere using Fibonacci and latitude–longitude lattices." *Mathematical Geosciences* 42, 2010. —— 斐波那契球面均匀采样。

## 本模组的取舍

| 问题 | 常见做法 | 本模组的做法与理由 |
| --- | --- | --- |
| 头部追踪数据从哪来 | 在 Java 进程内调用 CoreMotion | 系统要求调用进程声明 `NSMotionUsageDescription`，Java 进程无法满足，故使用独立的、签名的助手 `.app` 通过本机 UDP 传输 |
| 头部旋转如何作用于声音 | 旋转每个声源的位置 | 只旋转 OpenAL 听者朝向（在视角基上展开头部旋转），声源数量再多也只需一次写入；屏幕锚定的声音再单独做反向旋转 |
| 门洞后的声音 | 发射大量随机射线 | 小门洞的立体角太小，随机射线难以命中；改为在空气格子中做确定性加权 A*，结果稳定、可测试 |
| 混响时间 | 统计反射射线的能量衰减 | 由平均自由程与平均吸声系数用 Eyring 公式直接计算，射线数少也稳定，并按开阔度削弱 |
| 线程安全 | 在主线程直接调用 OpenAL | 所有 OpenAL 调用都在原版声音线程执行，与声源的创建、播放、销毁严格串行 |
