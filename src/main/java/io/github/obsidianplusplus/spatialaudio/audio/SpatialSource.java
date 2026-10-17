package io.github.obsidianplusplus.spatialaudio.audio;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 附着在每个 OpenAL 声源（Minecraft 的 {@code Channel}）上的空间音频状态。
 *
 * <p>字段按写入线程分为三组：
 * <ul>
 *   <li>绑定信息：由声音线程在执行主线程投递的绑定任务时写入；</li>
 *   <li>OpenAL 镜像：由声音线程在原版设置声源参数时同步记录；</li>
 *   <li>声学目标：由主线程计算后写入，声音线程读取并平滑。</li>
 * </ul>
 * 跨线程读写的字段均为 volatile；以“cur”开头的字段只在声音线程访问。
 */
public final class SpatialSource {
	/** OpenAL 声源名称。 */
	private final int sourceId;

	// ===================== 绑定信息 =====================

	/** 是否已收到绑定信息。 */
	public volatile boolean bound;

	/** 声音类别；未绑定时为 null。 */
	public volatile SoundSource category;

	/** 声音事件 ID；未绑定时为 null。 */
	public volatile Identifier soundId;

	// ===================== OpenAL 镜像 =====================

	/** 是否为相对听者的声源。 */
	public volatile boolean relative;

	/** 是否启用线性距离衰减。 */
	public volatile boolean attenuated;

	/** 线性衰减的最大距离（格）。 */
	public volatile float maxDistance = 16.0f;

	/** 缓冲区声道数（1 = 单声道，2 = 立体声）。 */
	public volatile int channels = 1;

	/** 是否为流式播放。 */
	public volatile boolean streaming;

	/** 原版设置的真实位置（世界坐标；相对声源则为听者局部坐标）。 */
	public volatile Vec3 truePosition = Vec3.ZERO;

	/** 是否已开始播放并登记到注册表。 */
	public volatile boolean active;

	/** 是否由 OpenAL 按位置做三维定位（单声道或已强制定位的立体声）。 */
	public volatile boolean spatialized = true;

	// ===================== 声学目标 =====================

	/** 主线程计算的声学目标。 */
	public volatile AcousticTarget target = AcousticTarget.CLEAR;

	/** 主线程下一次重新计算声学的游戏刻（仅主线程访问）。 */
	public long nextAcousticsTick;

	// ===================== 声音线程内部状态 =====================

	/** 当前直达声增益。 */
	float curDirectGain = 1.0f;

	/** 当前直达声高频增益。 */
	float curDirectHF = 1.0f;

	/** 当前混响发送增益。 */
	float curSendGain;

	/** 当前混响发送高频增益。 */
	float curSendHF = 1.0f;

	/** 当前回声发送增益。 */
	float curEchoSend;

	/** 当前绕射重定向权重。 */
	float curRedirectBlend;

	/** 当前绕射方向。 */
	double curRedirectX;

	/** 当前绕射方向 y。 */
	double curRedirectY;

	/** 当前绕射方向 z。 */
	double curRedirectZ;

	/** 上次写入 OpenAL 的直达声增益。 */
	float appliedDirectGain = -1.0f;

	/** 上次写入 OpenAL 的直达声高频增益。 */
	float appliedDirectHF = -1.0f;

	/** 上次写入 OpenAL 的混响发送增益。 */
	float appliedSendGain = -1.0f;

	/** 上次写入 OpenAL 的混响发送高频增益。 */
	float appliedSendHF = -1.0f;

	/** 上次写入 OpenAL 的回声发送增益。 */
	float appliedEchoSend = -1.0f;

	/** 上次写入 OpenAL 的空气吸收系数。 */
	float appliedAirAbsorption = -1.0f;

	/** 上次写入 OpenAL 的位置。 */
	final float[] appliedPosition = {Float.NaN, Float.NaN, Float.NaN};

	/** 上次写入的立体声角度（左声道）。 */
	float appliedStereoLeft = Float.NaN;

	/** 速度估计用的上一位置。 */
	Vec3 velocityAnchor;

	/** 速度估计用的上一时刻（纳秒）。 */
	long velocityNanos;

	/** 平滑后的速度 x（格/秒）。 */
	double velocityX;

	/** 平滑后的速度 y。 */
	double velocityY;

	/** 平滑后的速度 z。 */
	double velocityZ;

	/**
	 * 创建状态对象。
	 *
	 * @param sourceId OpenAL 声源名称
	 */
	public SpatialSource(final int sourceId) {
		this.sourceId = sourceId;
	}

	/**
	 * OpenAL 声源名称。
	 *
	 * @return 声源 ID
	 */
	public int sourceId() {
		return this.sourceId;
	}

	/**
	 * 应用主线程投递的绑定信息（在声音线程执行）。
	 *
	 * @param binding 绑定信息
	 */
	public void bind(final SourceBinding binding) {
		this.category = binding.category();
		this.soundId = binding.soundId();
		this.target = binding.initialTarget() == null ? AcousticTarget.CLEAR : binding.initialTarget();
		this.bound = true;
	}
}
