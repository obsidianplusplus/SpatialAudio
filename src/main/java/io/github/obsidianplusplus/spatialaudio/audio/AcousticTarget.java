package io.github.obsidianplusplus.spatialaudio.audio;

/**
 * 主线程为单个声源计算出的声学目标值，由声音线程平滑过渡后写入 OpenAL。
 *
 * @param directGain 直达声整体增益（遮挡造成的衰减，0~1）
 * @param directGainHF 直达声高频增益（遮挡造成的“闷声”，0~1）
 * @param redirectBlend 绕射重定向权重：0 表示从真实方向传来，1 表示完全从绕射方向传来
 * @param redirectX 绕射方向（从听者指向绕射点的单位向量）x
 * @param redirectY 绕射方向 y
 * @param redirectZ 绕射方向 z
 */
public record AcousticTarget(
	float directGain,
	float directGainHF,
	float redirectBlend,
	double redirectX,
	double redirectY,
	double redirectZ
) {
	/** 无遮挡、无重定向的默认目标。 */
	public static final AcousticTarget CLEAR = new AcousticTarget(1.0f, 1.0f, 0.0f, 0.0, 0.0, 0.0);
}
