# 配置参考

配置文件位于 `.minecraft/config/spatialaudio.json`，首次启动时自动生成。推荐使用游戏内设置界面（`F7`、Mod Menu 或 `/spatialaudio config`）修改；手动编辑后执行 `/spatialaudio reload` 生效。

- 非法数值会被自动修正到合法范围；
- 文件损坏无法解析时，会被改名为 `spatialaudio.json.broken` 备份，并使用默认配置；
- 修改 HRTF 相关设置会重新加载声音引擎（与切换原版“方向性音频”效果相同）；
- 修改数据源、端口或监听范围会重启头部追踪。

## 通用

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `enabled` | `true` | | 模组总开关，关闭后全部恢复原版行为 |
| `hrtfMode` | `"FORCE_ON"` | `FORCE_ON` / `FOLLOW_VANILLA` / `OFF` | HRTF 启用策略 |
| `hrtfName` | `""` | 数据集名称 | 指定 HRTF 数据集，空字符串表示默认 |

## 头部追踪

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `trackingSource` | `"AUTO"` | `AUTO` / `AIRPODS` / `OPENTRACK` / `DISABLED` | 数据来源 |
| `udpPort` | `4242` | 1~65535 | 接收姿态的本机 UDP 端口 |
| `udpListenAllInterfaces` | `false` | | 是否接收局域网其它设备的数据 |
| `trackingStrength` | `1.0` | 0~1 | 旋转强度 |
| `trackYaw` / `trackPitch` / `trackRoll` | `true` | | 分别追踪偏航、俯仰、横滚 |
| `invertYaw` / `invertPitch` / `invertRoll` | `false` | | 逐轴反转（校准第三方追踪器） |
| `recenterMode` | `"FULL"` | `FULL` / `YAW_ONLY` | 回中方式 |
| `autoRecenterOnConnect` | `true` | | 追踪器重新连接时自动回中 |
| `driftCorrection` | `true` | | 偏航漂移自动校正 |
| `driftCorrectionSeconds` | `25.0` | 5~120 | 漂移校正时间常数（秒） |
| `predictionMs` | `35.0` | 0~120 | 姿态预测时长（毫秒） |
| `smoothingMinCutoff` | `1.2` | 0.05~20 | One Euro 最小截止频率（Hz） |
| `smoothingBeta` | `0.35` | 0~5 | One Euro 速度系数 |
| `listenerUpdateRate` | `100` | 20~250 | 听者朝向刷新频率（Hz） |
| `useTrackerTranslation` | `false` | | 使用 OpenTrack 的头部平移 |
| `translationScale` | `1.0` | 0~3 | 平移缩放 |

## 声场

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `anchorToScreen` | `true` | 跟随听者的声音锚定屏幕方向 |
| `spatializeStereo` | `true` | 世界中的立体声声音强制三维定位 |

## 声学模拟

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `acousticsEnabled` | `true` | | 声学模拟总开关 |
| `respectSoundPhysicsMod` | `true` | | 检测到 Sound Physics Remastered 时自动让出声学模拟 |
| `reverbEnabled` | `true` | | 环境混响 |
| `reverbGain` | `1.0` | 0~2 | 混响强度 |
| `echoEnabled` | `true` | | 远距离回声 |
| `echoGain` | `0.7` | 0~2 | 回声强度 |
| `occlusionEnabled` | `true` | | 方块遮挡 |
| `occlusionStrength` | `1.0` | 0~3 | 遮挡强度倍率 |
| `soundRedirection` | `true` | | 绕射重定向 |
| `airAbsorption` | `true` | | 空气吸收 |
| `airAbsorptionFactor` | `1.0` | 0~10 | 空气吸收倍率 |
| `mediumFilter` | `true` | | 水下、岩浆、细雪中的介质滤波 |
| `dopplerEnabled` | `false` | | 多普勒效应 |
| `dopplerFactor` | `0.6` | 0~2 | 多普勒强度 |
| `probeRays` | `64` | 16~256 | 每轮环境探测射线数 |
| `probeDistance` | `48` | 8~128 | 混响探测距离（格） |
| `echoDistance` | `96` | 16~160 | 回声探测距离（格） |
| `diffractionCells` | `3000` | 0~20000 | 绕射寻路最多访问的格子数，0 表示只算直达路径 |
| `acousticsBudgetMs` | `1.5` | 0.2~10 | 每刻声学计算时间预算（毫秒） |

