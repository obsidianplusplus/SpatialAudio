package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.config.HeadTrackingSource;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.net.SocketException;

/**
 * 头部追踪总管理器：负责 UDP 接收器、macOS 助手与姿态处理管线的生命周期，
 * 并向音频系统提供“当前头部相对屏幕的旋转”。
 *
 * <p>线程模型：
 * <ul>
 *   <li>UDP 接收线程：解析数据包并写入 {@link HeadPoseProcessor}；</li>
 *   <li>Minecraft 声音线程：读取 {@link #currentRotation()} 合成听者朝向；</li>
 *   <li>主线程：启动、停止、回中与界面查询。</li>
 * </ul>
 */
public final class HeadTrackingManager implements UdpPoseReceiver.Listener {
	/** 全局唯一实例。 */
	private static final HeadTrackingManager INSTANCE = new HeadTrackingManager();

	/** AirPods 数据视为“正在使用”的时长（纳秒），期间忽略 OpenTrack 数据避免互相干扰。 */
	private static final long AIRPODS_PRIORITY_NANOS = 500_000_000L;

	/** 助手状态包视为有效的时长（纳秒）。 */
	private static final long HELPER_STATUS_TIMEOUT_NANOS = 3_000_000_000L;

	/** 助手启动后多久仍无回应视为失败（秒）。 */
	private static final double HELPER_RESPONSE_TIMEOUT_SECONDS = 15.0;

	/** 姿态处理管线。 */
	private final HeadPoseProcessor processor = new HeadPoseProcessor();

	/** macOS 助手启动器。 */
	private final MacHelperLauncher helper = new MacHelperLauncher();

	/** UDP 接收器。 */
	private volatile UdpPoseReceiver receiver;

	/** 当前数据源配置。 */
	private volatile HeadTrackingSource source = HeadTrackingSource.DISABLED;

	/** 是否使用追踪器平移。 */
	private volatile boolean useTranslation;

	/** 平移缩放。 */
	private volatile double translationScale = 1.0;

	/** 配置的端口。 */
	private volatile int configuredPort;

	/** 最近一次 AirPods 姿态时刻（纳秒）。 */
	private volatile long lastAirPodsPoseNanos;

	/** 最近一次助手状态包时刻（纳秒）。 */
	private volatile long lastHelperStatusNanos;

	/** 最近一次助手状态码。 */
	private volatile int helperStatusCode = -1;

	/** 最近一次 CoreMotion 授权状态原始值。 */
	private volatile int helperAuthorization = -1;

	/** 是否因端口占用而改用了临时端口。 */
	private volatile boolean portFallback;

	private HeadTrackingManager() {
	}

	/**
	 * 获取全局实例。
	 *
	 * @return 实例
	 */
	public static HeadTrackingManager get() {
		return INSTANCE;
	}

	/**
	 * 按配置（重新）启动头部追踪。
	 *
	 * @param config 当前配置
	 */
	public synchronized void start(final SpatialAudioConfig config) {
		this.stop();
		this.applySettings(config);
		this.source = config.enabled ? config.trackingSource : HeadTrackingSource.DISABLED;
		this.configuredPort = config.udpPort;
		this.processor.reset();
		if (this.source == HeadTrackingSource.DISABLED) {
			return;
		}
		this.portFallback = false;
		try {
			this.receiver = new UdpPoseReceiver(config.udpPort, config.udpListenAllInterfaces, this);
		} catch (SocketException e) {
			SpatialAudio.LOGGER.warn("UDP 端口 {} 无法使用（{}），改用系统分配的临时端口", config.udpPort, e.getMessage());
			try {
				this.receiver = new UdpPoseReceiver(0, false, this);
				this.portFallback = true;
			} catch (SocketException fallbackError) {
				SpatialAudio.LOGGER.error("无法创建头部追踪 UDP 接收器", fallbackError);
				return;
			}
		}
		this.receiver.start();
		SpatialAudio.LOGGER.info("头部追踪已启动：数据源 {}，UDP 端口 {}", this.source, this.receiver.port());
		if (this.source.usesAirPodsHelper() && MacHelperLauncher.isMacOs()) {
			this.helper.startAsync(this.receiver.port());
		}
	}

