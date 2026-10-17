package io.github.obsidianplusplus.spatialaudio.audio;

import io.github.obsidianplusplus.spatialaudio.acoustics.EchoParams;
import io.github.obsidianplusplus.spatialaudio.acoustics.EnvironmentSnapshot;
import io.github.obsidianplusplus.spatialaudio.acoustics.ReverbParams;

/**
 * 主线程计算的、对所有声源生效的全局声学状态。
 *
 * @param reverb 目标混响参数
 * @param echo 目标回声参数
 * @param mediumGain 听者所处介质（水、岩浆、细雪）造成的整体增益
 * @param mediumGainHF 听者所处介质造成的高频增益
 * @param airAbsorptionMultiplier 天气对空气吸收的倍率（下雨时空气湿度高，高频衰减更明显）
 * @param environment 环境探测结果（用于界面显示）
 */
public record GlobalAcoustics(
	ReverbParams reverb,
	EchoParams echo,
	float mediumGain,
	float mediumGainHF,
	float airAbsorptionMultiplier,
	EnvironmentSnapshot environment
) {
	/** 中性状态：无混响、无回声、无介质滤波。 */
	public static final GlobalAcoustics NEUTRAL = new GlobalAcoustics(
		ReverbParams.DRY, EchoParams.NONE, 1.0f, 1.0f, 1.0f, EnvironmentSnapshot.OPEN
	);
}
