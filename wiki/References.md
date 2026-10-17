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
| Apple PHASE 框架与“个性化空间音频” | 系统级空间音频的做法；模组内置渲染方式在游戏内自行渲染，因此需要对 Java 关闭系统空间音频，避免双重处理；“Apple 空间音频”渲染方式则把双耳渲染交给 Apple（见下文 [Apple 空间音频](#apple-空间音频)） |
| [OpenTrack](https://github.com/opentrack/opentrack) | 开源头部追踪中转软件；本模组兼容其“UDP over network”输出（6 个小端 double），见 [OpenTrack 与其它追踪器](OpenTrack.md) |

## 渲染与音频引擎

| 资料 | 用途 |
| --- | --- |
| [OpenAL Soft](https://github.com/kcat/openal-soft)（随 LWJGL 3.4.3 内置于 Minecraft 26.3，版本 1.25.2） | HRTF 卷积渲染、EFX 效果；扩展 `ALC_SOFT_HRTF`、`ALC_SOFT_output_mode`、`ALC_SOFT_output_limiter`、`AL_SOFT_source_spatialize`、`AL_EXT_STEREO_ANGLES`、`AL_SOFT_deferred_updates`；`makemhr` 工具用于把 SOFA 数据转换为 `.mhr` |
| OpenAL Soft 扩展规范：[`ALC_SOFT_loopback`](https://openal-soft.org/openal-extensions/SOFT_loopback.txt)、[`ALC_SOFT_loopback_bformat`](https://openal-soft.org/openal-extensions/SOFT_loopback_bformat.txt) | “Apple 空间音频”方式下的回环设备：由应用调用 `alcRenderSamplesSOFT` 取得混音结果，而不是输出到系统音频设备；B-Format 扩展让回环设备输出 Ambisonics（本模组使用 ACN 顺序、SN3D 归一化）。1.25.2 的回环设备支持最高 7.1 与 B-Format（最高 4 阶），不支持 7.1.4；7.1 输出只有水平面，不使用中置与低音声道 |
| Creative Labs，《OpenAL Effects Extension Guide》（EFX） | EAX 混响、回声、低通滤波器、辅助发送槽、`AL_AIR_ABSORPTION_FACTOR` 的参数含义与取值范围 |
| [LWJGL 3](https://www.lwjgl.org/) | Java 侧的 OpenAL 绑定（`ALC10`、`SOFTHRTF`、`EXTEfx` 等） |
| AES69（SOFA 格式） | 公开 HRTF 数据库的通用格式 |
| [Steam Audio](https://valvesoftware.github.io/steam-audio/)、[Resonance Audio](https://resonance-audio.github.io/resonance-audio/)、Microsoft Project Acoustics | 游戏空间音频引擎的设计参考：遮挡与透射分离、按声源平滑参数、以首条可达路径的方向作为感知方向 |

## Apple 空间音频

“Apple 空间音频”与“Apple 系统空间化立体声”两种渲染方式（见 [Apple 空间音频渲染](Apple-Spatial-Audio.md)）依据的 Apple 资料：

| 资料 | 用途 |
| --- | --- |
| Apple 支持：[在 Mac 上控制空间音频与头部追踪（AirPods 使用手册）](https://support.apple.com/guide/airpods/control-spatial-audio-and-head-tracking-dev00eb7e0a3/web)、[控制空间音频与动态头部追踪（Beats 使用手册）](https://support.apple.com/guide/beats/control-spatial-audio-head-tracking-apple-dev7db73ea55/web) | Mac 上的空间音频与头部追踪需要 Apple 芯片与 macOS 12.3+；在菜单栏 AirPods 图标中按应用选择“关闭 / 固定 / 头部追踪”，非杜比全景声内容显示为“空间化立体声”；空间音频并非适用于所有 App 或内容；头部追踪以 Mac 为参照。决定了“Apple 系统空间化立体声”的设置方法与限制，也是另外两种方式要求关闭系统空间音频的依据 |
| Apple 支持：[Mac 辅助功能“音频”设置](https://support.apple.com/guide/mac-help/change-audio-settings-for-accessibility-unac611/mac) | “空间音频跟随头部移动”开关（默认开启） |
| Apple 支持：[个性化空间音频](https://support.apple.com/en-us/102596) | 个性化档案在 iPhone（iOS 16+，原深感摄像头）上建立，同步到 macOS 13+ 的 Apple 芯片 Mac |
| Apple 支持：[在 Mac 上使用游戏模式](https://support.apple.com/en-us/105118) | macOS 14+ 的 Apple 芯片 Mac 全屏玩游戏时，把蓝牙采样率提高一倍以降低 AirPods 延迟 |
| Apple Developer：[`AVAudioEnvironmentNode`](https://developer.apple.com/documentation/avfaudio/avaudioenvironmentnode)、[`AVAudio3DMixingSourceMode.ambienceBed`](https://developer.apple.com/documentation/avfaudio/avaudio3dmixingsourcemode/ambiencebed) | 助手的渲染核心。环境声床的输入声道作为远场声源分布在听者周围、锚定在全局空间，渲染只取决于听者朝向而与听者位置无关，各声道的方向由输入总线的声道布局给出；渲染算法使用 `auto` |
| Apple Developer：[`isListenerHeadTrackingEnabled`](https://developer.apple.com/documentation/avfaudio/avaudioenvironmentnode/islistenerheadtrackingenabled)、[Head Pose 授权](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.coremotion.head-pose) | Apple 自动头部追踪：macOS 15+，需要受限授权 `com.apple.developer.coremotion.head-pose` |
| Apple Developer：[个性化空间音频](https://developer.apple.com/documentation/phase/personalizing-spatial-audio-in-your-app) | 应用具备 `com.apple.developer.spatial-audio.profile-access` 授权（macOS 15+）时，系统自动按用户的个性化档案调整空间渲染 |
| WWDC19 Session 510：[What's New in AVAudioEngine](https://developer.apple.com/videos/play/wwdc2019/510/) | `AVAudioEnvironmentNode` 的多声道空间化支持基于声道的布局与最高 3 阶的高阶 Ambisonics，“3 阶 Ambisonics”声场格式的上限由此而来 |
| Apple 示例代码：[Generating spatial audio from a multichannel audio stream](https://developer.apple.com/documentation/audiotoolbox/generating-spatial-audio-from-a-multichannel-audio-stream)（从多声道音频流生成空间音频） | 用空间混音器音频单元（`AUSpatialMixer`）把 8 声道音频流渲染为双耳空间音频，输入可以来自游戏引擎的输出，示例以拉取模型取得输入。与本模组的做法相近：把多声道声床交给 Apple 渲染，并由输出端拉取数据 |
| Apple Developer：[`PHASEAmbientMixerDefinition`](https://developer.apple.com/documentation/phase/phaseambientmixerdefinition)、[`PHASEListener.automaticHeadTrackingFlags`](https://developer.apple.com/documentation/phase/phaselistener/automaticheadtrackingflags) | 评估过的另一种方案（见下文“本模组的取舍”） |

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

### Ambisonics

- Nachbar, C., Zotter, F., Deleflie, E., Sontacchi, A. "AmbiX – A Suggested Ambisonics Format." *Ambisonics Symposium*, Lexington, 2011. —— ACN 通道顺序与 SN3D 归一化（AmbiX 约定）。本模组交给 Apple 的声场、OpenAL Soft 的 `ALC_ACN_SOFT` / `ALC_SN3D_SOFT` 与 CoreAudio 的 `kAudioChannelLayoutTag_HOA_ACN_SN3D` 使用的都是这一约定。

## 本模组的取舍

| 问题 | 常见做法 | 本模组的做法与理由 |
| --- | --- | --- |
| 头部追踪数据从哪来 | 在 Java 进程内调用 CoreMotion | 系统要求调用进程声明 `NSMotionUsageDescription`，Java 进程无法满足，故使用独立的、签名的助手 `.app` 通过本机 UDP 传输 |
| 头部旋转如何作用于声音 | 旋转每个声源的位置 | 只旋转 OpenAL 听者朝向（在视角基上展开头部旋转），声源数量再多也只需一次写入；屏幕锚定的声音再单独做反向旋转。Apple 空间音频方式下 OpenAL 听者只跟随摄像机，改由助手旋转 Apple 引擎的听者 |
| 门洞后的声音 | 发射大量随机射线 | 小门洞的立体角太小，随机射线难以命中；改为在空气格子中做确定性加权 A*，结果稳定、可测试 |
| 混响时间 | 统计反射射线的能量衰减 | 由平均自由程与平均吸声系数用 Eyring 公式直接计算，射线数少也稳定，并按开阔度削弱 |
| 线程安全 | 在主线程直接调用 OpenAL | 所有 OpenAL 调用都在原版声音线程执行，与声源的创建、播放、销毁严格串行（唯一的例外是 Apple 空间音频方式下由音频桥泵线程调用 `alcRenderSamplesSOFT` 驱动回环设备混音，见 [线程模型](Architecture.md#线程模型)） |
| 声场如何交给 Apple | 每个声源在 Apple 引擎中作为独立的对象 | OpenAL Soft 回环设备把全部声源混成一个以摄像机为参照的 Ambisonics（或 7.1）声床，作为环境声床交给 `AVAudioEnvironmentNode`。原版声音引擎、其它模组的声音以及本模组的全部声学处理（EFX 混响与回声、遮挡滤波、绕射重定向、空气吸收、多普勒）原样保留，不必在 Apple 一侧重写；传输量与声源数量无关（3 阶固定 16 声道）。代价是方向精度受 Ambisonics 阶数限制 |
| 声场如何传给助手 | 模组按自己的时钟推送音频 | 本机 TCP 上的拉取模型（[SAAB 协议](SAAB-Protocol.md)）：助手按音频硬件的消耗请求帧数，模组收到请求才调用 `alcRenderSamplesSOFT`。回环设备本身没有时钟，由此跟随真实的音频硬件时钟，不会漂移，缓冲量稳定在目标附近；TCP 有序可靠，本机传输不必处理丢包与乱序。Apple 的多声道空间音频示例同样以拉取模型取得输入 |
| Apple 方式下头部姿态的来源 | 交给 Apple 自动头部追踪（`isListenerHeadTrackingEnabled`） | 默认使用本模组处理后的头部姿态（HEAD 消息）。Apple 自动头部追踪需要 macOS 15 与受限授权 `com.apple.developer.coremotion.head-pose`，个性化 HRTF 需要 `com.apple.developer.spatial-audio.profile-access`（同样自 macOS 15 起提供）；受限授权必须由付费开发者账号（Apple Developer Program / Developer ID）的描述文件批准，随附的 ad-hoc 签名助手无法携带——带上未经批准的受限授权会在启动时被系统终止。本模组的姿态在任何签名下都可用，并保留回中、滤波、预测与 OpenTrack；拥有开发者账号的用户可以 [自备带授权的助手](Apple-Spatial-Audio.md#自备带授权的助手可选) 改用 Apple 自动头部追踪 |
| 用哪个 Apple 渲染接口 | PHASE 或 `AUSpatialMixer` | `AVAudioEnvironmentNode`：环境声床模式直接接受基于声道的布局与最高 3 阶的 HOA（WWDC19 510），渲染算法 `auto`，Apple 自动头部追踪只需一个属性，且可以在 `AVAudioEngine` 的离线手动渲染模式中自检。PHASE 的环境混音器（`PHASEAmbientMixerDefinition`）按文档接受单声道、立体声或环绕声布局并忽略 LFE 声道，其自动头部追踪（`automaticHeadTrackingFlags`）同样要求 macOS 15。`AUSpatialMixer` 是更底层的 Audio Unit，需要自己配置流格式、声道布局与参数（Apple 的多声道示例即使用它），保留为将来的备选方案 |
| 为什么不交给 macOS 的系统空间化 | 输出普通音频，由 `AVSampleBufferAudioRenderer` 等媒体播放管线交给系统空间音频 | 以下是本模组的判断，不是 Apple 的说明：系统是否真正做了空间化，无法在没有耳机的 CI 环境中验证；媒体播放管线面向音视频播放，缓冲与延迟比实时游戏音频大；系统空间音频需要用户在 AirPods 菜单中按应用手动开启。只想使用系统效果的用户可以选择“Apple 系统空间化立体声”，它直接输出普通立体声，由用户在 macOS 中打开空间化 |