	/**
	 * 停止头部追踪并结束助手。
	 */
	public synchronized void stop() {
		final UdpPoseReceiver current = this.receiver;
		this.receiver = null;
		if (current != null) {
			current.close();
		}
		this.helper.stop();
		this.lastAirPodsPoseNanos = 0L;
		this.lastHelperStatusNanos = 0L;
		this.helperStatusCode = -1;
		this.helperAuthorization = -1;
	}

	/**
	 * 仅更新处理参数（不重启接收器与助手）。
	 *
	 * @param config 当前配置
	 */
	public void applySettings(final SpatialAudioConfig config) {
		this.processor.setSettings(new HeadPoseProcessor.Settings(
			config.trackingStrength,
			config.trackYaw,
			config.trackPitch,
			config.trackRoll,
			config.invertYaw,
			config.invertPitch,
			config.invertRoll,
			config.recenterMode,
			config.autoRecenterOnConnect,
			config.driftCorrection,
			config.driftCorrectionSeconds,
			config.predictionMs / 1000.0,
			config.smoothingMinCutoff,
			config.smoothingBeta
		));
		this.useTranslation = config.useTrackerTranslation;
		this.translationScale = config.translationScale;
	}

	/**
	 * 判断配置变化是否需要重启追踪（数据源、端口或监听范围变化）。
	 *
	 * @param before 修改前
	 * @param after 修改后
	 * @return 需要重启时返回 true
	 */
	public static boolean needsRestart(final SpatialAudioConfig before, final SpatialAudioConfig after) {
		return before.enabled != after.enabled
			|| before.trackingSource != after.trackingSource
			|| before.udpPort != after.udpPort
			|| before.udpListenAllInterfaces != after.udpListenAllInterfaces;
	}

	/**
	 * 回中：把当前头部姿态设为正对屏幕。
	 */
	public void recenter() {
		this.processor.recenter();
	}

	/**
	 * 当前头部相对屏幕的旋转（已滤波、预测与整形）。
	 *
	 * @return 头部坐标系下的旋转；关闭时为单位旋转
	 */
	public Quat currentRotation() {
		if (this.source == HeadTrackingSource.DISABLED) {
			return Quat.IDENTITY;
		}
		return this.processor.currentRelative(System.nanoTime());
	}

	/**
	 * 当前头部平移（OpenAL 听者坐标系，米）。
	 *
	 * @return 平移；未启用或无数据时返回 null
	 */
	public double[] currentTranslation() {
		if (!this.useTranslation || this.source == HeadTrackingSource.DISABLED || !this.processor.isFresh(System.nanoTime())) {
			return null;
		}
		final double[] translation = this.processor.lastTranslation();
		if (translation == null) {
			return null;
		}
		final double scale = this.translationScale;
		return new double[] {translation[0] * scale, translation[1] * scale, translation[2] * scale};
	}

	/**
	 * 估计的数据采样率。
	 *
	 * @return Hz；无数据时为 0
	 */
	public double sampleRate() {
		return this.processor.isFresh(System.nanoTime()) ? this.processor.sampleRate() : 0.0;
	}

	/**
	 * 实际监听的 UDP 端口。
	 *
	 * @return 端口；未启动时为 -1
	 */
	public int boundPort() {
		final UdpPoseReceiver current = this.receiver;
		return current == null ? -1 : current.port();
	}

	/**
	 * 助手启动器（用于界面显示错误信息）。
	 *
	 * @return 启动器
	 */
	public MacHelperLauncher helper() {
		return this.helper;
	}

	/**
	 * 计算当前状态。
	 *
	 * @return 状态
	 */
	public TrackerStatus status() {
		if (this.source == HeadTrackingSource.DISABLED) {
			return TrackerStatus.DISABLED;
		}
		final long now = System.nanoTime();
		if (this.processor.isFresh(now)) {
			return this.processor.lastOrigin() == PoseSample.Origin.AIRPODS ? TrackerStatus.ACTIVE_AIRPODS : TrackerStatus.ACTIVE_OPENTRACK;
		}
		if (this.receiver == null) {
			return TrackerStatus.HELPER_FAILED;
		}
		if (this.source.usesAirPodsHelper() && MacHelperLauncher.isMacOs()) {
			final TrackerStatus helperStatus = this.helperStatus(now);
			if (helperStatus != null) {
				return helperStatus;
			}
		}
		if (this.portFallback && this.source.acceptsOpenTrack()) {
			return TrackerStatus.PORT_IN_USE;
		}
		return TrackerStatus.WAITING_FOR_DATA;
	}

