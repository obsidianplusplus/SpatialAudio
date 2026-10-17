package io.github.obsidianplusplus.spatialaudio.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.sounds.SoundSource;

/**
 * 模组的全部可配置项。
 *
 * <p>本类是纯数据对象，由 {@link ConfigManager} 以 JSON 形式读写到 {@code config/spatialaudio.json}。
 * 字段按功能分组：双耳渲染、Apple 空间音频、头部追踪、非定位声音、声学模拟、方块材质覆盖、分类设置与界面。
 */
public final class SpatialAudioConfig {
	/** 当前配置格式版本，用于将来迁移旧配置。 */
	public static final int CURRENT_VERSION = 1;

	/** 配置文件版本号。 */
	public int configVersion = CURRENT_VERSION;

	/** 模组总开关，关闭后所有处理恢复为原版行为。 */
	public boolean enabled = true;

	// ===================== 双耳渲染 =====================

	/** HRTF 启用策略。 */
	public HrtfMode hrtfMode = HrtfMode.FORCE_ON;

	/** 指定使用的 HRTF 数据集名称，空字符串表示使用 OpenAL Soft 默认数据集。 */
	public String hrtfName = "";

	// ===================== Apple 空间音频 =====================

	/** 空间音频渲染方式：模组内置、Apple 空间音频或 Apple 系统空间化立体声。 */
	public SpatialRenderer renderer = SpatialRenderer.BUILT_IN;

	/** Apple 空间音频方式下交给 Apple 渲染引擎的声场格式。 */
	public AppleBedFormat appleBedFormat = AppleBedFormat.AMBISONICS_3;

	/** 助手具备 Apple 头部追踪授权时，优先使用 Apple 自动头部追踪；否则使用本模组处理后的头部姿态。 */
	public boolean applePreferAutoHeadTracking = true;

	/** 助手中排队等待播放的音频时长（毫秒），越小延迟越低，越大越不容易断音。 */
	public int appleBufferMs = 30;

	// ===================== 头部追踪 =====================

	/** 头部追踪数据来源。 */
	public HeadTrackingSource trackingSource = HeadTrackingSource.AUTO;

	/** 接收头部姿态的 UDP 端口（与 OpenTrack 默认端口 4242 一致）。 */
	public int udpPort = 4242;

	/** 是否监听所有网卡（默认只监听本机回环地址，更安全）。 */
	public boolean udpListenAllInterfaces = false;

	/** 头部旋转作用强度，0 表示不跟随，1 表示与真实转头一致。 */
	public float trackingStrength = 1.0f;

	/** 是否追踪偏航（左右转头）。 */
	public boolean trackYaw = true;

	/** 是否追踪俯仰（抬头低头）。 */
	public boolean trackPitch = true;

	/** 是否追踪横滚（侧向歪头）。 */
	public boolean trackRoll = true;

	/** 偏航方向取反（用于校准第三方追踪器）。 */
	public boolean invertYaw = false;

	/** 俯仰方向取反。 */
	public boolean invertPitch = false;

	/** 横滚方向取反。 */
	public boolean invertRoll = false;

	/** 回中方式。 */
	public RecenterMode recenterMode = RecenterMode.FULL;

	/** 追踪器重新连接（例如重新戴上 AirPods）时自动回中。 */
	public boolean autoRecenterOnConnect = true;

	/** 是否启用缓慢的偏航漂移自动校正。 */
	public boolean driftCorrection = true;

	/** 漂移校正的时间常数（秒），越大校正越缓慢。 */
	public float driftCorrectionSeconds = 25.0f;

	/** 头部姿态预测时长（毫秒），用于抵消蓝牙与渲染延迟。 */
	public float predictionMs = 35.0f;

	/** One Euro 滤波器最小截止频率（Hz），越小越平滑但延迟越大。 */
	public float smoothingMinCutoff = 1.2f;

	/** One Euro 滤波器速度系数，越大快速转头时越跟手。 */
	public float smoothingBeta = 0.35f;

	/** 听者朝向的刷新频率（Hz）。 */
	public int listenerUpdateRate = 100;

	/** 是否使用追踪器提供的头部平移（仅 OpenTrack 6DoF 数据有效）。 */
	public boolean useTrackerTranslation = false;

	/** 头部平移缩放系数。 */
	public float translationScale = 1.0f;

	// ===================== 非定位声音 =====================

