# 空间音频与 HRTF

## 人耳如何判断方向

人类靠三类线索判断声音方向：

| 线索 | 原理 | 主要作用 |
| --- | --- | --- |
| 双耳时间差（ITD） | 声音到达两耳的时间差，最大约 0.7 毫秒 | 左右方向（低频） |
| 双耳强度差（ILD） | 头部遮挡造成远侧耳朵音量更小 | 左右方向（高频） |
| 频谱线索 | 耳廓、头部、肩膀对不同方向、不同频率的声音产生不同的反射与滤波 | 上下、前后 |

普通立体声只能表现左右。**HRTF（Head-Related Transfer Function，头相关传输函数）** 是实测出来的、从空间每个方向到左右耳的滤波器集合，把它施加到声音上，耳机就能还原上下、前后、左右的完整三维方位。这是 Apple 空间音频、游戏 3D 音效的共同基础。

## Minecraft 的音频引擎

Minecraft Java 版通过 LWJGL 使用 **OpenAL Soft**（26.3 内置 LWJGL 3.4.3，OpenAL Soft 1.25.2）。OpenAL Soft 本身就实现了高质量的 HRTF 渲染（“full” 模式下每个声源都用独立的 HRTF 滤波器，并在方向变化时平滑插值），原版也提供了“方向性音频”选项，但默认关闭，且：

- 不会强制以双耳模式输出；
- 不处理立体声声音的定位；
- 没有头部追踪；
- 没有环境声学。

## 渲染方式

本页描述的是默认的 **模组内置** 渲染方式：由 OpenAL Soft 的 HRTF 完成双耳渲染。设置 → 通用 → **空间音频渲染方式**（或 `/spatialaudio renderer <方式>`）还可以把这最后一步交给 Apple，详见 [Apple 空间音频渲染](Apple-Spatial-Audio.md)：

| 渲染方式 | 谁完成双耳渲染 | OpenAL 输出什么 |
| --- | --- | --- |
| **模组内置**（默认） | OpenAL Soft HRTF | 双耳立体声（见下文） |
| **Apple 空间音频** | Apple 空间音频引擎（`AVAudioEnvironmentNode`） | 回环设备上以摄像机为参照的多声道声场（默认 3 阶 Ambisonics），不做 HRTF |
| **Apple 系统空间化立体声** | macOS“空间化立体声” | 普通声像立体声，不做 HRTF |

“HRTF 双耳渲染”与“HRTF 数据集”两项设置**只对模组内置渲染有效**。另外两种方式下修改它们不会重新加载声音引擎，切回模组内置时才生效；状态面板中的 HRTF 一项显示为“由 Apple 空间音频引擎渲染”或“由 macOS 空间化立体声渲染”。选择了 Apple 空间音频但系统不支持或已自动回退时，使用的是模组内置渲染，HRTF 设置照常有效。

## 本模组对 OpenAL 的设置

模组内置渲染方式下，模组在创建 OpenAL 上下文时（替换原版 `Library#createAttributes`）请求以下属性：

| 属性 | 值 | 作用 |
| --- | --- | --- |
| `ALC_HRTF_SOFT` | `ALC_TRUE` | 开启 HRTF（“强制开启”模式；“跟随原版”时取原版选项） |
| `ALC_HRTF_ID_SOFT` | 选定数据集的序号 | 选择 HRTF 数据集 |
| `ALC_OUTPUT_MODE_SOFT` | `ALC_STEREO_HRTF_SOFT` | 即使系统把设备报告为多声道，也坚持双耳输出 |
| `ALC_OUTPUT_LIMITER_SOFT` | `ALC_TRUE` | 输出限幅，防止大量声音叠加时爆音（与原版一致） |
| `ALC_MAX_AUXILIARY_SENDS` | 2 | 两个辅助发送通道：环境混响与远距离回声 |

上下文创建后，模组读取 `ALC_HRTF_STATUS_SOFT` 与 `ALC_HRTF_SPECIFIER_SOFT`，在设置界面、调试叠加层和日志中显示 HRTF 的真实状态。修改 HRTF 相关设置后，模组会像切换原版“方向性音频”一样重新加载声音引擎。

### Apple 两种方式下的属性

| 属性 | Apple 空间音频（回环设备） | Apple 系统空间化立体声 |
| --- | --- | --- |
| `ALC_FORMAT_CHANNELS_SOFT` | `ALC_BFORMAT3D_SOFT`（Ambisonics）或 `ALC_7POINT1_SOFT`（7.1） | — |
| `ALC_FORMAT_TYPE_SOFT` / `ALC_FREQUENCY` | `ALC_FLOAT_SOFT` / 48000 | — |
| `ALC_AMBISONIC_LAYOUT_SOFT` / `ALC_AMBISONIC_SCALING_SOFT` / `ALC_AMBISONIC_ORDER_SOFT` | `ALC_ACN_SOFT` / `ALC_SN3D_SOFT` / 声场格式的阶数（仅 Ambisonics） | — |
| `ALC_HRTF_SOFT` | `ALC_FALSE` | `ALC_FALSE` |
| `ALC_OUTPUT_MODE_SOFT` | — | `ALC_STEREO_BASIC_SOFT`（普通声像立体声） |
| `ALC_OUTPUT_LIMITER_SOFT` | `ALC_TRUE` | `ALC_TRUE` |
| `ALC_MAX_AUXILIARY_SENDS` | 2 | 2 |