	/**
	 * 根据助手启动器状态与助手上报的状态码推断状态。
	 *
	 * @param now 当前时刻
	 * @return 状态；无法判断时返回 null
	 */
	private TrackerStatus helperStatus(final long now) {
		switch (this.helper.state()) {
			case PREPARING:
				return TrackerStatus.HELPER_STARTING;
			case COMPILING:
				return TrackerStatus.HELPER_COMPILING;
			case NOT_FOUND:
				return TrackerStatus.HELPER_NOT_FOUND;
			case FAILED:
				return TrackerStatus.HELPER_FAILED;
			case UNSUPPORTED_OS:
				return TrackerStatus.UNSUPPORTED_OS;
			default:
				break;
		}
		if (now - this.lastHelperStatusNanos < HELPER_STATUS_TIMEOUT_NANOS) {
			return switch (this.helperStatusCode) {
				case SahtProtocol.STATUS_NOT_AVAILABLE -> TrackerStatus.NOT_AVAILABLE;
				case SahtProtocol.STATUS_NOT_AUTHORIZED -> TrackerStatus.NOT_AUTHORIZED;
				case SahtProtocol.STATUS_AWAITING_AUTHORIZATION -> TrackerStatus.AWAITING_AUTHORIZATION;
				case SahtProtocol.STATUS_ERROR -> TrackerStatus.HELPER_ERROR;
				case SahtProtocol.STATUS_STARTING -> TrackerStatus.HELPER_STARTING;
				default -> TrackerStatus.WAITING_FOR_HEADPHONES;
			};
		}
		if (this.helper.state() == MacHelperLauncher.State.LAUNCHED
			&& this.helper.secondsSinceLaunch(now) > HELPER_RESPONSE_TIMEOUT_SECONDS) {
			return TrackerStatus.HELPER_NO_RESPONSE;
		}
		if (this.helper.state() == MacHelperLauncher.State.LAUNCHED) {
			return TrackerStatus.HELPER_STARTING;
		}
		return null;
	}

	@Override
	public void onSahtPacket(final SahtProtocol.Packet packet, final long receivedNanos) {
		if (!this.source.usesAirPodsHelper()) {
			return;
		}
		this.helper.setHelperPid(packet.helperPid());
		this.lastHelperStatusNanos = receivedNanos;
		this.helperStatusCode = packet.status();
		this.helperAuthorization = packet.authorization();
		if (packet.hasPose()) {
			this.lastAirPodsPoseNanos = receivedNanos;
			this.processor.onSample(new PoseSample(
				PoseSample.Origin.AIRPODS,
				packet.rotation(),
				packet.timestamp(),
				receivedNanos,
				packet.hasRate() ? packet.rotationRate() : null,
				null
			));
		}
	}

	@Override
	public void onOpenTrackPacket(final OpenTrackProtocol.Pose pose, final long receivedNanos) {
		if (!this.source.acceptsOpenTrack()) {
			return;
		}
		// AirPods 数据优先：两者同时存在时忽略 OpenTrack，避免姿态来回跳变。
		if (receivedNanos - this.lastAirPodsPoseNanos < AIRPODS_PRIORITY_NANOS) {
			return;
		}
		this.processor.onSample(new PoseSample(
			PoseSample.Origin.OPENTRACK,
			pose.rotation(),
			Double.NaN,
			receivedNanos,
			null,
			pose.translation()
		));
	}

	/**
	 * CoreMotion 授权状态原始值（0 未决定、1 受限、2 拒绝、3 已授权）。
	 *
	 * @return 原始值；未知时为 -1
	 */
	public int helperAuthorization() {
		return this.helperAuthorization;
	}
}
