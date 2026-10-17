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

| 系统 | HRTF 与声学 | AirPods 头部追踪 | OpenTrack 头部追踪 |
| --- | --- | --- | --- |
| macOS 14 Sonoma 及以上（Apple Silicon / Intel） | ✅ | ✅ | ✅ |
| macOS 13 及以下 | ✅ | ❌（系统未提供耳机运动接口） | ✅ |
| Windows 10 / 11 | ✅ | ❌ | ✅ |
| Linux | ✅ | ❌ | ✅ |

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

| 功能 | 建议 |
| --- | --- |
| macOS“空间音频”（菜单栏 AirPods / 控制中心） | 对 Java 设为“关闭”，避免双重空间化 |
| Windows Sonic / Dolby Atmos for Headphones | 关闭，原因相同 |
| 均衡器、音量限制 | 不影响 |
