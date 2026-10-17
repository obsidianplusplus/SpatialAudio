package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 听者周围环境的增量式射线探测。
 *
 * <p>每个游戏刻只发射一部分射线，若干刻后完成一整轮，再汇总为 {@link EnvironmentSnapshot}。
 * 这样即使射线总数较多，也不会在单帧造成卡顿。每完成一轮，采样方向整体旋转一个黄金角，
 * 多轮之间互相错开，统计结果更稳定。
 *
 * <p>水平方向的射线会延伸到更远的“回声探测距离”，用于发现山谷、峡谷中的远处岩壁。
 */
public final class EnvironmentProbe {
	/** 视为“水平”射线的竖直分量上限。 */
	private static final double HORIZONTAL_LIMIT = 0.35;

	/** 视为“向上”射线的竖直分量下限。 */
	private static final double UPWARD_LIMIT = 0.5;

	/** 产生可感知回声所需的最小反射距离（格），对应约 100 毫秒往返时间。 */
	private static final double MIN_ECHO_DISTANCE = 18.0;

	/** 本轮累计：射线总数。 */
	private int rays;

	/** 本轮累计：在探测距离内命中的射线数。 */
	private int hits;

	/** 本轮累计：命中距离之和。 */
	private double distanceSum;

	/** 本轮累计：命中表面吸声系数之和。 */
	private double absorptionSum;

	/** 本轮累计：向上射线数。 */
	private int upRays;

	/** 本轮累计：向上射线中逃逸到天空的数量。 */
	private int upEscaped;

	/** 本轮累计：水平射线数。 */
	private int horizontalRays;

	/** 本轮累计：水平射线中形成回声的数量。 */
	private int echoHits;

	/** 本轮累计：回声反射距离之和。 */
	private double echoDistanceSum;

	/** 当前轮次已发射到的序号。 */
	private int cursor;

	/** 当前轮次的采样旋转。 */
	private double rotation;

	/** 最近一次完成的探测结果。 */
	private volatile EnvironmentSnapshot latest = EnvironmentSnapshot.OPEN;

	/**
	 * 最近一次完成的探测结果。
	 *
	 * @return 快照
	 */
	public EnvironmentSnapshot latest() {
		return this.latest;
	}

	/**
	 * 清空进行中的统计（切换世界时调用）。
	 */
	public void reset() {
		this.resetAccumulators();
		this.cursor = 0;
		this.latest = EnvironmentSnapshot.OPEN;
	}

	/**
	 * 发射一批射线。
	 *
	 * @param query 世界查询
	 * @param lx 听者 x
	 * @param ly 听者 y
	 * @param lz 听者 z
	 * @param batch 本次发射数量
	 * @param totalRays 每轮射线总数
	 * @param probeDistance 混响探测距离
	 * @param echoDistance 回声探测距离
	 * @return 本次调用是否完成了一整轮
	 */
	public boolean step(final BlockQuery query, final double lx, final double ly, final double lz,
			final int batch, final int totalRays, final double probeDistance, final double echoDistance) {
		boolean finished = false;
		for (int k = 0; k < batch; k++) {
			if (this.cursor >= totalRays) {
				this.finish(probeDistance);
				finished = true;
			}
			final double[] d = SphereSampler.direction(this.cursor, totalRays, this.rotation);
			this.cursor++;
			final boolean horizontal = Math.abs(d[1]) < HORIZONTAL_LIMIT;
			final double range = horizontal ? Math.max(probeDistance, echoDistance) : probeDistance;
			final OcclusionSolver.RayHit hit = OcclusionSolver.castToSurface(query, lx, ly, lz, d[0], d[1], d[2], range);
			this.rays++;
			if (hit.hit() && hit.distance() <= probeDistance) {
				this.hits++;
				this.distanceSum += hit.distance();
				this.absorptionSum += hit.absorption();
			}
			if (d[1] > UPWARD_LIMIT) {
				this.upRays++;
				if (!hit.hit() || hit.distance() > probeDistance) {
					this.upEscaped++;
				}
			}
			if (horizontal) {
				this.horizontalRays++;
				if (hit.hit() && hit.distance() >= MIN_ECHO_DISTANCE) {
					this.echoHits++;
					this.echoDistanceSum += hit.distance();
				}
			}
		}
		if (this.cursor >= totalRays) {
			this.finish(probeDistance);
			finished = true;
		}
		return finished;
	}

