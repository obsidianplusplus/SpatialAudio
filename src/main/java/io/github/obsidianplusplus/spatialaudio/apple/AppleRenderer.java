package io.github.obsidianplusplus.spatialaudio.apple;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.config.AppleBedFormat;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.config.SpatialRenderer;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.headtracking.MacHelperLauncher;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.openal.SOFTLoopbackBformat;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.lwjgl.system.MemoryStack;

/**
 * Apple 空间音频渲染方式的总协调者。
 *
 * <p>工作方式：选择“Apple 空间音频”后，Minecraft 打开的不再是真实音频设备，而是 OpenAL Soft 的回环设备。
 * OpenAL 照常完成全部声源、混响、遮挡与滤波，但不做 HRTF，而是把以摄像机为参照的声场渲染为
 * Ambisonics（ACN/SN3D）或 7.1 多声道数据；{@link AppleAudioBridge} 再把这些数据经本机 TCP 交给 macOS 助手，
 * 由 Apple 的空间音频引擎（AVAudioEnvironmentNode，环境声床模式）完成双耳渲染，并按头部姿态旋转听者。
 *
 * <p>任何一步失败（系统不支持、助手无法启动、长时间未连接、渲染引擎出错）都会自动回退到模组内置渲染器，
 * 保证游戏始终有声音；用户重新选择渲染方式或执行 {@code /spatialaudio restart} 后再次尝试。
 *
 * <p>线程：{@link #configure}、{@link #tick} 在主线程调用；{@code openLoopbackDevice}、{@code onContextReady}、
 * {@code onLibraryCleanup} 在声音引擎加载线程调用；渲染在音频桥的泵线程中进行。
 */
public final class AppleRenderer {
	/** 全局唯一实例。 */
	private static final AppleRenderer INSTANCE = new AppleRenderer();

	/** 允许在非 macOS 系统上启用回环与音频桥的系统属性（仅用于自动化测试，由测试代码模拟助手）。 */
	public static final String ALLOW_NON_MAC_PROPERTY = "spatialaudio.apple.allowNonMac";

	/** 声场采样率（Hz）。 */
	public static final int SAMPLE_RATE = 48000;

	/** 声场开始推送后多久仍未连上助手即回退（秒）。 */
	private static final double CONNECT_TIMEOUT_SECONDS = 12.0;

	/** 助手持续报告引擎错误多久后回退（秒）。 */
	private static final double ENGINE_ERROR_TIMEOUT_SECONDS = 5.0;

	/** 音频桥；未选择 Apple 渲染方式时为 null。 */
	private volatile AppleAudioBridge bridge;

	/** 用户是否选择了 Apple 空间音频。 */
	private volatile boolean wanted;

	/** 配置中的声场格式。 */
	private volatile AppleBedFormat bedFormat = AppleBedFormat.AMBISONICS_3;

	/** 配置中的缓冲时长（毫秒）。 */
	private volatile int bufferMs = 30;

	/** 当前回环设备句柄；0 表示未使用回环设备。 */
	private volatile long loopbackDevice;

	/** 当前回环设备使用的声场格式。 */
	private volatile AppleBedFormat activeFormat = AppleBedFormat.AMBISONICS_3;

	/** 当前目标输出设备名称（用户选择的设备或系统默认设备）。 */
	private volatile String targetDeviceName = "";

	/** 是否由用户明确选择了输出设备（否则助手跟随系统默认设备）。 */
	private volatile boolean explicitDevice;

	/** 是否已回退到内置渲染器。 */
	private volatile boolean fallbackLatched;

	/** 回退原因（可翻译文本）。 */
	private volatile Component fallbackReason = Component.empty();

	/** 开始推送声场的时刻（纳秒）。 */
	private volatile long streamStartedNanos;

	/** 最近一次处于已连接状态的时刻（纳秒）。 */
	private volatile long lastConnectedNanos;

	/** 引擎错误开始出现的时刻（纳秒）；0 表示当前无错误。 */
	private volatile long engineErrorSinceNanos;

	private AppleRenderer() {
	}