	/** 音乐、界面等跟随听者的声音是否锚定在屏幕方向。 */
	public boolean anchorToScreen = true;

	/** 世界中的立体声声音是否强制按声源位置三维定位。 */
	public boolean spatializeStereo = true;

	// ===================== 声学模拟 =====================

	/** 声学模拟总开关（混响、遮挡、回声、空气吸收、介质滤波）。 */
	public boolean acousticsEnabled = true;

	/** 检测到 Sound Physics Remastered 时自动关闭本模组的声学模拟，避免重复处理。 */
	public boolean respectSoundPhysicsMod = true;

	/** 是否启用环境混响。 */
	public boolean reverbEnabled = true;

	/** 混响总增益。 */
	public float reverbGain = 1.0f;

	/** 是否启用远距离回声（山谷、峡谷）。 */
	public boolean echoEnabled = true;

	/** 回声增益。 */
	public float echoGain = 0.7f;

	/** 是否启用方块遮挡。 */
	public boolean occlusionEnabled = true;

	/** 遮挡强度倍率。 */
	public float occlusionStrength = 1.0f;

	/** 是否启用声音绕射重定向（被墙挡住的声音从门洞、拐角方向传来）。 */
	public boolean soundRedirection = true;

	/** 是否启用空气吸收（远处声音高频衰减）。 */
	public boolean airAbsorption = true;

	/** 空气吸收倍率。 */
	public float airAbsorptionFactor = 1.0f;

	/** 是否启用介质滤波（水下、岩浆、细雪中的闷声效果）。 */
	public boolean mediumFilter = true;

	/** 是否启用多普勒效应。 */
	public boolean dopplerEnabled = false;

	/** 多普勒效应强度。 */
	public float dopplerFactor = 0.6f;

	/** 环境探测射线数量。 */
	public int probeRays = 64;

	/** 环境探测最大距离（格）。 */
	public int probeDistance = 48;

	/** 回声探测最大距离（格）。 */
	public int echoDistance = 96;

	/** 每个被遮挡声源搜索绕射路径时最多访问的方块格数（0 表示只计算直达路径）。 */
	public int diffractionCells = 3000;

	/** 每个游戏刻声学计算的时间预算（毫秒）。 */
	public float acousticsBudgetMs = 1.5f;

	// ===================== 方块材质覆盖 =====================

	/** 按方块 ID 覆盖每格遮挡系数，例如 {"minecraft:glass": 0.3}。 */
	public Map<String, Float> blockOcclusionOverrides = new LinkedHashMap<>();

	/** 按方块 ID 覆盖表面吸声系数（0~1），例如 {"minecraft:white_wool": 0.9}。 */
	public Map<String, Float> blockAbsorptionOverrides = new LinkedHashMap<>();

	// ===================== 分类设置 =====================

	/** 每个声音类别的设置，键为 {@link SoundSource#getName()}。 */
	public Map<String, CategorySettings> categories = defaultCategories();

	// ===================== 界面 =====================

	/** 是否显示调试信息叠加层。 */
	public boolean showHud = false;

	/** 是否在进入世界时显示 AirPods 设置提示。 */
	public boolean showSetupHints = true;

	/**
	 * 生成全部声音类别的默认设置。
	 *
	 * @return 有序映射
	 */
	public static Map<String, CategorySettings> defaultCategories() {
		final Map<String, CategorySettings> map = new LinkedHashMap<>();
		for (final SoundSource source : SoundSource.values()) {
			map.put(source.getName(), CategorySettings.defaultsFor(source));
		}
		return map;
	}

	/**
	 * 获取某个声音类别的设置；缺失时自动补全默认值。
	 *
	 * @param source 声音类别，可为 null（视为 MASTER）
	 * @return 类别设置
	 */
	public CategorySettings category(final SoundSource source) {
		final SoundSource key = source == null ? SoundSource.MASTER : source;
		return this.categories.computeIfAbsent(key.getName(), name -> CategorySettings.defaultsFor(key));
	}

