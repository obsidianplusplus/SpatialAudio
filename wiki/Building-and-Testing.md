# 构建与测试

## 环境要求

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25 | 推荐 Eclipse Temurin 25 |
| Gradle | 9.8.1 | 通过仓库自带的 Gradle Wrapper 自动下载，无需单独安装 |
| Fabric Loom | 1.19.1 | 由 Gradle 插件自动下载 |
| Xcode 命令行工具 | 任意支持 macOS 14 SDK 的版本 | 仅在 macOS 上编译助手（AirPods 头部追踪与 Apple 空间音频渲染）时需要（`xcode-select --install`） |

## 常用命令

```bash
./gradlew build              # 编译、运行单元测试、打包 jar（build/libs/）
./gradlew test               # 只运行单元测试
./gradlew runClient          # 启动带本模组的开发客户端（开启调试日志）
./gradlew runClientGameTest  # 运行客户端集成测试
./gradlew genSources         # 反编译 Minecraft 源码，便于阅读原版代码
./gradlew buildMacHelper     # 单独编译 AirPods 助手（仅 macOS）
```

产物：

| 文件 | 说明 |
| --- | --- |
| `build/libs/spatialaudio-<版本>.jar` | 模组本体，放入 `mods` 文件夹即可 |
| `build/libs/spatialaudio-<版本>-sources.jar` | 源码包 |
| `build/generated/mac-helper/` | 编译好的 SpatialAudioHeadTracker.app 与清单（仅 macOS） |

## AirPods 助手的构建

`native/macos/build.sh` 负责：

1. 分别为 arm64 与 x86_64 编译 `main.swift`（最低系统 macOS 14），并把 Info.plist 嵌入可执行文件的 `__TEXT,__info_plist` 段；
2. 用 `lipo` 合并为通用二进制，组装 `.app`（Info.plist、PkgInfo）；
3. `codesign --force --sign -` 做 ad-hoc 签名并校验；
4. 运行 `--self-test` 自检：除 SAHT 数据包外，还校验 [SAAB](SAAB-Protocol.md) 编解码与环形缓冲区，并做 Apple 空间音频的离线渲染检查（见下文），失败时构建中止；
5. 生成 `manifest.txt`：第一行为全部文件内容的摘要（模组据此判断是否需要重新解压），其后为文件列表。

### 自检中的 Apple 渲染检查

`--self-test` 不请求权限、不启动运动更新、也不需要音频设备，因此可以在 CI 的 macOS 虚拟机上运行。它用 `AVAudioEngine` 的离线手动渲染模式（双声道 48 kHz，环境节点输出类型为耳机）把 1 秒确定性宽带噪声按助手的真实信号链渲染一遍，比较左右声道能量：

| 用例 | 期望 |
| --- | --- |
| 3 阶 Ambisonics 声床，声源在左侧（W 与 ACN 1 有信号），不转头 | 左 > 1.5 × 右 |
| 3 阶 Ambisonics 声床，声源在正前方（W 与 ACN 3 有信号），经 HEAD 映射向左转头 90° | 右 > 1.5 × 左 |
| 3 阶 Ambisonics 声床，声源在正前方，不转头 | 0.67 < 左 / 右 < 1.5 |
| 7.1 声床，只有 SL 声道有信号 | 左 > 1.5 × 右 |

每个用例都会打印左右能量、实际使用的渲染算法与 `applicableRenderingAlgorithms`。Apple 文档只说明“源节点的方向决定声床在全局空间中的旋转”，没有说明源节点位置与声床朝向的具体约定，因此自检对 `(0,0,0)` 与 `(0,0,-1)` 两个候选位置分别运行全部用例并报告哪个通过，两个候选都不通过时这一项才算失败。自检最后输出 `selfTest=PASS` 或 `selfTest=FAIL`，任何一项失败都以非 0 退出码结束。`build.sh` 在此之外还要求运行时实际使用的位置（`main.swift` 中的常量 `bedSourcePosition`，目前为 `(0,0,-1)`）通过检查（输出行 `bedSourcePositionInUse=… inUsePasses=true`），否则同样中止构建，避免发布声床正前方摆错方向的助手。

Gradle 的 `processResources` 依赖 `buildMacHelper` 任务：在 macOS 主机上自动执行；其它系统上自动跳过（也可用 `-PskipMacHelper` 强制跳过），此时 jar 中只包含 Swift 源码，模组会在玩家的 Mac 上现场编译。

## 单元测试

位于 `src/test`，使用 JUnit 5，不需要启动游戏：

