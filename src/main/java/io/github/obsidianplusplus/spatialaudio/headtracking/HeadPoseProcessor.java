package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.config.RecenterMode;
import io.github.obsidianplusplus.spatialaudio.math.OneEuroFilter;
import io.github.obsidianplusplus.spatialaudio.math.Quat;

/**
 * 头部姿态处理管线：轴向校准 → One Euro 滤波 → 回中/漂移校正 → 延迟预测 → 轴向开关与强度。
 *
 * <p>数据流：
 * <ol>
 *   <li>接收线程调用 {@link #onSample(PoseSample)} 写入新样本，完成滤波、角速度估计与漂移校正；</li>
 *   <li>音频线程调用 {@link #currentRelative(long)} 读取“相对于屏幕正前方”的头部旋转，并按预测时长外推。</li>
 * </ol>
 * 两个线程通过对象锁同步，单次计算只有几十次浮点运算，不会造成阻塞。
 */
public final class HeadPoseProcessor {
	/** 超过该时长没有新样本即视为追踪中断（纳秒）。 */
	private static final long STALE_NANOS = 500_000_000L;

	/** 两个样本间隔超过该时长视为“重新连接”（纳秒）。 */
	private static final long RECONNECT_NANOS = 2_000_000_000L;

	/** 漂移校正仅在偏航小于该角度时生效（弧度，约 25°）。 */
	private static final double DRIFT_YAW_LIMIT = Math.toRadians(25.0);

	/** 漂移校正要求的“静止”角速度上限（弧度/秒）。 */
	private static final double DRIFT_SPEED_LIMIT = 0.2;

	/** 漂移校正要求的连续静止时长（秒）。 */
	private static final double DRIFT_STILL_SECONDS = 1.0;

	/** 追踪中断后姿态回归正前方的时间常数（秒）。 */
	private static final double FADE_SECONDS = 0.35;

	/** 预测外推的最长时间（秒），过长的外推会放大噪声。 */
	private static final double MAX_PREDICTION_SECONDS = 0.15;

	/** 角速度估计的平滑时间常数（秒）。 */
	private static final double RATE_SMOOTHING_SECONDS = 0.05;

	/**
	 * 处理参数，由配置生成。
	 *
	 * @param strength 旋转强度（0~1）
	 * @param trackYaw 是否追踪偏航
	 * @param trackPitch 是否追踪俯仰
	 * @param trackRoll 是否追踪横滚
	 * @param invertYaw 偏航取反
	 * @param invertPitch 俯仰取反
	 * @param invertRoll 横滚取反
	 * @param recenterMode 回中方式
	 * @param autoRecenterOnConnect 重新连接时自动回中
	 * @param driftCorrection 是否启用漂移校正
	 * @param driftSeconds 漂移校正时间常数（秒）
	 * @param predictionSeconds 预测时长（秒）
	 * @param minCutoff One Euro 最小截止频率
	 * @param beta One Euro 速度系数
	 */
	public record Settings(
		double strength,
		boolean trackYaw,
		boolean trackPitch,
		boolean trackRoll,
		boolean invertYaw,
		boolean invertPitch,
		boolean invertRoll,
		RecenterMode recenterMode,
		boolean autoRecenterOnConnect,
		boolean driftCorrection,
		double driftSeconds,
		double predictionSeconds,
		double minCutoff,
		double beta
	) {
		/** 默认参数。 */
		public static final Settings DEFAULT = new Settings(1.0, true, true, true, false, false, false,
			RecenterMode.FULL, true, true, 25.0, 0.035, 1.2, 0.35);
	}

	/** 当前参数。 */
	private Settings settings = Settings.DEFAULT;

	/** 自适应滤波器。 */
	private final OneEuroFilter filter = new OneEuroFilter(Settings.DEFAULT.minCutoff(), Settings.DEFAULT.beta());

	/** 回中参考姿态（追踪器参考系中“正对屏幕”的头部姿态）。 */
	private Quat reference = Quat.IDENTITY;

	/** 最近一次滤波后的姿态。 */
	private Quat filtered;

	/** 最近一次的头部角速度（头部坐标系，弧度/秒）。 */
	private double[] angularVelocity = new double[3];

	/** 最近一次样本的传感器时间（秒）。 */
	private double lastSensorTime = Double.NaN;

	/** 最近一次样本的接收时刻（纳秒）。 */
	private long lastReceivedNanos;

	/** 是否已收到过样本。 */
	private boolean hasSample;

	/** 下一个样本到来时是否执行回中。 */
	private boolean pendingRecenter = true;

	/** 连续静止时长（秒），用于漂移校正。 */
	private double stillSeconds;

	/** 最近一次输出（用于追踪中断时平滑回归）。 */
	private Quat lastOutput = Quat.IDENTITY;

	/** 最近一次输出的计算时刻（纳秒）。 */
	private long lastOutputNanos;

	/** 最近一次样本的来源。 */
	private PoseSample.Origin lastOrigin;

	/** 最近一次样本的平移量。 */
	private double[] lastTranslation;

	/** 采样率估计（Hz）。 */
	private double sampleRate;

