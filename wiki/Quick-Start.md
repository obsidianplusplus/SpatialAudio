# 安装与快速上手

## 1. 准备

| 项目 | 要求 |
| --- | --- |
| Minecraft | Java 版 26.3 |
| 模组加载器 | Fabric Loader 0.19.5 或更新 |
| 前置模组 | Fabric API（26.3 对应版本） |
| Java | 25（官方启动器自带的运行时即可） |
| 可选 | Mod Menu（在模组列表中提供“配置”按钮） |
| AirPods 头部追踪 | macOS 14 Sonoma 或更新；AirPods（第 3/4 代）、AirPods Pro、AirPods Max 或支持头部追踪的 Beats |

## 2. 安装

1. 用 [Fabric 安装器](https://fabricmc.net/use/) 为 26.3 安装 Fabric Loader。
2. 把 Fabric API 与 `spatialaudio-x.y.z.jar` 放入 `.minecraft/mods`。
   - 请使用 [Releases](https://github.com/obsidianplusplus/SpatialAudio/releases) 页面的 jar：它包含在 macOS 上编译、签名并自检过的 AirPods 助手。
3. 启动游戏。日志中出现以下两行即表示模组已接管音频：

   ```text
   [SpatialAudio] SpatialAudio 已加载：HRTF 模式 FORCE_ON，头部追踪 AUTO，声学模拟 开启
   [SpatialAudio] 空间音频已接管设备「……」：HRTF 已开启（……），立体声定位可用，屏幕锚定可用
   ```

## 3. 第一次使用 AirPods

1. 戴上 AirPods，并确认它是 macOS 当前的声音输出设备。
2. 在菜单栏 AirPods 图标（或 控制中心 → 声音）中把 Java 的 **空间音频** 设为 **关闭**（原因见 [macOS 与 AirPods 设置](macOS-AirPods.md#为什么要关闭系统空间音频)）。
3. 启动游戏后 macOS 会询问是否允许 “SpatialAudio Head Tracker” 访问运动与健身活动，点击 **允许**。
4. 进入世界，正对屏幕按 **F6** 回中（Mac 键盘为 `fn + F6`）。

## 4. 确认是否生效

按 **F7** 打开设置界面，顶部的实时状态面板会显示：

```text
头部追踪：AirPods 追踪中 · 25 Hz
头部姿态：偏航 +0.3°  俯仰 -1.2°  横滚 +0.4°
HRTF：已开启（Built-In HRTF） · 设备：AirPods Pro
声学：开启 · 封闭度 12% · RT60 0.18 秒 · 回声 0 格 · 声源 6
```

然后做三个小实验：

1. **方向**：站在一只鸡旁边，闭上眼，应能分辨它在前后左右哪个方向。
2. **头部追踪**：让鸡在正前方，向左转头，鸡叫声应移到右耳；向右转头则移到左耳。
3. **声学**：钻进一个石头小屋，说话般的方块音效会带有短混响；在墙外听唱片，声音会变闷，并且从门口方向传来。

在状态面板中，**向左转头时偏航应为正值、抬头时俯仰应为正值、向右歪头时横滚应为正值**。如果方向相反，可在“头部追踪”分组中开启对应的“反转”选项。

## 5. 常用操作

| 操作 | 方式 |
| --- | --- |
| 回中 | `F6`，或 `/spatialaudio recenter` |
| 设置界面 | `F7`，或 Mod Menu，或 `/spatialaudio config` |
| 状态查询 | `/spatialaudio status` |
| 调试叠加层 | 设置界面“界面”分组，或 `/spatialaudio hud` |
| 重启追踪 | 设置界面“重启头部追踪”按钮，或 `/spatialaudio restart` |

## 6. 没有 AirPods？

- **Windows / Linux / 其它耳机**：HRTF 双耳渲染与全部声学模拟照常工作，戴任何耳机都能获得空间感。
- **头部追踪**：可使用 [OpenTrack](OpenTrack.md) 接入摄像头、手机、TrackIR 或 VR 头显等追踪器。
- **只用扬声器**：建议在设置中把“HRTF 双耳渲染”改为“跟随原版”或“关闭”，HRTF 是为耳机设计的。