| 测试类 | 覆盖内容 |
| --- | --- |
| `QuatTest` | 头部坐标系约定、欧拉角分解与合成、球面插值、指数/对数映射 |
| `OneEuroFilterTest` | 静止降噪、阶跃收敛、快速运动低延迟 |
| `HeadPoseProcessorTest` | 自动回中、相对偏航、轴向开关、反转、预测领先、中断回归、手动回中、漂移校正 |
| `ProtocolTest` | SAHT 编解码与校验、OpenTrack 轴向约定与校验、UDP 接收器回环收包 |
| `VoxelRaycasterTest` | 体素遍历的顺序、连通性与段长 |
| `OcclusionSolverTest` | 无遮挡、墙厚、材质差异、门洞绕射与重定向、封闭房间、端点忽略 |
| `AirPathFinderTest` | 直线、门洞寻路、封闭与预算、半透声格子、远离原点的坐标 |
| `EnvironmentProbeTest` | 开阔地、平原、石室、羊毛房、参数范围、峡谷回声 |
| `ListenerControllerTest` | 听者朝向合成、视角叠加、屏幕锚定、立体声角度 |
| `ConfigTest` | 默认值、非法值修正、JSON 往返；渲染方式相关字段的默认值（内置渲染器、3 阶 Ambisonics、优先 Apple 自动头部追踪、30 毫秒缓冲），未知值与 null 的修正，缓冲时长限制在 10~200 毫秒，旧配置文件兼容，声场格式的声道数、阶数与翻译键 |
| `LanguageFilesTest` | 中英文键一致、中英文占位符数量一致、源码与枚举引用的键全部存在（包括渲染方式及其 `.tooltip`、声场格式、渲染状态及其 `.hint`） |
| `AppleBridgeProtocolTest` | [SAAB](SAAB-Protocol.md) 编解码：各消息往返与逐字节字段偏移、令牌定长、超长设备名按 UTF-8 字符边界截断、消息头校验（魔数、4 MiB 上限）、负载过短时报错 |
| `AppleAudioBridgeTest` | 测试代码扮演助手，经本机 TCP 连接音频桥：错误的令牌、版本或首条消息被拒绝；连接后收到空闲 CONFIG 与 HEAD；推送声场后按 REQUEST 恰好发送请求的帧数；旧代次请求被忽略；参数变化与拆除声场时下发新 CONFIG；STATUS 的记录与过期；新连接替换旧连接、更换令牌断开现有连接；没有助手时以接近实时的速度空转渲染 |

声学算法只依赖 `BlockQuery` 接口，测试中使用 `TestWorld` 合成世界（手动摆放方块）验证。

## 客户端集成测试

位于 `src/gametest`，基于 Fabric Client GameTest API，会启动一个真实的 Minecraft 26.3 客户端，创建超平坦创造世界并执行：

1. 检查 OpenAL 已启用 HRTF、EFX 与两个辅助发送通道（证明所有 Mixin 注入生效）；
2. 在左侧与正前方播放唱片，**分析 OpenAL 实际输出的双耳音频**，验证左右声道能量；
3. 启动模拟 OpenTrack 发送线程，注入“向左转头 90°”“向右转头 90°”，验证正前方声源分别移到右耳与左耳；
4. 砌墙验证遮挡与重定向；建石室验证封闭度与混响；放水验证介质滤波；
5. 打开设置界面、类别界面与调试叠加层，在英文与简体中文下截图，并滚动检查长列表；
6. 依次切换到“Apple 空间音频”“Apple 系统空间化立体声”并恢复“模组内置”，检查三种渲染方式（见下文）。

### Apple 渲染方式场景