	/**
	 * 获取全局实例。
	 *
	 * @return 实例
	 */
	public static AppleRenderer get() {
		return INSTANCE;
	}

	/**
	 * 当前平台能否使用 Apple 空间音频（macOS 14 及以上；测试时可通过系统属性放行）。
	 *
	 * @return 支持时返回 true
	 */
	public static boolean platformSupported() {
		return MacHelperLauncher.isSupportedMacOs() || Boolean.getBoolean(ALLOW_NON_MAC_PROPERTY);
	}

	/**
	 * 把缓冲毫秒数换算为帧数。
	 *
	 * @param milliseconds 毫秒
	 * @return 帧数
	 */
	public static int framesFor(final int milliseconds) {
		return milliseconds * SAMPLE_RATE / 1000;
	}

	// ===================== 配置（主线程） =====================

	/**
	 * 按配置创建或关闭音频桥，并同步可在线调整的参数。
	 *
	 * @param config 当前配置
	 */
	public synchronized void configure(final SpatialAudioConfig config) {
		final boolean nowWanted = config.enabled && config.renderer == SpatialRenderer.APPLE;
		this.bedFormat = config.appleBedFormat;
		this.bufferMs = config.appleBufferMs;
		if (!nowWanted) {
			this.wanted = false;
			this.fallbackLatched = false;
			this.fallbackReason = Component.empty();
			this.closeBridge();
			return;
		}
		this.wanted = true;
		if (!platformSupported()) {
			this.closeBridge();
			return;
		}
		if (this.bridge == null) {
			try {
				this.bridge = new AppleAudioBridge(() -> HeadTrackingManager.get().currentRotation());
				SpatialAudio.LOGGER.info("Apple 音频桥已在 127.0.0.1:{} 监听", this.bridge.port());
			} catch (IOException e) {
				this.latch(Component.translatable("spatialaudio.apple_fallback.bridge", String.valueOf(e.getMessage())));
				return;
			}
		}
		this.bridge.updateSettings(new AppleAudioBridge.Settings(framesFor(config.appleBufferMs), config.applePreferAutoHeadTracking));
	}

	/**
	 * 清除回退状态（用户重新选择渲染方式或手动重启时调用），下次重新加载声音引擎时再次尝试 Apple 渲染。
	 */
	public void resetFallback() {
		this.fallbackLatched = false;
		this.fallbackReason = Component.empty();
		this.engineErrorSinceNanos = 0L;
	}

	/**
	 * 判断配置变化后是否需要重新加载声音引擎。
	 *
	 * @param before 修改前
	 * @param after 修改后
	 * @return 需要重新加载时返回 true
	 */
	public static boolean needsReload(final SpatialAudioConfig before, final SpatialAudioConfig after) {
		return before.renderer != after.renderer
			|| after.renderer == SpatialRenderer.APPLE && before.appleBedFormat != after.appleBedFormat;
	}

	/**
	 * 助手需要的音频参数；每次调用都会更换令牌，使旧的助手实例无法再连接。
	 *
	 * @return 参数；未选择 Apple 渲染方式、平台不支持或已回退时返回 null
	 */
	public synchronized HelperLink helperLink() {
		final AppleAudioBridge current = this.bridge;
		if (!this.wanted || current == null || this.fallbackLatched || !MacHelperLauncher.isSupportedMacOs()) {
			return null;
		}
		return new HelperLink(current.port(), current.rotateToken());
	}

	/**
	 * 测试用：音频桥的端口与令牌（不更换令牌）。
	 *
	 * @return 参数；音频桥不存在时返回 null
	 */
	public HelperLink endpointForTests() {
		final AppleAudioBridge current = this.bridge;
		return current == null ? null : new HelperLink(current.port(), current.token());
	}

	/**
	 * 游戏退出时关闭音频桥。
	 */
	public synchronized void shutdown() {
		this.wanted = false;
		this.closeBridge();
	}

	/**
	 * 关闭音频桥。
	 */
	private void closeBridge() {
		final AppleAudioBridge current = this.bridge;
		this.bridge = null;
		if (current != null) {
			current.close();
		}
	}

