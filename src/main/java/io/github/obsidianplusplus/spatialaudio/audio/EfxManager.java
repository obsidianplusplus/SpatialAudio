package io.github.obsidianplusplus.spatialaudio.audio;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.acoustics.EchoParams;
import io.github.obsidianplusplus.spatialaudio.acoustics.ReverbParams;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;

/**
 * OpenAL EFX（Effects Extension）资源管理：混响、回声效果槽与低通滤波器。
 *
 * <p>设计要点：
 * <ul>
 *   <li>效果槽 0 为环境混响（EAX Reverb，不支持时回退到标准 Reverb），效果槽 1 为远距离回声（Echo）；</li>
 *   <li>EFX 规定把滤波器附加到声源时会“复制”参数，因此全局只需两个可复用的低通滤波器对象，
 *       设置参数后立即附加即可，无需为每个声源单独创建；</li>
 *   <li>所有方法只能在持有 OpenAL 上下文且由 {@link AudioSystem} 加锁的线程中调用。</li>
 * </ul>
 */
public final class EfxManager {
	/** 混响所用的辅助发送通道编号。 */
	public static final int REVERB_SEND = 0;

	/** 回声所用的辅助发送通道编号。 */
	public static final int ECHO_SEND = 1;

	/** 是否初始化成功。 */
	private boolean ready;

	/** 混响是否为 EAX 版本。 */
	private boolean eaxReverb;

	/** 可用的辅助发送通道数量。 */
	private int sends;

	/** 混响效果槽。 */
	private int reverbSlot;

	/** 混响效果对象。 */
	private int reverbEffect;

	/** 回声效果槽（0 表示不可用）。 */
	private int echoSlot;

	/** 回声效果对象。 */
	private int echoEffect;

	/** 直达声低通滤波器。 */
	private int directFilter;

	/** 发送通道低通滤波器。 */
	private int sendFilter;

	/** 最近一次上传的混响参数。 */
	private ReverbParams uploadedReverb;

	/** 最近一次上传的回声参数。 */
	private EchoParams uploadedEcho;