这一场景在 Linux 上也能完整运行：测试先设置系统属性 `spatialaudio.apple.allowNonMac=true`（仅供自动化测试，允许在非 macOS 系统上启用回环设备与音频桥），再由测试线程扮演 macOS 助手——通过 [SAAB](SAAB-Protocol.md) 连接音频桥，以实时速度请求声场，每 250 毫秒报告“引擎运行中”，并统计各 Ambisonics 声道的能量及其与 W 的相关。Minecraft 26.3 通过 LWJGL 3.4.3 自带 OpenAL Soft 1.25.2，回环设备支持最高 7.1 与 B-Format（最高 4 阶 Ambisonics），因此 OpenAL 一侧的声场可以在 CI 中验证；Apple 引擎一侧由助手的 [`--self-test`](#自检中的-apple-渲染检查) 在 macOS 上验证。

| 阶段 | 检查 |
| --- | --- |
| 切换到 Apple 空间音频（3 阶 Ambisonics） | 使用回环设备，实际渲染方式为 `APPLE`，HRTF 状态为“由 Apple 空间音频引擎渲染”；助手收到的 CONFIG 为 16 声道、HOA ACN/SN3D 布局、48000 Hz；状态变为“Apple 空间音频 · 正在渲染” |
| 左侧声源 | Y（ACN 1）与 W 强正相关、增益大于 0.5；X（ACN 3）的增益接近 0 |
| 正前方声源 | X 与 W 强正相关、增益大于 0.5；Y 的增益接近 0 |
| 模拟 OpenTrack 向左转头 90° | 助手收到的偏航约为 +90°；OpenAL 听者的偏航保持在 ±5° 以内；正前方声源仍位于声场的 +X 方向 |
| 设置界面 | 滚动到渲染方式与“Apple 空间音频”分组，截图 `spatialaudio-apple-config-zh` |
| 切换到 Apple 系统空间化立体声 | HRTF 状态为“由 macOS 空间化立体声渲染”；不使用回环设备，音频桥关闭；本模组停止自己的头部追踪；有 WAV 输出时，左侧声源的左声道能量大于右声道的 1.5 倍 |
| 恢复模组内置 | HRTF 重新开启，不使用回环设备，头部追踪重新启动 |

Apple 声场的分析直接使用测试助手收到的数据，不依赖 `SPATIALAUDIO_TEST_WAV`。

### 在 Linux 上运行

需要虚拟显示器与软件渲染。Xvfb 的软件 GLX 不提供 sRGB 视觉，而 26.3 的 OpenGL 后端要求 sRGB 帧缓冲，因此让 SDL 改用 EGL：

```bash
sudo apt-get install -y xvfb libgl1-mesa-dri libegl1 libegl-mesa0

# 让 OpenAL Soft 把输出写到 WAV 文件，测试会分析其中的双耳声道
cat > /tmp/alsoft.conf <<EOF
[general]
drivers = wave
channels = stereo
frequency = 48000
sample-type = int16

[wave]
file = /tmp/spatialaudio-test.wav
EOF

export ALSOFT_CONF=/tmp/alsoft.conf ALSOFT_DRIVERS=wave
export SPATIALAUDIO_TEST_WAV=/tmp/spatialaudio-test.wav
export SDL_VIDEO_FORCE_EGL=1 LIBGL_ALWAYS_SOFTWARE=1
export XDG_RUNTIME_DIR=/tmp/xdg-runtime && mkdir -p $XDG_RUNTIME_DIR && chmod 700 $XDG_RUNTIME_DIR
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest
```

截图保存在 `build/run/clientGameTest/screenshots/`。不设置 `SPATIALAUDIO_TEST_WAV` 时，测试会跳过音频能量断言，其余检查照常进行。

### 在 macOS / Windows 上运行

直接执行 `./gradlew runClientGameTest` 即可（会使用真实的音频设备；不设置 `SPATIALAUDIO_TEST_WAV` 时跳过音频能量断言）。

## 持续集成

`.github/workflows/build.yml`：

| 任务 | 运行环境 | 内容 |
| --- | --- | --- |
| macOS 构建 | `macos-latest` | 编译通用助手、构建 jar、单元测试；校验 jar 内含助手、签名有效、架构正确、自检通过（含 Apple 空间音频离线渲染检查）、Info.plist 含 `NSMotionUsageDescription`；上传 jar |
| Linux 测试 | `ubuntu-latest` | 单元测试；在 Xvfb + EGL + OpenAL wave 后端下运行完整的客户端集成测试（含 Apple 渲染方式场景）；上传截图与日志 |
| 发布 | `ubuntu-latest` | 推送 `v*` 标签时，把 macOS 任务构建的 jar 发布为 GitHub Release |

发布新版本：

```bash
# 修改 gradle.properties 中的 mod_version 后
git tag v1.0.0
git push origin v1.0.0
```

## 代码规范

- 所有注释使用中文：类、方法、字段使用 Javadoc（`/** ... */`），句末使用中文句号；方法注释包含 `@param` / `@return`；行内说明使用 `// ...`。
- Java 使用 Tab 缩进，Swift 使用 4 个空格（见 `.editorconfig`）。
- 新增翻译键需同时加入 `zh_cn.json` 与 `en_us.json`，`LanguageFilesTest` 会检查。
- 只在原版声音线程中调用 OpenAL（唯一的例外是 Apple 空间音频方式下音频桥泵线程调用 `alcRenderSamplesSOFT`），只在客户端主线程中读取世界（见 [架构与源码导读](Architecture.md#线程模型)）。
- 修改 SAAB 协议时，Java 与 Swift 两端以及 [SAAB 协议](SAAB-Protocol.md) 页面必须同时更新，`AppleBridgeProtocolTest` 会逐字节检查 Java 端的字段偏移。
