package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 把环境探测结果映射为 EAX 混响与回声参数的声学模型。
 *
 * <p>映射依据：
 * <ul>
 *   <li>衰减时间采用 Eyring 公式估算的 RT60，并对过长的尾音做软压缩；</li>
 *   <li>封闭度越高，混响越“满”（整体增益、后期混响增益越大），开阔地几乎只剩地面反射；</li>
 *   <li>平均自由程决定早期反射与后期混响的延迟（声音往返墙面所需时间）；</li>
 *   <li>表面越软（羊毛、雪），高频衰减越快，混响越暗；坚硬的石头、金属则明亮；</li>
 *   <li>空间越大，回声密度越低，听感越“空旷”。</li>
 * </ul>
 */
public final class ReverbModel {
	/** 声速（米/秒，Minecraft 中 1 格 = 1 米）。 */
	public static final double SPEED_OF_SOUND = 343.3;

	/** 混响时间软压缩的上限（秒）。 */
	private static final double PERCEPTUAL_MAX_DECAY = 10.0;

	/** 形成回声所需的最低水平回声比例。 */
	private static final float MIN_ECHO_FRACTION = 0.12f;

	/** 形成回声所需的最低天空开阔度。 */
	private static final float MIN_ECHO_SKY = 0.4f;

	private ReverbModel() {
	}

	/**
	 * 根据环境快照生成混响参数。
	 *
	 * @param env 环境快照
	 * @return EAX 混响参数（已限制范围）
	 */
	public static ReverbParams reverbFor(final EnvironmentSnapshot env) {
		final float e = env.enclosure();
		if (e < 0.02f) {
			return ReverbParams.DRY;
		}
		final float l = Math.max(0.5f, env.meanFreePath());
		final float soft = env.surfaceAbsorption();
		final float sky = env.skyOpenness();
		// 理想石室的理论混响可达十几秒，游戏中过长的尾音会让声音浑浊。
		// 对 RT60 做软压缩：短混响几乎不变，长混响平滑地趋近 10 秒。
		final float rt60 = (float) (PERCEPTUAL_MAX_DECAY * (1.0 - Math.exp(-env.rt60() / PERCEPTUAL_MAX_DECAY)));

		final float gain = (0.25f + 0.75f * e) * (1.0f - 0.6f * sky);
		final float reflectionsGain = e * (1.0f - soft) * (l < 6.0f ? 1.3f : 1.0f);
		final float reflectionsDelay = (float) (l / SPEED_OF_SOUND * 0.6);
		final float lateGain = (float) (Math.pow(e, 1.5) * Math.sqrt(1.0f - soft) * 1.4);
		final float lateDelay = (float) (l / SPEED_OF_SOUND * 0.9);
		final float decayHFRatio = 1.0f - soft * 0.7f - (rt60 > 2.0f ? 0.15f : 0.0f);
		final float density = 1.0f - l / 64.0f;
		final float diffusion = 0.6f + 0.4f * e;
		final float gainHF = 0.99f - soft * 0.6f;
		final float modulationDepth = rt60 > 3.0f ? 0.1f : 0.0f;

		return new ReverbParams(
			Math.clamp(density, 0.25f, 1.0f),
			Math.clamp(diffusion, 0.5f, 1.0f),
			gain,
			Math.clamp(gainHF, 0.3f, 1.0f),
			1.0f,
			rt60,
			Math.clamp(decayHFRatio, 0.2f, 1.3f),
			1.0f,
			reflectionsGain,
			Math.max(0.002f, reflectionsDelay),
			lateGain,
			Math.max(0.005f, lateDelay),
			0.25f,
			0.0f,
			0.25f,
			modulationDepth,
			0.994f,
			5000.0f,
			250.0f
		).clamped();
	}

	/**
	 * 根据环境快照生成远距离回声参数。
	 *
	 * @param env 环境快照
	 * @return 回声参数；不满足回声条件时返回 {@link EchoParams#NONE}
	 */
	public static EchoParams echoFor(final EnvironmentSnapshot env) {
		if (env.echoStrength() <= 0.0f || env.echoDistance() <= 0.0f || env.skyOpenness() < MIN_ECHO_SKY || env.enclosure() > 0.7f) {
			return EchoParams.NONE;
		}
		final float fraction = env.echoStrength() / Math.max(0.01f, env.skyOpenness());
		if (fraction < MIN_ECHO_FRACTION) {
			return EchoParams.NONE;
		}
		// 往返时间 t = 2d / c。EFX 回声第一拍最长 0.207 秒，其余时间放到第二拍上。
		final float roundTrip = (float) (2.0 * env.echoDistance() / SPEED_OF_SOUND);
		final float delay = Math.min(0.207f, roundTrip);
		final float lrDelay = Math.clamp(roundTrip - delay + 0.03f, 0.03f, 0.404f);
		final float feedback = Math.clamp(0.15f + 0.35f * fraction, 0.0f, 0.5f);
		final float strength = Math.clamp(env.echoStrength() * 0.9f, 0.0f, 0.6f);
		return new EchoParams(delay, lrDelay, 0.6f, feedback, -0.8f, strength).clamped();
	}
}
