package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * EAX（EFX 扩展）混响参数集合，字段含义与取值范围与 OpenAL EFX 规范一致。
 *
 * @param density 回声密度（0~1）
 * @param diffusion 扩散度（0~1）
 * @param gain 混响整体增益（0~1）
 * @param gainHF 高频增益（0~1）
 * @param gainLF 低频增益（0~1）
 * @param decayTime 衰减时间 RT60（0.1~20 秒）
 * @param decayHFRatio 高频衰减比（0.1~2）
 * @param decayLFRatio 低频衰减比（0.1~2）
 * @param reflectionsGain 早期反射增益（0~3.16）
 * @param reflectionsDelay 早期反射延迟（0~0.3 秒）
 * @param lateReverbGain 后期混响增益（0~10）
 * @param lateReverbDelay 后期混响延迟（0~0.1 秒）
 * @param echoTime 混响内调制回声时间（0.075~0.25 秒）
 * @param echoDepth 混响内调制回声深度（0~1）
 * @param modulationTime 调制时间（0.004~4 秒）
 * @param modulationDepth 调制深度（0~1）
 * @param airAbsorptionGainHF 空气吸收高频增益（0.892~1）
 * @param hfReference 高频参考频率（1000~20000 Hz）
 * @param lfReference 低频参考频率（20~1000 Hz）
 */
