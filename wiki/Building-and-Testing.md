# 构建与测试

## 环境要求

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25 | 推荐 Eclipse Temurin 25 |
| Gradle | 9.8.1 | 通过仓库自带的 Gradle Wrapper 自动下载，无需单独安装 |
| Fabric Loom | 1.19.1 | 由 Gradle 插件自动下载 |
| Xcode 命令行工具 | 任意支持 macOS 14 SDK 的版本 | 仅在 macOS 上编译 AirPods 助手时需要（`xcode-select --install`） |

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
4. 运行 `--self-test` 自检；
5. 生成 `manifest.txt`：第一行为全部文件内容的摘要（模组据此判断是否需要重新解压），其后为文件列表。

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
| `ConfigTest` | 默认值、非法值修正、JSON 往返 |
| `LanguageFilesTest` | 中英文键一致、源码与枚举引用的键全部存在 |

声学算法只依赖 `BlockQuery` 接口，测试中使用 `TestWorld` 合成世界（手动摆放方块）验证。

## 客户端集成测试

位于 `src/gametest`，基于 Fabric Client GameTest API，会启动一个真实的 Minecraft 26.3 客户端，创建超平坦创造世界并执行：

1. 检查 OpenAL 已启用 HRTF、EFX 与两个辅助发送通道（证明所有 Mixin 注入生效）；
2. 在左侧与正前方播放唱片，**分析 OpenAL 实际输出的双耳音频**，验证左右声道能量；
3. 启动模拟 OpenTrack 发送线程，注入“向左转头 90°”“向右转头 90°”，验证正前方声源分别移到右耳与左耳；
4. 砌墙验证遮挡与重定向；建石室验证封闭度与混响；放水验证介质滤波；
5. 打开设置界面、类别界面与调试叠加层，在英文与简体中文下截图，并滚动检查长列表。

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
| macOS 构建 | `macos-latest` | 编译通用助手、构建 jar、单元测试；校验 jar 内含助手、签名有效、架构正确、自检通过、Info.plist 含 `NSMotionUsageDescription`；上传 jar |
| Linux 测试 | `ubuntu-latest` | 单元测试；在 Xvfb + EGL + OpenAL wave 后端下运行完整的客户端集成测试；上传截图与日志 |
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
- 只在原版声音线程中调用 OpenAL，只在客户端主线程中读取世界（见 [架构与源码导读](Architecture.md#线程模型)）。