	/**
	 * 汇总一整轮的统计并开始新一轮。
	 *
	 * @param probeDistance 探测距离
	 */
	private void finish(final double probeDistance) {
		if (this.rays > 0) {
			this.latest = summarize(this.rays, this.hits, this.distanceSum, this.absorptionSum, this.upRays, this.upEscaped,
				this.horizontalRays, this.echoHits, this.echoDistanceSum, probeDistance);
		}
		this.resetAccumulators();
		this.cursor = 0;
		this.rotation += SphereSampler.GOLDEN_ANGLE;
	}

	/**
	 * 根据统计量计算环境快照（包含 Eyring 混响时间估算）。
	 *
	 * <p>Eyring 公式：{@code RT60 = 0.161·V / (−S·ln(1−ᾱ))}。对任意形状的空间有平均自由程
	 * {@code l = 4V/S}，代入得 {@code RT60 = 0.04025·l / (−ln(1−ᾱ))}。
	 * 其中逃逸到探测范围外的射线按吸声系数 1 计入，代表声能离开了这个空间。
	 *
	 * @param rays 射线总数
	 * @param hits 命中数
	 * @param distanceSum 命中距离和
	 * @param absorptionSum 命中吸声系数和
	 * @param upRays 向上射线数
	 * @param upEscaped 向上逃逸数
	 * @param horizontalRays 水平射线数
	 * @param echoHits 回声命中数
	 * @param echoDistanceSum 回声距离和
	 * @param probeDistance 探测距离
	 * @return 环境快照
	 */
	static EnvironmentSnapshot summarize(final int rays, final int hits, final double distanceSum, final double absorptionSum,
			final int upRays, final int upEscaped, final int horizontalRays, final int echoHits, final double echoDistanceSum,
			final double probeDistance) {
		final float enclosure = (float) hits / rays;
		final float meanFreePath = hits > 0 ? (float) (distanceSum / hits) : (float) probeDistance;
		final float surfaceAbsorption = hits > 0 ? (float) (absorptionSum / hits) : 0.0f;
		final double effectiveAbsorption = Math.clamp((absorptionSum + (rays - hits)) / rays, 0.01, 0.999);
		final double rt60 = 0.04025 * Math.max(0.5, meanFreePath) / -Math.log(1.0 - effectiveAbsorption);
		final float skyOpenness = upRays > 0 ? (float) upEscaped / upRays : 1.0f - enclosure;
		final float echoFraction = horizontalRays > 0 ? (float) echoHits / horizontalRays : 0.0f;
		final float echoDistance = echoHits > 0 ? (float) (echoDistanceSum / echoHits) : 0.0f;
		final float echoStrength = echoHits > 0 ? Math.clamp(echoFraction * skyOpenness, 0.0f, 1.0f) : 0.0f;
		return new EnvironmentSnapshot(
			enclosure,
			meanFreePath,
			(float) effectiveAbsorption,
			surfaceAbsorption,
			skyOpenness,
			(float) Math.clamp(rt60, 0.1, 20.0),
			echoDistance,
			echoStrength
		);
	}

	/**
	 * 清零累计量。
	 */
	private void resetAccumulators() {
		this.rays = 0;
		this.hits = 0;
		this.distanceSum = 0.0;
		this.absorptionSum = 0.0;
		this.upRays = 0;
		this.upEscaped = 0;
		this.horizontalRays = 0;
		this.echoHits = 0;
		this.echoDistanceSum = 0.0;
	}
}