	// ===================== 声音引擎钩子（声音引擎加载线程） =====================

	/**
	 * 在原版打开音频设备时调用：需要 Apple 渲染时改为打开 OpenAL Soft 回环设备。
	 *
	 * @param preferredDevice 用户选择的设备名称，null 表示系统默认
	 * @param systemDefaultDevice 系统默认设备名称
	 * @return 回环设备句柄；不使用 Apple 渲染或创建失败时返回 0（由原版继续打开真实设备）
	 */
	public long openLoopbackDevice(final String preferredDevice, final String systemDefaultDevice) {
		this.loopbackDevice = 0L;
		if (!this.wanted || this.fallbackLatched || this.bridge == null || !platformSupported()) {
			return 0L;
		}
		final AppleBedFormat format = this.bedFormat;
		final long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
		if (device == 0L) {
			this.latch(Component.translatable("spatialaudio.apple_fallback.loopback"));
			return 0L;
		}
		Component problem = checkFormat(device, format);
		if (problem == null) {
			this.activeFormat = format;
			problem = this.trialContext(device);
		}
		if (problem != null) {
			ALC10.alcCloseDevice(device);
			this.latch(problem);
			return 0L;
		}
		this.explicitDevice = preferredDevice != null;
		this.targetDeviceName = preferredDevice != null ? preferredDevice : systemDefaultDevice == null ? "" : systemDefaultDevice;
		this.loopbackDevice = device;
		SpatialAudio.LOGGER.info("Apple 空间音频：已改用 OpenAL 回环设备，声场格式 {}（{} 声道），目标输出「{}」",
			format, format.channels(), this.targetDeviceName.isEmpty() ? "系统默认" : this.targetDeviceName);
		return device;
	}

