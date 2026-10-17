# 兼容性

## 游戏与加载器

| 项目 | 支持情况 |
| --- | --- |
| Minecraft | Java 版 26.3（`~26.3`，含 26.3.x 小版本） |
| 模组加载器 | Fabric Loader 0.19.5+ |
| 前置 | Fabric API |
| 运行环境 | 纯客户端；可加入任何原版或模组服务器，服务器无需安装 |
| 渲染后端 | OpenGL 与 Vulkan 均可（本模组不涉及渲染） |

## 操作系统

| 系统 | HRTF 与声学 | AirPods 头部追踪 | OpenTrack 头部追踪 | Apple 空间音频渲染 | Apple 系统空间化立体声 |
| --- | --- | --- | --- | --- | --- |
| macOS 14 Sonoma 及以上（Apple 芯片） | ✅ | ✅ | ✅ | ✅ | ✅ |
| macOS 14 Sonoma 及以上（Intel） | ✅ | ⚠️ 助手为通用二进制，能否读取取决于系统是否提供耳机运动数据 | ✅ | ⚠️ 可以选择，但 Apple 说明 Mac 上的空间音频需要 Apple 芯片 | ❌ |
| macOS 13 及以下 | ✅ | ❌（系统未提供耳机运动接口） | ✅ | ❌（自动使用模组内置渲染） | ✅ 仅限 Apple 芯片、macOS 12.3+ |
| Windows 10 / 11 | ✅ | ❌ | ✅ | ❌（自动使用模组内置渲染） | ❌（只会输出普通立体声） |
| Linux | ✅ | ❌ | ✅ | ❌（自动使用模组内置渲染） | ❌（只会输出普通立体声） |

- **Apple 空间音频渲染**需要 macOS 14+；Apple 自动头部追踪（`isListenerHeadTrackingEnabled`）还需要 macOS 15+ 以及带 `com.apple.developer.coremotion.head-pose` 授权的自备助手，个性化空间音频需要 `com.apple.developer.spatial-audio.profile-access` 授权（Apple 文档标注该授权自 macOS 15 起提供）。随附的助手为 ad-hoc 签名，使用 Apple 的渲染引擎与通用 HRTF，头部姿态由本模组提供。详见 [Apple 空间音频渲染](Apple-Spatial-Audio.md)。
- **Apple 系统空间化立体声**是 macOS 自带的功能：Apple 说明 Mac 上的空间音频与头部追踪需要 Apple 芯片与 macOS 12.3 或更新，并且“并非适用于所有 App 或内容”。
- Minecraft 26.3 自带的 OpenAL Soft（随 LWJGL 3.4.3 内置，版本 1.25.2）的回环输出最多支持 7.1 与 4 阶 Ambisonics，不支持 7.1.4；它的 7.1 输出只在水平面上分布声音（不使用中置与低音声道），因此 Apple 空间音频默认使用 3 阶 Ambisonics。

## 耳机

| 设备 | 头部追踪 |
| --- | --- |
| AirPods（第 3 代及更新） | ✅ |
| AirPods Pro（全部世代） | ✅ |
| AirPods Max | ✅ |
| 支持动态头部追踪的 Beats 型号（以 Apple 官方列表为准） | ✅ |
| AirPods（第 1、2 代）及其它耳机 | 无头部追踪，HRTF 与声学照常 |
| 扬声器 | 建议把 HRTF 设为“跟随原版”或“关闭” |

## 其它模组

| 模组 | 状态 | 说明 |
| --- | --- | --- |
| Sound Physics Remastered | ⚠️ 自动协调 | 它同样修改声源滤波与混响发送。检测到它时，本模组默认让出声学模拟（状态显示“由 Sound Physics 接管”），保留 HRTF、头部追踪与屏幕锚定。关闭“兼容 Sound Physics”可强制两者同时工作（不推荐） |
| Simple Voice Chat | ✅ | 语音使用它自己的 OpenAL 设备与上下文播放，不经过原版声音引擎，因此不会被本模组处理；二者互不干扰 |
| Mod Menu | ✅ | 在模组列表中提供“配置”按钮 |
| AmbientSounds、Presence Footsteps 等添加声音的模组 | ✅ | 它们的声音同样经过原版声音引擎，自动获得全部处理；立体声音效会被强制三维定位 |
| 资源包、数据包中的自定义声音 | ✅ | 同上 |
| 用 OpenTrack 控制视角的模组 | ⚠️ | OpenTrack 的 UDP 输出只能发往一个端口，两个模组无法同时接收同一路数据。视角已随头部转动时，声音会随视角自然转动，本模组无需再做头部追踪：把“追踪数据源”设为“AirPods”或“关闭”即可 |
| 其它直接修改 `com.mojang.blaze3d.audio` 的模组 | ⚠️ | 若它们覆盖了本模组的注入点，游戏会在启动时明确报错（而不是悄悄失效），请反馈冲突 |

## 系统音频功能

| 功能 | 模组内置（默认） | Apple 空间音频 | Apple 系统空间化立体声 |
| --- | --- | --- | --- |
| macOS“空间音频”（菜单栏 AirPods / 控制中心） | 对 Java 设为“关闭”，避免双重空间化 | 若为 SpatialAudio Head Tracker 列出，设为“关闭”；Minecraft 本身不再直接发声，Java 的设置不起作用 | 对 Java 选择“头部追踪”，并保持 系统设置 → 辅助功能 → 音频 →“空间音频跟随头部移动”开启 |
| Windows Sonic / Dolby Atmos for Headphones | 关闭，原因相同 | 不适用：非 macOS 上自动使用模组内置渲染，按左列处理 | 不适用：此方式依赖 macOS |
| 均衡器、音量限制 | 不影响 | 不影响 | 不影响 |

各渲染方式的详细设置见 [macOS 与 AirPods 设置](macOS-AirPods.md#为什么要关闭系统空间音频)。