OpenAL Soft 1.25.2 的回环设备支持最高 7.1 与 B-Format（最高 4 阶 Ambisonics），不支持 7.1.4；7.1 输出只覆盖水平面，不使用中置与低音声道。默认的 3 阶 Ambisonics 则包含高度信息，也是 Apple 引擎支持的最高阶数。

### 使用的 OpenAL Soft 扩展

| 扩展 | 用途 |
| --- | --- |
| `ALC_SOFT_HRTF` | 开关 HRTF、选择数据集、查询状态 |
| `ALC_SOFT_output_mode` | 强制立体声 HRTF 输出；系统空间化立体声方式下请求普通声像立体声 |
| `ALC_SOFT_loopback` | Apple 空间音频方式：回环设备，由音频桥调用 `alcRenderSamplesSOFT` 取得混音结果 |
| `ALC_SOFT_loopback_bformat` | Apple 空间音频方式：回环设备输出 Ambisonics（ACN / SN3D） |
| `ALC_EXT_EFX` | 混响、回声效果槽与低通滤波器（见 [声学模拟](Acoustics.md)） |
| `AL_SOFT_source_spatialize` | 让立体声文件也按声源位置三维定位 |
| `AL_EXT_STEREO_ANGLES` | 旋转立体声虚拟扬声器，实现屏幕锚定 |
| `AL_SOFT_deferred_updates` | 批量提交参数，保证听者朝向与声源参数在同一个混音周期内生效 |

## 自定义 HRTF 数据集

OpenAL Soft 内置一套默认 HRTF。每个人的耳廓不同，换一套更贴合自己的 HRTF 往往能明显改善前后、上下的分辨。

1. 获取 `.mhr` 文件（OpenAL Soft 的 HRTF 格式），可用 OpenAL Soft 附带的 `makemhr` 工具从 SOFA 文件转换。常见的公开 HRTF 库有 MIT KEMAR、CIPIC、LISTEN、SADIE II 等。
2. 放到 OpenAL Soft 的数据目录：
   - macOS / Linux：`~/.local/share/openal/hrtf/`
   - Windows：`%AppData%\openal\hrtf\`
3. 重启游戏，在设置界面的“HRTF 数据集”中选择，或执行 `/spatialaudio hrtf` 查看列表。

## 立体声声音的处理

OpenAL 只会对**单声道**缓冲区做三维定位，立体声缓冲区默认按“左右声道直出”播放。Minecraft 原版的世界音效都是单声道，但资源包、数据包和其它模组经常添加立体声音效，原版会让它们失去方位。

本模组的处理：

| 情况 | 处理方式 |
| --- | --- |
| 世界中的立体声声音（非相对坐标） | 设置 `AL_SOURCE_SPATIALIZE_SOFT = AL_TRUE`，OpenAL Soft 会把各声道收拢到声源位置，正常参与距离衰减与 HRTF |
| 跟随听者的立体声（音乐、界面） | 保持立体声，作为两只虚拟扬声器渲染，并按屏幕锚定旋转 |
| 单声道声音 | 正常三维定位 |

可在“声音类别设置”中按类别关闭“三维定位”。

## 屏幕锚定

Apple 空间音频在播放音乐、视频时，会把声音固定在设备（屏幕）方向：转头时，声音仍然来自屏幕。本模组对“跟随听者”的声音（音乐、界面音效、部分环境音）实现了同样的效果：

- **立体声**：音乐等立体声声音以两只虚拟扬声器渲染，默认位于 ±30°。转头时，模组通过 `AL_STEREO_ANGLES` 把两只扬声器的角度反向旋转偏航角，使它们保持在屏幕两侧。例如向左转头 30°，左扬声器正好来到正前方。
- **相对单声道**：对带偏移的相对声源，按头部旋转的逆变换修正其相对位置。
- **世界声音**：本来就固定在世界中，无需额外处理。

可在设置中关闭“屏幕锚定”，此时音乐与界面音效将随头部转动（与普通耳机一致）。

以上做法只用于模组内置渲染。Apple 两种渲染方式下 OpenAL 听者不随头部旋转，屏幕锚定没有单独的效果，见 [声音类别适配](Sound-Categories.md#与渲染方式的关系)。

## 听者朝向合成

头部追踪的核心是把“摄像机朝向”与“真实头部旋转”合成为最终的 OpenAL 听者朝向：

- 摄像机给出右、前、上三个基向量 R、F、U（由鼠标控制）；
- 头部追踪给出头部相对屏幕的旋转 q（头部坐标系：x 右、y 前、z 上）；
- 最终前向 = `q·(0,1,0)` 在 (R, F, U) 基下展开；最终上方向 = `q·(0,0,1)` 在 (R, F, U) 基下展开。

于是声音被固定在世界中：向左转头 90°，原本正前方的声源会出现在右耳一侧，与现实一致。头部旋转叠加在视角之上，所以低头看地面时转头，仍是绕“视角的上方向”旋转。

原版只在每一帧渲染时更新听者，模组额外运行一个独立线程，按“听者刷新率”（默认 100 Hz）投递更新，使低帧率或暂停时转头依然顺滑。所有 OpenAL 调用都在原版声音线程中执行，与原版对声源的操作严格串行。

Apple 两种渲染方式下，合成时使用单位头部旋转：OpenAL 听者只跟随摄像机。Apple 空间音频方式由助手旋转 Apple 引擎的听者，系统空间化立体声方式由 macOS 完成头部追踪，见 [头部追踪原理](Head-Tracking.md#不同渲染方式下的头部姿态)。