## 方块材质覆盖

按方块 ID 覆盖默认材质参数：

```json
{
  "blockOcclusionOverrides": {
    "minecraft:glass": 0.2,
    "minecraft:oak_leaves": 0.6
  },
  "blockAbsorptionOverrides": {
    "minecraft:white_wool": 0.95,
    "minecraft:deepslate": 0.01
  }
}
```

| 键 | 取值 | 说明 |
| --- | --- | --- |
| `blockOcclusionOverrides` | 0~10 | 每格厚度的遮挡量（石头为 1） |
| `blockAbsorptionOverrides` | 0~1 | 表面吸声系数（0 完全反射，1 完全吸收） |

方块 ID 不区分大小写，修改后立即生效（材质缓存随配置自动刷新）。

## 分类设置

`categories` 以声音类别名为键，每项包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `spatialize` | 布尔 | 立体声声音是否三维定位 |
| `occlusion` | 布尔 | 是否计算遮挡与绕射 |
| `reverbSend` | 0~2 | 混响与回声发送比例 |
| `screenAnchored` | 布尔 | 跟随听者的声音是否锚定屏幕 |

类别名：`master`、`music`、`record`、`weather`、`block`、`hostile`、`neutral`、`player`、`ambient`、`voice`、`ui`。各类别默认值见 [声音类别适配](Sound-Categories.md#各类别的默认设置)。

```json
{
  "categories": {
    "music": { "spatialize": false, "occlusion": false, "reverbSend": 0.0, "screenAnchored": true },
    "hostile": { "spatialize": true, "occlusion": true, "reverbSend": 1.0, "screenAnchored": false }
  }
}
```

## 界面

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `showHud` | `false` | 屏幕左上角调试叠加层 |
| `showSetupHints` | `true` | 进入世界时显示 AirPods 设置提示 |

## 完整的默认配置

```json
{
  "configVersion": 1,
  "enabled": true,
  "hrtfMode": "FORCE_ON",
  "hrtfName": "",
  "trackingSource": "AUTO",
  "udpPort": 4242,
  "udpListenAllInterfaces": false,
  "trackingStrength": 1.0,
  "trackYaw": true,
  "trackPitch": true,
  "trackRoll": true,
  "invertYaw": false,
  "invertPitch": false,
  "invertRoll": false,
  "recenterMode": "FULL",
  "autoRecenterOnConnect": true,
  "driftCorrection": true,
  "driftCorrectionSeconds": 25.0,
  "predictionMs": 35.0,
  "smoothingMinCutoff": 1.2,
  "smoothingBeta": 0.35,
  "listenerUpdateRate": 100,
  "useTrackerTranslation": false,
  "translationScale": 1.0,
  "anchorToScreen": true,
  "spatializeStereo": true,
  "acousticsEnabled": true,
  "respectSoundPhysicsMod": true,
  "reverbEnabled": true,
  "reverbGain": 1.0,
  "echoEnabled": true,
  "echoGain": 0.7,
  "occlusionEnabled": true,
  "occlusionStrength": 1.0,
  "soundRedirection": true,
  "airAbsorption": true,
  "airAbsorptionFactor": 1.0,
  "mediumFilter": true,
  "dopplerEnabled": false,
  "dopplerFactor": 0.6,
  "probeRays": 64,
  "probeDistance": 48,
  "echoDistance": 96,
  "diffractionCells": 3000,
  "acousticsBudgetMs": 1.5,
  "blockOcclusionOverrides": {},
  "blockAbsorptionOverrides": {},
  "categories": { "...": "见上文" },
  "showHud": false,
  "showSetupHints": true
}
```