public record ReverbParams(
	float density,
	float diffusion,
	float gain,
	float gainHF,
	float gainLF,
	float decayTime,
	float decayHFRatio,
	float decayLFRatio,
	float reflectionsGain,
	float reflectionsDelay,
	float lateReverbGain,
	float lateReverbDelay,
	float echoTime,
	float echoDepth,
	float modulationTime,
	float modulationDepth,
	float airAbsorptionGainHF,
	float hfReference,
	float lfReference
) {
	/** 完全干声（无混响），用于开阔地或关闭混响时。 */
	public static final ReverbParams DRY = new ReverbParams(
		1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.5f, 0.83f, 1.0f, 0.0f, 0.007f, 0.0f, 0.011f,
		0.25f, 0.0f, 0.25f, 0.0f, 0.994f, 5000.0f, 250.0f
	);

	/** 水下预设（参考 EFX 预设 UNDERWATER）。 */
	public static final ReverbParams UNDERWATER = new ReverbParams(
		0.3645f, 1.0f, 0.3162f, 0.01f, 1.0f, 1.49f, 0.1f, 1.0f, 0.5963f, 0.007f, 7.0795f, 0.011f,
		0.25f, 0.0f, 1.18f, 0.348f, 0.9943f, 5000.0f, 250.0f
	);

	/** 岩浆中（极端低沉、短促）。 */
	public static final ReverbParams LAVA = new ReverbParams(
		0.3f, 1.0f, 0.25f, 0.005f, 1.0f, 0.8f, 0.1f, 1.0f, 0.3f, 0.005f, 3.0f, 0.01f,
		0.25f, 0.0f, 0.5f, 0.2f, 0.994f, 5000.0f, 250.0f
	);

	/**
	 * 把全部字段限制在 EFX 规范允许的范围内，防止 OpenAL 报 AL_INVALID_VALUE。
	 *
	 * @return 限制后的新参数
	 */
	public ReverbParams clamped() {
		return new ReverbParams(
			clamp(this.density, 0.0f, 1.0f),
			clamp(this.diffusion, 0.0f, 1.0f),
			clamp(this.gain, 0.0f, 1.0f),
			clamp(this.gainHF, 0.0f, 1.0f),
			clamp(this.gainLF, 0.0f, 1.0f),
			clamp(this.decayTime, 0.1f, 20.0f),
			clamp(this.decayHFRatio, 0.1f, 2.0f),
			clamp(this.decayLFRatio, 0.1f, 2.0f),
			clamp(this.reflectionsGain, 0.0f, 3.16f),
			clamp(this.reflectionsDelay, 0.0f, 0.3f),
			clamp(this.lateReverbGain, 0.0f, 10.0f),
			clamp(this.lateReverbDelay, 0.0f, 0.1f),
			clamp(this.echoTime, 0.075f, 0.25f),
			clamp(this.echoDepth, 0.0f, 1.0f),
			clamp(this.modulationTime, 0.004f, 4.0f),
			clamp(this.modulationDepth, 0.0f, 1.0f),
			clamp(this.airAbsorptionGainHF, 0.892f, 1.0f),
			clamp(this.hfReference, 1000.0f, 20000.0f),
			clamp(this.lfReference, 20.0f, 1000.0f)
		);
	}

	/**
	 * 线性插值到目标参数。
	 *
	 * @param to 目标
	 * @param t 插值系数（0~1）
	 * @return 插值结果
	 */
	public ReverbParams lerp(final ReverbParams to, final float t) {
		return new ReverbParams(
			mix(this.density, to.density, t),
			mix(this.diffusion, to.diffusion, t),
			mix(this.gain, to.gain, t),
			mix(this.gainHF, to.gainHF, t),
			mix(this.gainLF, to.gainLF, t),
			mix(this.decayTime, to.decayTime, t),
			mix(this.decayHFRatio, to.decayHFRatio, t),
			mix(this.decayLFRatio, to.decayLFRatio, t),
			mix(this.reflectionsGain, to.reflectionsGain, t),
			mix(this.reflectionsDelay, to.reflectionsDelay, t),
			mix(this.lateReverbGain, to.lateReverbGain, t),
			mix(this.lateReverbDelay, to.lateReverbDelay, t),
			mix(this.echoTime, to.echoTime, t),
			mix(this.echoDepth, to.echoDepth, t),
			mix(this.modulationTime, to.modulationTime, t),
			mix(this.modulationDepth, to.modulationDepth, t),
			mix(this.airAbsorptionGainHF, to.airAbsorptionGainHF, t),
			mix(this.hfReference, to.hfReference, t),
			mix(this.lfReference, to.lfReference, t)
		);
	}

	/**
	 * 判断与另一组参数是否有明显差异（用于减少不必要的 OpenAL 上传）。
	 *
	 * @param other 另一组参数
	 * @param epsilon 相对阈值
	 * @return 有差异时返回 true
	 */
	public boolean differsFrom(final ReverbParams other, final float epsilon) {
		return other == null
			|| differs(this.gain, other.gain, epsilon)
			|| differs(this.decayTime, other.decayTime, epsilon)
			|| differs(this.lateReverbGain, other.lateReverbGain, epsilon)
			|| differs(this.reflectionsGain, other.reflectionsGain, epsilon)
			|| differs(this.reflectionsDelay, other.reflectionsDelay, epsilon)
			|| differs(this.lateReverbDelay, other.lateReverbDelay, epsilon)
			|| differs(this.gainHF, other.gainHF, epsilon)
			|| differs(this.decayHFRatio, other.decayHFRatio, epsilon)
			|| differs(this.density, other.density, epsilon)
			|| differs(this.diffusion, other.diffusion, epsilon)
			|| differs(this.modulationDepth, other.modulationDepth, epsilon);
	}

	/**
	 * 按比例缩放早期反射与后期混响的增益（用于玩家调整混响强度）。
	 *
	 * @param scale 缩放倍率
	 * @return 新参数
	 */
	public ReverbParams withWetScale(final float scale) {
		return new ReverbParams(this.density, this.diffusion, this.gain, this.gainHF, this.gainLF, this.decayTime,
			this.decayHFRatio, this.decayLFRatio, this.reflectionsGain * scale, this.reflectionsDelay,
			this.lateReverbGain * scale, this.lateReverbDelay, this.echoTime, this.echoDepth, this.modulationTime,
			this.modulationDepth, this.airAbsorptionGainHF, this.hfReference, this.lfReference);
	}

	/**
	 * 线性插值。
	 *
	 * @param a 起点
	 * @param b 终点
	 * @param t 系数
	 * @return 插值
	 */
	private static float mix(final float a, final float b, final float t) {
		return a + (b - a) * t;
	}

	/**
	 * 相对差异判断。
	 *
	 * @param a 值一
	 * @param b 值二
	 * @param epsilon 阈值
	 * @return 差异超过阈值时返回 true
	 */
	private static boolean differs(final float a, final float b, final float epsilon) {
		return Math.abs(a - b) > epsilon * Math.max(1.0f, Math.max(Math.abs(a), Math.abs(b)));
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