	/**
	 * 检查回环设备是否支持指定声场格式。
	 *
	 * @param device 回环设备
	 * @param format 声场格式
	 * @return 不支持的原因；支持时返回 null
	 */
	private static Component checkFormat(final long device, final AppleBedFormat format) {
		if (!ALC10.alcIsExtensionPresent(device, "ALC_SOFT_loopback")) {
			return Component.translatable("spatialaudio.apple_fallback.no_loopback");
		}
		if (format.ambisonic()) {
			// 注意：LWJGL 的 ALCCapabilities 对该扩展的检测有误，必须直接查询扩展字符串。
			if (!ALC10.alcIsExtensionPresent(device, "ALC_SOFT_loopback_bformat")) {
				return Component.translatable("spatialaudio.apple_fallback.no_bformat");
			}
			final int maxOrder = ALC10.alcGetInteger(device, SOFTLoopbackBformat.ALC_MAX_AMBISONIC_ORDER_SOFT);
			ALC10.alcGetError(device);
			if (maxOrder < format.ambisonicOrder()) {
				return Component.translatable("spatialaudio.apple_fallback.max_order", maxOrder);
			}
		}
		final boolean supported = SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, SAMPLE_RATE, channelsEnum(format), SOFTLoopback.ALC_FLOAT_SOFT);
		ALC10.alcGetError(device);
		return supported ? null : Component.translatable("spatialaudio.apple_fallback.format");
	}

	/**
	 * 用即将使用的属性试建一次上下文并立即销毁。原版在上下文创建失败时会关闭整个声音系统，
	 * 提前试建可以在失败时改为打开真实设备，保证游戏仍有声音。
	 *
	 * @param device 回环设备
	 * @return 失败原因；成功时返回 null
	 */
	private Component trialContext(final long device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			final long context = ALC10.alcCreateContext(device, this.buildLoopbackAttributes(device, stack));
			final int error = ALC10.alcGetError(device);
			if (context == 0L) {
				return Component.translatable("spatialaudio.apple_fallback.context", "0x" + Integer.toHexString(error));
			}
			ALC10.alcDestroyContext(context);
			ALC10.alcGetError(device);
			return null;
		}
	}

	/**
	 * 声场格式对应的 OpenAL 声道配置枚举。
	 *
	 * @param format 声场格式
	 * @return ALC 枚举值
	 */
	private static int channelsEnum(final AppleBedFormat format) {
		return format.ambisonic() ? SOFTLoopbackBformat.ALC_BFORMAT3D_SOFT : SOFTLoopback.ALC_7POINT1_SOFT;
	}

	/**
	 * 判断设备是否为当前的回环设备。
	 *
	 * @param device 设备句柄
	 * @return 是回环设备时返回 true
	 */
	public boolean isLoopbackDevice(final long device) {
		return device != 0L && device == this.loopbackDevice;
	}

	/**
	 * 构建回环设备的上下文属性：输出格式、关闭 HRTF（双耳渲染交给 Apple）、输出限幅与两个辅助发送通道。
	 *
	 * @param device 回环设备
	 * @param stack 内存栈
	 * @return 属性数组
	 */
	public IntBuffer buildLoopbackAttributes(final long device, final MemoryStack stack) {
		final AppleBedFormat format = this.activeFormat;
		final IntBuffer attributes = stack.callocInt(24);
		attributes.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(channelsEnum(format));
		attributes.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_FLOAT_SOFT);
		attributes.put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE);
		if (format.ambisonic()) {
			attributes.put(SOFTLoopbackBformat.ALC_AMBISONIC_LAYOUT_SOFT).put(SOFTLoopbackBformat.ALC_ACN_SOFT);
			attributes.put(SOFTLoopbackBformat.ALC_AMBISONIC_SCALING_SOFT).put(SOFTLoopbackBformat.ALC_SN3D_SOFT);
			attributes.put(SOFTLoopbackBformat.ALC_AMBISONIC_ORDER_SOFT).put(format.ambisonicOrder());
		}
		if (ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF")) {
			attributes.put(SOFTHRTF.ALC_HRTF_SOFT).put(ALC10.ALC_FALSE);
		}
		attributes.put(SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT).put(ALC10.ALC_TRUE);
		if (ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
			attributes.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(2);
		}
		attributes.put(0);
		ALC10.alcGetError(device);
		return attributes.flip();
	}

	/**
	 * 按当前设备列表确定目标输出设备，并返回原版应报告的设备名称，使原版的设备切换检测照常工作。
	 *
	 * <p>与原版打开真实设备时的回退规则一致：用户选择的设备当前不存在时改用系统默认设备，
	 * 等它重新出现时原版会检测到“首选设备可用”并重新加载声音引擎，届时再让助手切换过去。
	 *
	 * @param allDevices 当前全部设备名称
	 * @param defaultDevice 系统默认设备名称，可为 null
	 * @return 设备名称
	 */
	public String resolveTargetDevice(final List<String> allDevices, final String defaultDevice) {
		if (this.explicitDevice && !allDevices.isEmpty() && !allDevices.contains(this.targetDeviceName)) {
			this.explicitDevice = false;
			this.targetDeviceName = defaultDevice == null ? "" : defaultDevice;
		}
		return this.targetDeviceName;
	}

	/**
	 * OpenAL 上下文创建完成：开始向助手推送声场。
	 *
	 * @param device 设备句柄
	 */
	public void onContextReady(final long device) {
		final AppleAudioBridge current = this.bridge;
		if (!this.isLoopbackDevice(device) || current == null) {
			return;
		}
		final AppleBedFormat format = this.activeFormat;
		final AppleAudioBridge.FrameSource source = (target, frames) -> SOFTLoopback.alcRenderSamplesSOFT(device, target, frames);
		current.attach(new AppleAudioBridge.Stream(source, SAMPLE_RATE, format.channels(), AppleBridgeProtocol.layoutFor(format.ambisonic()),
			this.explicitDevice ? this.targetDeviceName : ""));
		this.streamStartedNanos = System.nanoTime();
		this.engineErrorSinceNanos = 0L;
	}

	/**
	 * OpenAL 上下文即将销毁：停止渲染（返回后不会再访问该设备）。
	 */
	public void onLibraryCleanup() {
		if (this.loopbackDevice == 0L) {
			return;
		}
		final AppleAudioBridge current = this.bridge;
		if (current != null) {
			current.detach();
		}
		this.loopbackDevice = 0L;
	}

	/**
	 * 当前是否正在使用回环设备（Apple 渲染生效中）。
	 *
	 * @return 使用中返回 true
	 */
	public boolean active() {
		return this.loopbackDevice != 0L;
	}

	// ===================== 回退监控（主线程） =====================

	/**
	 * 每个客户端刻调用：检测助手启动失败、长时间未连接或渲染引擎持续出错，必要时回退到内置渲染器。
	 */
	public void tick() {
		final AppleAudioBridge current = this.bridge;
		if (current == null) {
			return;
		}
		final MacHelperLauncher launcher = HeadTrackingManager.get().helper();
		final AppleBridgeProtocol.Hello greeting = current.hello();
		if (greeting != null && current.connected()) {
			// 记录助手进程号，重启或退出时可以结束它（只渲染声场时助手不发送 SAHT 数据包，无法从那里得知进程号）。
			launcher.setHelperPid(greeting.helperPid());
		}
		if (!this.active() || this.fallbackLatched) {
			return;
		}
		final long now = System.nanoTime();
		if (current.connected()) {
			this.lastConnectedNanos = now;
		}
		if (MacHelperLauncher.isMacOs()) {
			switch (launcher.state()) {
				case FAILED, NOT_FOUND, UNSUPPORTED_OS -> {
					final String error = launcher.lastError();
					this.latchAndReload(Component.translatable("spatialaudio.apple_fallback.helper", error.isEmpty() ? "-" : error));
					return;
				}
				case PREPARING, COMPILING -> {
					// 首次解压或现场编译可能需要较长时间，期间不计入连接超时。
					this.lastConnectedNanos = now;
					return;
				}
				default -> {
				}
			}
		}
		final long since = Math.max(this.streamStartedNanos, this.lastConnectedNanos);
		if (!current.connected() && (now - since) / 1.0e9 > CONNECT_TIMEOUT_SECONDS) {
			this.latchAndReload(Component.translatable("spatialaudio.apple_fallback.connect_timeout", (int) CONNECT_TIMEOUT_SECONDS));
			return;
		}
		final AppleBridgeProtocol.Status helperStatus = current.status();
		if (helperStatus.has(AppleBridgeProtocol.STATUS_ENGINE_ERROR)) {
			if (this.engineErrorSinceNanos == 0L) {
				this.engineErrorSinceNanos = now;
			} else if ((now - this.engineErrorSinceNanos) / 1.0e9 > ENGINE_ERROR_TIMEOUT_SECONDS) {
				this.latchAndReload(Component.translatable("spatialaudio.apple_fallback.engine",
					helperStatus.error().isEmpty() ? "-" : helperStatus.error()));
			}
		} else {
			this.engineErrorSinceNanos = 0L;
		}
	}

	/**
	 * 记录回退原因（不重新加载），并在游戏中提示玩家。
	 *
	 * @param reason 原因
	 */
	private void latch(final Component reason) {
		this.fallbackLatched = true;
		this.fallbackReason = reason;
		SpatialAudio.LOGGER.warn("Apple 空间音频不可用，改用模组内置渲染：{}", reason.getString());
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			// 进入世界前发生的回退由进入世界时的设置提示统一告知；游戏中途发生的回退立即在聊天栏提示。
			minecraft.execute(() -> {
				if (minecraft.player != null) {
					minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.apple_fallback", reason)
						.withStyle(ChatFormatting.YELLOW));
				}
			});
		}
	}

	/**
	 * 记录回退原因并重新加载声音引擎，使原版重新打开真实设备。
	 *
	 * @param reason 原因
	 */
	private void latchAndReload(final Component reason) {
		this.latch(reason);
		AudioSystem.get().reloadSoundEngine();
	}

	// ===================== 状态查询 =====================

	/**
	 * 计算当前渲染方式的状态。
	 *
	 * @param config 当前配置
	 * @return 状态
	 */
	public AppleRendererStatus status(final SpatialAudioConfig config) {
		if (!config.enabled || config.renderer == SpatialRenderer.BUILT_IN) {
			return AppleRendererStatus.BUILT_IN;
		}
		if (config.renderer == SpatialRenderer.APPLE_SYSTEM_STEREO) {
			return AppleRendererStatus.SYSTEM_STEREO;
		}
		if (!platformSupported()) {
			return AppleRendererStatus.UNSUPPORTED_OS;
		}
		if (this.fallbackLatched) {
			return AppleRendererStatus.FALLBACK;
		}
		final AppleAudioBridge current = this.bridge;
		if (current == null || !current.connected()) {
			return AppleRendererStatus.STARTING;
		}
		final AppleBridgeProtocol.Status helperStatus = current.status();
		if (helperStatus.has(AppleBridgeProtocol.STATUS_ENGINE_ERROR)) {
			return AppleRendererStatus.ENGINE_ERROR;
		}
		if (this.active() && helperStatus.has(AppleBridgeProtocol.STATUS_ENGINE_RUNNING)
			&& helperStatus.generation() == current.generation()) {
			return AppleRendererStatus.ACTIVE;
		}
		return AppleRendererStatus.CONNECTING_ENGINE;
	}

	/**
	 * 回退原因。
	 *
	 * @return 原因；未回退时为 null
	 */
	public Component fallbackReason() {
		return this.fallbackLatched ? this.fallbackReason : null;
	}

	/**
	 * 当前声场格式（回环设备使用中的格式，否则为配置中的格式）。
	 *
	 * @return 声场格式
	 */
	public AppleBedFormat format() {
		return this.active() ? this.activeFormat : this.bedFormat;
	}

	/**
	 * 助手最近一次上报的状态。
	 *
	 * @return 状态；未连接时为占位值
	 */
	public AppleBridgeProtocol.Status helperStatus() {
		final AppleAudioBridge current = this.bridge;
		return current == null ? AppleBridgeProtocol.Status.NONE : current.status();
	}

	/**
	 * 助手的问候信息（能力位、系统版本）。
	 *
	 * @return 问候；未连接过时为 null
	 */
	public AppleBridgeProtocol.Hello helperHello() {
		final AppleAudioBridge current = this.bridge;
		return current == null ? null : current.hello();
	}

	/**
	 * 估计的总延迟：助手环形缓冲中实际排队的音频 + 助手报告的输出延迟（不含蓝牙传输）。
	 * 助手会按音频硬件每次拉取的帧数自动加大缓冲，因此优先使用它上报的实际缓冲量；尚未收到状态时使用配置值。
	 *
	 * @return 毫秒
	 */
	public int estimatedLatencyMs() {
		final AppleBridgeProtocol.Status status = this.helperStatus();
		final int queuedMs = status.has(AppleBridgeProtocol.STATUS_ENGINE_RUNNING)
			? Math.round(status.bufferedFrames() * 1000.0f / SAMPLE_RATE)
			: this.bufferMs;
		return queuedMs + Math.round(status.outputLatency() * 1000.0f);
	}

	/**
	 * Apple 自动头部追踪是否生效（否则由本模组的头部姿态驱动 Apple 渲染器的听者朝向）。
	 *
	 * @return 生效时返回 true
	 */
	public boolean autoHeadTrackingActive() {
		return this.helperStatus().has(AppleBridgeProtocol.STATUS_AUTO_HEAD_TRACKING_ACTIVE);
	}

	/**
	 * 最近一次交给 Apple 渲染器的头部姿态（界面显示用）。
	 *
	 * @return 头部旋转
	 */
	public Quat displayHead() {
		final AppleAudioBridge current = this.bridge;
		return current == null ? Quat.IDENTITY : current.lastHead();
	}

	/**
	 * 助手启动参数。
	 *
	 * @param port 音频桥端口
	 * @param token 连接令牌
	 */
	public record HelperLink(int port, String token) {
	}
}