	/**
	 * 更新处理参数。
	 *
	 * @param newSettings 新参数
	 */
	public synchronized void setSettings(final Settings newSettings) {
		this.settings = newSettings;
		this.filter.setParameters(newSettings.minCutoff(), newSettings.beta());
	}

	/**
	 * 请求回中：下一帧把当前头部姿态视为正对屏幕。
	 */
	public synchronized void recenter() {
		if (this.filtered != null) {
			this.applyRecenter(this.filtered);
		} else {
			this.pendingRecenter = true;
		}
	}

	/**
	 * 清空全部状态（切换数据源或重启追踪时调用）。
	 */
	public synchronized void reset() {
		this.filter.reset();
		this.filtered = null;
		this.reference = Quat.IDENTITY;
		this.angularVelocity = new double[3];
		this.lastSensorTime = Double.NaN;
		this.hasSample = false;
		this.pendingRecenter = true;
		this.stillSeconds = 0.0;
		this.lastOrigin = null;
		this.lastTranslation = null;
		this.sampleRate = 0.0;
	}

	/**
	 * 输入新样本。
	 *
	 * @param sample 姿态样本
	 */
	public synchronized void onSample(final PoseSample sample) {
		final Quat raw = this.calibrate(sample.rotation());
		final long gapNanos = this.hasSample ? sample.receivedNanos() - this.lastReceivedNanos : Long.MAX_VALUE;
		final boolean reconnected = gapNanos > RECONNECT_NANOS || this.lastOrigin != sample.origin();

		// 计算时间间隔：优先使用传感器时间戳，异常时退回到本机接收时间差。
		double dt = Double.NaN;
		if (Double.isFinite(sample.sensorTime()) && Double.isFinite(this.lastSensorTime)) {
			dt = sample.sensorTime() - this.lastSensorTime;
		}
		if (!(dt > 0.0 && dt < 0.5)) {
			dt = reconnected ? 0.0 : Math.min(0.5, gapNanos / 1.0e9);
		}

		if (reconnected) {
			this.filter.reset();
			this.angularVelocity = new double[3];
			this.stillSeconds = 0.0;
			if (this.settings.autoRecenterOnConnect() || !this.hasSample) {
				this.pendingRecenter = true;
			}
		}

		final Quat previous = this.filtered;
		final Quat current = this.filter.filter(raw, dt);
		this.updateAngularVelocity(sample, previous, current, dt);
		this.filtered = current;

		if (this.pendingRecenter) {
			this.applyRecenter(current);
			this.pendingRecenter = false;
		}
		if (dt > 0.0) {
			this.applyDriftCorrection(current, dt);
			final double instantRate = 1.0 / dt;
			this.sampleRate = this.sampleRate <= 0.0 ? instantRate : this.sampleRate + 0.05 * (instantRate - this.sampleRate);
		}

		this.lastSensorTime = sample.sensorTime();
		this.lastReceivedNanos = sample.receivedNanos();
		this.lastOrigin = sample.origin();
		this.lastTranslation = sample.translation();
		this.hasSample = true;
	}

	/**
	 * 计算当前时刻相对于屏幕正前方的头部旋转（含延迟预测）。
	 *
	 * @param nowNanos 当前时刻（{@link System#nanoTime()}）
	 * @return 头部相对旋转；无数据时平滑回归单位旋转
	 */
	public synchronized Quat currentRelative(final long nowNanos) {
		final double elapsedSinceOutput = this.lastOutputNanos == 0L ? 0.0 : Math.max(0.0, (nowNanos - this.lastOutputNanos) / 1.0e9);
		this.lastOutputNanos = nowNanos;

		if (!this.isFresh(nowNanos)) {
			// 追踪中断：指数回归正前方，避免声场突然跳变。
			final double t = 1.0 - Math.exp(-elapsedSinceOutput / FADE_SECONDS);
			this.lastOutput = this.lastOutput.slerp(Quat.IDENTITY, t);
			return this.lastOutput;
		}

		// 预测：q(t+Δ) = q(t) ⊗ exp(ω·Δ)，ω 为头部坐标系下的角速度。
		final double latency = Math.max(0.0, (nowNanos - this.lastReceivedNanos) / 1.0e9);
		final double horizon = Math.min(MAX_PREDICTION_SECONDS, this.settings.predictionSeconds() + latency);
		final Quat predicted = this.filtered.multiply(Quat.exp(
			this.angularVelocity[0] * horizon,
			this.angularVelocity[1] * horizon,
			this.angularVelocity[2] * horizon
		)).normalize();

		final Quat relative = this.reference.conjugate().multiply(predicted).normalize();
		this.lastOutput = this.shape(relative);
		return this.lastOutput;
	}

	/**
	 * 数据是否新鲜（最近 0.5 秒内收到过样本）。
	 *
	 * @param nowNanos 当前时刻
	 * @return 新鲜时返回 true
	 */
	public synchronized boolean isFresh(final long nowNanos) {
		return this.hasSample && this.filtered != null && nowNanos - this.lastReceivedNanos < STALE_NANOS;
	}

