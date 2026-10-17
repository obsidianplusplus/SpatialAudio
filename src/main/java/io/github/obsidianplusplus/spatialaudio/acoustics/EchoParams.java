package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * EFX 回声效果参数，用于模拟山谷、峡谷等开阔地形中的远距离回声。
 *
 * @param delay 第一次回声延迟（0~0.207 秒）
 * @param lrDelay 第二次回声相对第一次的延迟（0~0.404 秒）
 * @param damping 高频阻尼（0~0.99）
 * @param feedback 反馈量，决定回声重复次数（0~1）
 * @param spread 左右声像展开（-1~1）
 * @param strength 回声强度（写入效果槽增益，0~1）
 */
public record EchoParams(float delay, float lrDelay, float damping, float feedback, float spread, float strength) {
	/** 无回声。 */
	public static final EchoParams NONE = new EchoParams(0.1f, 0.1f, 0.5f, 0.0f, -1.0f, 0.0f);

	/**
	 * 限制到 EFX 规范范围。
	 *
	 * @return 限制后的新参数
	 */
	public EchoParams clamped() {
		return new EchoParams(
			clamp(this.delay, 0.0f, 0.207f),
			clamp(this.lrDelay, 0.0f, 0.404f),
			clamp(this.damping, 0.0f, 0.99f),
			clamp(this.feedback, 0.0f, 1.0f),
			clamp(this.spread, -1.0f, 1.0f),
			clamp(this.strength, 0.0f, 1.0f)
		);
	}

	/**
	 * 线性插值。
	 *
	 * @param to 目标
	 * @param t 系数
	 * @return 插值结果
	 */
	public EchoParams lerp(final EchoParams to, final float t) {
		return new EchoParams(
			this.delay + (to.delay - this.delay) * t,
			this.lrDelay + (to.lrDelay - this.lrDelay) * t,
			this.damping + (to.damping - this.damping) * t,
			this.feedback + (to.feedback - this.feedback) * t,
			this.spread + (to.spread - this.spread) * t,
			this.strength + (to.strength - this.strength) * t
		);
	}

	/**
	 * 判断是否有明显差异。
	 *
	 * @param other 另一组参数
	 * @return 有差异时返回 true
	 */
	public boolean differsFrom(final EchoParams other) {
		return other == null
			|| Math.abs(this.delay - other.delay) > 0.002f
			|| Math.abs(this.lrDelay - other.lrDelay) > 0.002f
			|| Math.abs(this.feedback - other.feedback) > 0.01f
			|| Math.abs(this.damping - other.damping) > 0.01f
			|| Math.abs(this.strength - other.strength) > 0.005f;
	}

	/**
	 * 限制范围，NaN 时取下限。
	 *
	 * @param value 值
	 * @param min 下限
	 * @param max 上限
	 * @return 限制后的值
	 */
	private static float clamp(final float value, final float min, final float max) {
		return Float.isFinite(value) ? Math.clamp(value, min, max) : min;
	}
}
