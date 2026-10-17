package io.github.obsidianplusplus.spatialaudio.math;

/**
 * 针对旋转（四元数）的 One Euro 自适应低通滤波器。
 *
 * <p>算法来自 Casiez、Roussel 与 Vogel 在 CHI 2012 发表的论文
 * 《1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems》。
 * 其核心思想是：头部静止时使用较低的截止频率以消除传感器抖动；
 * 快速转头时根据角速度提高截止频率，以降低延迟、保持跟手。
 *
 * <p>本实现把标量版本推广到旋转：用球面线性插值代替线性插值，用相邻样本的夹角除以时间间隔作为速度。
 * 非线程安全，应只在接收数据的线程中调用。
 */
public final class OneEuroFilter {
	/** 速度估计所用的固定截止频率（Hz）。 */
	private static final double DERIVATIVE_CUTOFF = 1.0;

	/** 最小截止频率（Hz）。 */
	private double minCutoff;

	/** 速度系数。 */
	private double beta;

	/** 上一次的滤波输出。 */
	private Quat filtered;

	/** 上一次的原始输入。 */
	private Quat lastRaw;

	/** 滤波后的角速度（弧度/秒）。 */
	private double filteredSpeed;

	/**
	 * 创建滤波器。
	 *
	 * @param minCutoff 最小截止频率（Hz）
	 * @param beta 速度系数
	 */
	public OneEuroFilter(final double minCutoff, final double beta) {
		this.minCutoff = minCutoff;
		this.beta = beta;
	}

	/**
	 * 更新滤波参数（不清空状态）。
	 *
	 * @param minCutoff 最小截止频率（Hz）
	 * @param beta 速度系数
	 */
	public void setParameters(final double minCutoff, final double beta) {
		this.minCutoff = minCutoff;
		this.beta = beta;
	}

	/**
	 * 清空滤波状态，下一个样本将直接作为输出。
	 */
	public void reset() {
		this.filtered = null;
		this.lastRaw = null;
		this.filteredSpeed = 0.0;
	}

	/**
	 * 输入一个新样本并返回滤波结果。
	 *
	 * @param raw 原始旋转
	 * @param dt 距上一个样本的时间间隔（秒）
	 * @return 滤波后的旋转
	 */
	public Quat filter(final Quat raw, final double dt) {
		if (this.filtered == null || !(dt > 0.0)) {
			this.filtered = raw;
			this.lastRaw = raw;
			this.filteredSpeed = 0.0;
			return raw;
		}
		// 1. 估计原始角速度，并用固定截止频率平滑。
		final double rawSpeed = this.lastRaw.conjugate().multiply(raw).angle() / dt;
		final double speedAlpha = smoothingFactor(dt, DERIVATIVE_CUTOFF);
		this.filteredSpeed += speedAlpha * (rawSpeed - this.filteredSpeed);
		// 2. 根据速度计算自适应截止频率，并对旋转做球面插值平滑。
		final double cutoff = this.minCutoff + this.beta * this.filteredSpeed;
		final double alpha = smoothingFactor(dt, cutoff);
		this.filtered = this.filtered.slerp(raw, alpha);
		this.lastRaw = raw;
		return this.filtered;
	}

	/**
	 * 当前滤波后的角速度。
	 *
	 * @return 角速度（弧度/秒）
	 */
	public double speed() {
		return this.filteredSpeed;
	}

	/**
	 * 一阶低通滤波器的平滑系数 α = 1 / (1 + τ/Δt)，其中 τ = 1 / (2π·fc)。
	 *
	 * @param dt 时间间隔（秒）
	 * @param cutoff 截止频率（Hz）
	 * @return 平滑系数（0~1）
	 */
	static double smoothingFactor(final double dt, final double cutoff) {
		final double tau = 1.0 / (2.0 * Math.PI * Math.max(cutoff, 1.0e-3));
		return 1.0 / (1.0 + tau / dt);
	}
}