	/**
	 * 最近一次样本的来源。
	 *
	 * @return 来源；尚无数据时为 null
	 */
	public synchronized PoseSample.Origin lastOrigin() {
		return this.lastOrigin;
	}

	/**
	 * 最近一次样本携带的平移量。
	 *
	 * @return 平移（米），没有时为 null
	 */
	public synchronized double[] lastTranslation() {
		return this.lastTranslation;
	}

	/**
	 * 估计的采样率。
	 *
	 * @return 采样率（Hz）
	 */
	public synchronized double sampleRate() {
		return this.sampleRate;
	}

	/**
	 * 最近一次接收样本的时刻。
	 *
	 * @return 纳秒时间戳；尚无数据时为 0
	 */
	public synchronized long lastReceivedNanos() {
		return this.hasSample ? this.lastReceivedNanos : 0L;
	}

	/**
	 * 应用轴向反转校准。
	 *
	 * @param raw 原始旋转
	 * @return 校准后的旋转
	 */
	private Quat calibrate(final Quat raw) {
		final Quat normalized = raw.normalize();
		if (!this.settings.invertYaw() && !this.settings.invertPitch() && !this.settings.invertRoll()) {
			return normalized;
		}
		final double[] euler = normalized.toEulerZXY();
		return Quat.fromEulerZXY(
			this.settings.invertYaw() ? -euler[0] : euler[0],
			this.settings.invertPitch() ? -euler[1] : euler[1],
			this.settings.invertRoll() ? -euler[2] : euler[2]
		);
	}

	/**
	 * 更新头部角速度估计：优先使用陀螺仪读数，否则用相邻两帧的旋转差估计。
	 *
	 * @param sample 当前样本
	 * @param previous 上一帧滤波结果
	 * @param current 当前帧滤波结果
	 * @param dt 时间间隔（秒）
	 */
	private void updateAngularVelocity(final PoseSample sample, final Quat previous, final Quat current, final double dt) {
		double[] measured = null;
		final double[] rate = sample.rotationRate();
		if (rate != null && Double.isFinite(rate[0]) && Double.isFinite(rate[1]) && Double.isFinite(rate[2])
			&& !this.settings.invertYaw() && !this.settings.invertPitch() && !this.settings.invertRoll()) {
			measured = rate.clone();
		} else if (previous != null && dt > 0.0) {
			final double[] delta = previous.conjugate().multiply(current).log();
			measured = new double[] {delta[0] / dt, delta[1] / dt, delta[2] / dt};
		}
		if (measured == null) {
			return;
		}
		final double alpha = dt > 0.0 ? 1.0 - Math.exp(-dt / RATE_SMOOTHING_SECONDS) : 1.0;
		for (int i = 0; i < 3; i++) {
			this.angularVelocity[i] += alpha * (measured[i] - this.angularVelocity[i]);
		}
	}

	/**
	 * 执行回中。
	 *
	 * @param pose 当前头部姿态
	 */
	private void applyRecenter(final Quat pose) {
		if (this.settings.recenterMode() == RecenterMode.YAW_ONLY) {
			this.reference = Quat.fromAxisAngle(0.0, 0.0, 1.0, pose.yaw());
		} else {
			this.reference = pose;
		}
		this.stillSeconds = 0.0;
	}

	/**
	 * 偏航漂移校正：头部长时间基本正对屏幕且静止时，缓慢把参考方向拉向当前方向，抵消陀螺仪累积漂移。
	 *
	 * @param pose 当前头部姿态
	 * @param dt 时间间隔（秒）
	 */
	private void applyDriftCorrection(final Quat pose, final double dt) {
		if (!this.settings.driftCorrection()) {
			this.stillSeconds = 0.0;
			return;
		}
		final double yaw = this.reference.conjugate().multiply(pose).yaw();
		if (Math.abs(yaw) < DRIFT_YAW_LIMIT && this.filter.speed() < DRIFT_SPEED_LIMIT) {
			this.stillSeconds += dt;
		} else {
			this.stillSeconds = 0.0;
		}
		if (this.stillSeconds > DRIFT_STILL_SECONDS) {
			final double k = Math.min(1.0, dt / Math.max(1.0, this.settings.driftSeconds()));
			this.reference = this.reference.multiply(Quat.fromAxisAngle(0.0, 0.0, 1.0, yaw * k)).normalize();
		}
	}

	/**
	 * 按轴向开关与强度整形输出旋转。
	 *
	 * @param relative 相对旋转
	 * @return 整形后的旋转
	 */
	private Quat shape(final Quat relative) {
		final Settings s = this.settings;
		if (s.trackYaw() && s.trackPitch() && s.trackRoll() && s.strength() >= 0.999) {
			return relative;
		}
		final double[] euler = relative.toEulerZXY();
		return Quat.fromEulerZXY(
			s.trackYaw() ? euler[0] * s.strength() : 0.0,
			s.trackPitch() ? euler[1] * s.strength() : 0.0,
			s.trackRoll() ? euler[2] * s.strength() : 0.0
		);
	}
}