	/**
	 * 把所有字段修正到合法范围，并补全缺失项。
	 *
	 * <p>读取用户手动编辑的配置文件后必须调用，避免非法数值进入 OpenAL 导致错误。
	 */
	public void sanitize() {
		if (this.hrtfMode == null) {
			this.hrtfMode = HrtfMode.FORCE_ON;
		}
		if (this.hrtfName == null) {
			this.hrtfName = "";
		}
		if (this.renderer == null) {
			this.renderer = SpatialRenderer.BUILT_IN;
		}
		if (this.appleBedFormat == null) {
			this.appleBedFormat = AppleBedFormat.AMBISONICS_3;
		}
		this.appleBufferMs = Math.clamp(this.appleBufferMs, 10, 200);
		if (this.trackingSource == null) {
			this.trackingSource = HeadTrackingSource.AUTO;
		}
		if (this.recenterMode == null) {
			this.recenterMode = RecenterMode.FULL;
		}
		this.udpPort = Math.clamp(this.udpPort, 1, 65535);
		this.trackingStrength = clampFinite(this.trackingStrength, 0.0f, 1.0f, 1.0f);
		this.driftCorrectionSeconds = clampFinite(this.driftCorrectionSeconds, 5.0f, 120.0f, 25.0f);
		this.predictionMs = clampFinite(this.predictionMs, 0.0f, 120.0f, 35.0f);
		this.smoothingMinCutoff = clampFinite(this.smoothingMinCutoff, 0.05f, 20.0f, 1.2f);
		this.smoothingBeta = clampFinite(this.smoothingBeta, 0.0f, 5.0f, 0.35f);
		this.listenerUpdateRate = Math.clamp(this.listenerUpdateRate, 20, 250);
		this.translationScale = clampFinite(this.translationScale, 0.0f, 3.0f, 1.0f);
		this.reverbGain = clampFinite(this.reverbGain, 0.0f, 2.0f, 1.0f);
		this.echoGain = clampFinite(this.echoGain, 0.0f, 2.0f, 0.7f);
		this.occlusionStrength = clampFinite(this.occlusionStrength, 0.0f, 3.0f, 1.0f);
		this.airAbsorptionFactor = clampFinite(this.airAbsorptionFactor, 0.0f, 10.0f, 1.0f);
		this.dopplerFactor = clampFinite(this.dopplerFactor, 0.0f, 2.0f, 0.6f);
		this.probeRays = Math.clamp(this.probeRays, 16, 256);
		this.probeDistance = Math.clamp(this.probeDistance, 8, 128);
		this.echoDistance = Math.clamp(this.echoDistance, 16, 160);
		this.diffractionCells = Math.clamp(this.diffractionCells, 0, 20000);
		this.acousticsBudgetMs = clampFinite(this.acousticsBudgetMs, 0.2f, 10.0f, 1.5f);

		if (this.blockOcclusionOverrides == null) {
			this.blockOcclusionOverrides = new LinkedHashMap<>();
		}
		if (this.blockAbsorptionOverrides == null) {
			this.blockAbsorptionOverrides = new LinkedHashMap<>();
		}
		sanitizeOverrides(this.blockOcclusionOverrides, 0.0f, 10.0f);
		sanitizeOverrides(this.blockAbsorptionOverrides, 0.0f, 1.0f);

		if (this.categories == null) {
			this.categories = defaultCategories();
		}
		for (final SoundSource source : SoundSource.values()) {
			final CategorySettings settings = this.categories.get(source.getName());
			if (settings == null) {
				this.categories.put(source.getName(), CategorySettings.defaultsFor(source));
			} else {
				settings.sanitize();
			}
		}
		this.configVersion = CURRENT_VERSION;
	}

	/**
	 * 清理方块覆盖表：去掉空键、非法数值，并把 ID 统一为小写。
	 *
	 * @param map 覆盖表
	 * @param min 最小值
	 * @param max 最大值
	 */
	private static void sanitizeOverrides(final Map<String, Float> map, final float min, final float max) {
		final Map<String, Float> cleaned = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			if (key != null && !key.isBlank() && value != null && Float.isFinite(value)) {
				cleaned.put(key.trim().toLowerCase(Locale.ROOT), Math.clamp(value, min, max));
			}
		});
		map.clear();
		map.putAll(cleaned);
	}

	/**
	 * 限制浮点数范围；遇到 NaN 或无穷大时使用默认值。
	 *
	 * @param value 原值
	 * @param min 最小值
	 * @param max 最大值
	 * @param fallback 非法时的默认值
	 * @return 修正后的值
	 */
	private static float clampFinite(final float value, final float min, final float max, final float fallback) {
		return Float.isFinite(value) ? Math.clamp(value, min, max) : fallback;
	}
}
