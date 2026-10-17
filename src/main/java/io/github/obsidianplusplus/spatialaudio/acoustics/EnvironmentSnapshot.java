package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 一次完整环境探测得到的听者周围空间特征。
 *
 * @param enclosure 封闭度：射线在探测距离内命中方块的比例（0 开阔 ~ 1 完全封闭）
 * @param meanFreePath 平均自由程：命中射线的平均距离（米 = 格）
 * @param absorption 有效吸声系数：命中表面的平均吸声系数，逃逸射线按 1 计入（0~1）
 * @param surfaceAbsorption 仅命中表面的平均吸声系数（0~1）
 * @param skyOpenness 向上射线逃逸到天空的比例（0~1）
 * @param rt60 由 Eyring 公式估算的混响时间（秒）
 * @param echoDistance 远处反射面的平均距离（米），无回声面时为 0
 * @param echoStrength 回声强度估计（0~1）
 */
public record EnvironmentSnapshot(
	float enclosure,
	float meanFreePath,
	float absorption,
	float surfaceAbsorption,
	float skyOpenness,
	float rt60,
	float echoDistance,
	float echoStrength
) {
	/** 开阔地默认值。 */
	public static final EnvironmentSnapshot OPEN = new EnvironmentSnapshot(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.1f, 0.0f, 0.0f);
}