	/**
	 * 创建全部 EFX 对象。
	 *
	 * @param availableSends 设备支持的辅助发送通道数
	 * @return 成功时返回 true
	 */
	public boolean init(final int availableSends) {
		this.ready = false;
		this.uploadedReverb = null;
		this.uploadedEcho = null;
		this.sends = availableSends;
		if (availableSends < 1) {
			return false;
		}
		clearErrors();
		try {
			this.reverbEffect = EXTEfx.alGenEffects();
			EXTEfx.alEffecti(this.reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
			this.eaxReverb = AL10.alGetError() == AL10.AL_NO_ERROR;
			if (!this.eaxReverb) {
				EXTEfx.alEffecti(this.reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
			}
			this.reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
			EXTEfx.alAuxiliaryEffectSloti(this.reverbSlot, EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);

			if (availableSends > ECHO_SEND) {
				this.echoEffect = EXTEfx.alGenEffects();
				EXTEfx.alEffecti(this.echoEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_ECHO);
				this.echoSlot = EXTEfx.alGenAuxiliaryEffectSlots();
				EXTEfx.alAuxiliaryEffectSloti(this.echoSlot, EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, AL10.AL_TRUE);
				EXTEfx.alAuxiliaryEffectSlotf(this.echoSlot, EXTEfx.AL_EFFECTSLOT_GAIN, 0.0f);
			} else {
				this.echoSlot = 0;
			}

			this.directFilter = EXTEfx.alGenFilters();
			EXTEfx.alFilteri(this.directFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			this.sendFilter = EXTEfx.alGenFilters();
			EXTEfx.alFilteri(this.sendFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);

			final int error = AL10.alGetError();
			if (error != AL10.AL_NO_ERROR) {
				SpatialAudio.LOGGER.warn("初始化 EFX 时出现 OpenAL 错误 0x{}，声学效果已禁用", Integer.toHexString(error));
				return false;
			}
			this.ready = true;
			this.applyReverb(ReverbParams.DRY);
			this.applyEcho(EchoParams.NONE);
			SpatialAudio.LOGGER.info("EFX 已就绪：{} 混响，{} 个辅助发送通道{}", this.eaxReverb ? "EAX" : "标准",
				availableSends, this.echoSlot != 0 ? "，回声已启用" : "");
			return true;
		} catch (RuntimeException e) {
			SpatialAudio.LOGGER.error("初始化 EFX 失败，声学效果已禁用", e);
			this.ready = false;
			return false;
		}
	}

	/**
	 * 标记 EFX 失效（OpenAL 上下文即将销毁，对象会随上下文与设备一并释放）。
	 */
	public void invalidate() {
		this.ready = false;
		this.uploadedReverb = null;
		this.uploadedEcho = null;
	}

	/**
	 * 是否可用。
	 *
	 * @return 可用时返回 true
	 */
	public boolean isReady() {
		return this.ready;
	}

	/**
	 * 是否支持回声。
	 *
	 * @return 支持时返回 true
	 */
	public boolean hasEcho() {
		return this.ready && this.echoSlot != 0;
	}

	/**
	 * 上传混响参数（与上次差异很小时跳过）。
	 *
	 * @param params 混响参数
	 */
	public void applyReverb(final ReverbParams params) {
		if (!this.ready) {
			return;
		}
		final ReverbParams p = params.clamped();
		if (!p.differsFrom(this.uploadedReverb, 0.01f)) {
			return;
		}
		final int e = this.reverbEffect;
		if (this.eaxReverb) {
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_DENSITY, p.density());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_DIFFUSION, p.diffusion());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_GAIN, p.gain());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_GAINHF, p.gainHF());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_GAINLF, p.gainLF());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_DECAY_TIME, p.decayTime());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, p.decayHFRatio());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, p.decayLFRatio());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, p.reflectionsGain());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, p.reflectionsDelay());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, p.lateReverbGain());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, p.lateReverbDelay());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_ECHO_TIME, p.echoTime());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_ECHO_DEPTH, p.echoDepth());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_MODULATION_TIME, p.modulationTime());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_MODULATION_DEPTH, p.modulationDepth());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, p.airAbsorptionGainHF());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_HFREFERENCE, p.hfReference());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_LFREFERENCE, p.lfReference());
			EXTEfx.alEffectf(e, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, 0.0f);
			EXTEfx.alEffecti(e, EXTEfx.AL_EAXREVERB_DECAY_HFLIMIT, AL10.AL_TRUE);
		} else {
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DENSITY, p.density());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DIFFUSION, p.diffusion());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_GAIN, p.gain());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_GAINHF, p.gainHF());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DECAY_TIME, p.decayTime());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_DECAY_HFRATIO, p.decayHFRatio());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, p.reflectionsGain());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, p.reflectionsDelay());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, p.lateReverbGain());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, p.lateReverbDelay());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, p.airAbsorptionGainHF());
			EXTEfx.alEffectf(e, EXTEfx.AL_REVERB_ROOM_ROLLOFF_FACTOR, 0.0f);
			EXTEfx.alEffecti(e, EXTEfx.AL_REVERB_DECAY_HFLIMIT, AL10.AL_TRUE);
		}
		// 效果槽在附加时复制效果参数，修改效果对象后必须重新附加才能生效。
		EXTEfx.alAuxiliaryEffectSloti(this.reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, e);
		this.uploadedReverb = p;
	}

	/**
	 * 上传回声参数。
	 *
	 * @param params 回声参数
	 */
	public void applyEcho(final EchoParams params) {
		if (!this.hasEcho()) {
			return;
		}
		final EchoParams p = params.clamped();
		if (!p.differsFrom(this.uploadedEcho)) {
			return;
		}
		final int e = this.echoEffect;
		EXTEfx.alEffectf(e, EXTEfx.AL_ECHO_DELAY, p.delay());
		EXTEfx.alEffectf(e, EXTEfx.AL_ECHO_LRDELAY, p.lrDelay());
		EXTEfx.alEffectf(e, EXTEfx.AL_ECHO_DAMPING, p.damping());
		EXTEfx.alEffectf(e, EXTEfx.AL_ECHO_FEEDBACK, p.feedback());
		EXTEfx.alEffectf(e, EXTEfx.AL_ECHO_SPREAD, p.spread());
		EXTEfx.alAuxiliaryEffectSloti(this.echoSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, e);
		EXTEfx.alAuxiliaryEffectSlotf(this.echoSlot, EXTEfx.AL_EFFECTSLOT_GAIN, p.strength());
		this.uploadedEcho = p;
	}

	/**
	 * 设置声源直达声的低通滤波；增益与高频增益均为 1 时直接移除滤波器以节省混音开销。
	 *
	 * @param source 声源
	 * @param gain 整体增益（0~1）
	 * @param gainHF 高频增益（0~1）
	 */
	public void setDirectFilter(final int source, final float gain, final float gainHF) {
		if (!this.ready) {
			return;
		}
		if (gain >= 0.999f && gainHF >= 0.999f) {
			AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
			return;
		}
		EXTEfx.alFilterf(this.directFilter, EXTEfx.AL_LOWPASS_GAIN, clamp01(gain));
		EXTEfx.alFilterf(this.directFilter, EXTEfx.AL_LOWPASS_GAINHF, clamp01(gainHF));
		AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, this.directFilter);
	}

	/**
	 * 设置声源到混响效果槽的发送量。
	 *
	 * @param source 声源
	 * @param gain 发送增益（0 表示断开）
	 * @param gainHF 发送高频增益
	 */
	public void setReverbSend(final int source, final float gain, final float gainHF) {
		this.setSend(source, REVERB_SEND, this.reverbSlot, gain, gainHF);
	}

	/**
	 * 设置声源到回声效果槽的发送量。
	 *
	 * @param source 声源
	 * @param gain 发送增益（0 表示断开）
	 */
	public void setEchoSend(final int source, final float gain) {
		if (this.hasEcho()) {
			this.setSend(source, ECHO_SEND, this.echoSlot, gain, 1.0f);
		}
	}

	/**
	 * 设置辅助发送通道。
	 *
	 * @param source 声源
	 * @param sendIndex 发送通道编号
	 * @param slot 效果槽
	 * @param gain 增益
	 * @param gainHF 高频增益
	 */
	private void setSend(final int source, final int sendIndex, final int slot, final float gain, final float gainHF) {
		if (!this.ready || sendIndex >= this.sends) {
			return;
		}
		if (gain <= 0.001f) {
			AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, sendIndex, EXTEfx.AL_FILTER_NULL);
			return;
		}
		EXTEfx.alFilterf(this.sendFilter, EXTEfx.AL_LOWPASS_GAIN, clamp01(gain));
		EXTEfx.alFilterf(this.sendFilter, EXTEfx.AL_LOWPASS_GAINHF, clamp01(gainHF));
		AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, sendIndex, this.sendFilter);
	}

	/**
	 * 清除 OpenAL 错误标志。
	 */
	public static void clearErrors() {
		for (int i = 0; i < 8 && AL10.alGetError() != AL10.AL_NO_ERROR; i++) {
			// 连续读取直到错误队列为空。
		}
	}

	/**
	 * 限制到 0~1。
	 *
	 * @param value 值
	 * @return 限制后的值
	 */
	private static float clamp01(final float value) {
		return Float.isFinite(value) ? Math.clamp(value, 0.0f, 1.0f) : 1.0f;
	}
}
