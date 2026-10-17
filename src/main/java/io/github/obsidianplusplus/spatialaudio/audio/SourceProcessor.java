package io.github.obsidianplusplus.spatialaudio.audio;

import io.github.obsidianplusplus.spatialaudio.config.CategorySettings;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTStereoAngles;
import org.lwjgl.openal.SOFTSourceSpatialize;

/**
 * 逐声源处理：立体声定位、遮挡滤波、混响/回声发送、绕射重定向、屏幕锚定、空气吸收与多普勒速度。
 *
 * <p>所有方法都在 Minecraft 声音线程（Sound engine 线程）执行，与原版对同一声源的操作天然串行，
 * 不会出现“声源已删除仍被设置参数”的竞争。主线程只写入 {@link SpatialSource#target} 等 volatile 字段。
 */
public final class SourceProcessor {
	/** 增益平滑时间常数（秒）。 */
	private static final double GAIN_SMOOTHING_SECONDS = 0.1;

	/** 重定向平滑时间常数（秒）。 */
	private static final double REDIRECT_SMOOTHING_SECONDS = 0.25;

	/** 增益变化小于该值时不重新写入 OpenAL。 */
	private static final float GAIN_EPSILON = 0.004f;

	/** 位置变化小于该值（格）时不重新写入。 */
	private static final float POSITION_EPSILON = 0.01f;

	/** 立体声角度变化小于该值（弧度）时不重新写入。 */
	private static final float ANGLE_EPSILON = 0.002f;

	/** 重定向只对距离超过该值的声源生效（格）。 */
	private static final double MIN_REDIRECT_DISTANCE = 1.5;

	/** 默认立体声虚拟扬声器角度（±30°）。 */
	private static final float DEFAULT_STEREO_ANGLE = (float) (Math.PI / 6.0);

	/** 所属音频系统。 */
	private final AudioSystem system;

	/**
	 * 创建处理器。
	 *
	 * @param system 音频系统
	 */
	SourceProcessor(final AudioSystem system) {
		this.system = system;
	}

	/**
	 * 声源即将开始播放：登记并一次性写入全部初始参数（不做平滑，保证声音一出现就是正确效果）。
	 *
	 * @param source 声源状态
	 */
	public void onPlay(final SpatialSource source) {
		this.system.sources().add(source);
		if (!this.system.isAlive()) {
			return;
		}
		final SpatialAudioConfig config = ConfigManager.get();
		final CategorySettings category = config.category(source.category);
		final int id = source.sourceId();

		if (this.system.capabilities().sourceSpatialize()) {
			final boolean spatialize = config.enabled && config.spatializeStereo && !source.relative
				&& source.channels >= 2 && category.spatialize;
			AL10.alSourcei(id, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, spatialize ? AL10.AL_TRUE : SOFTSourceSpatialize.AL_AUTO_SOFT);
			source.spatialized = spatialize || source.channels < 2;
		} else {
			source.spatialized = source.channels < 2;
		}

		this.computeTargets(source, config, category, 0.0, true);
		this.apply(source, config, true);
		this.applyAnchoring(source, config, category, true);
		EfxManager.clearErrors();
	}

	/**
	 * 声源被销毁：从注册表移除。
	 *
	 * @param source 声源状态
	 */
	public void onDestroy(final SpatialSource source) {
		this.system.sources().remove(source);
	}

	/**
	 * 原版设置声源位置时调用：记录真实位置，返回考虑绕射重定向与屏幕锚定后的感知位置。
	 *
	 * @param source 声源状态
	 * @param vanilla 原版位置 {x, y, z}
	 * @return 实际写入 OpenAL 的位置
	 */
	public float[] onSetPosition(final SpatialSource source, final float[] vanilla) {
		final Vec3 position = new Vec3(vanilla[0], vanilla[1], vanilla[2]);
		source.truePosition = position;
		this.trackVelocity(source, position);
		if (!source.active || !this.system.isAlive() || !ConfigManager.get().enabled) {
			return vanilla;
		}
		final float[] perceived = this.perceivedPosition(source, ConfigManager.get());
		System.arraycopy(perceived, 0, source.appliedPosition, 0, 3);
		return perceived;
	}

	/**
	 * 周期性更新全部声源的声学参数（带平滑）。
	 *
	 * @param dt 距上次更新的时间（秒）
	 */
	public void update(final double dt) {
		final SpatialAudioConfig config = ConfigManager.get();
		for (final SpatialSource source : this.system.sources().all()) {
			final CategorySettings category = config.category(source.category);
			this.computeTargets(source, config, category, dt, false);
			this.apply(source, config, false);
		}
	}

	/**
	 * 随听者朝向刷新屏幕锚定（相对声源的位置与立体声角度）。
	 */
	public void updateAnchoring() {
		final SpatialAudioConfig config = ConfigManager.get();
		for (final SpatialSource source : this.system.sources().all()) {
			if (source.relative || !source.spatialized) {
				this.applyAnchoring(source, config, config.category(source.category), false);
			}
		}
	}

	/**
	 * 计算并平滑声学目标值。
	 *
	 * @param source 声源
	 * @param config 配置
	 * @param category 类别设置
	 * @param dt 时间间隔（秒）
	 * @param immediate 是否直接跳到目标值
	 */
	private void computeTargets(final SpatialSource source, final SpatialAudioConfig config, final CategorySettings category,
			final double dt, final boolean immediate) {
		final GlobalAcoustics global = this.system.global();
		final AcousticTarget target = source.target == null ? AcousticTarget.CLEAR : source.target;
		float directGain = 1.0f;
		float directHF = 1.0f;
		float sendGain = 0.0f;
		float sendHF = 1.0f;
		float echoSend = 0.0f;
		float redirect = 0.0f;

		if (this.system.acousticsActive(config)) {
			final boolean occlude = !source.relative && category.occlusion && config.occlusionEnabled;
			if (occlude) {
				directGain = target.directGain();
				directHF = target.directGainHF();
				if (config.soundRedirection) {
					redirect = target.redirectBlend();
				}
			}
			final boolean medium = config.mediumFilter && source.category != SoundSource.MUSIC && source.category != SoundSource.UI;
			if (medium) {
				directGain *= global.mediumGain();
				directHF *= global.mediumGainHF();
			}
			if (config.reverbEnabled && category.reverbSend > 0.0f) {
				// 被遮挡的声源仍能激发听者所在空间的混响，但比直达声衰减得少。
				final float occlusionMix = occlude ? 0.5f + 0.5f * target.directGain() : 1.0f;
				sendGain = category.reverbSend * occlusionMix * (source.relative ? 0.5f : 1.0f);
				sendHF = (occlude ? (float) Math.sqrt(target.directGainHF()) : 1.0f) * (medium ? global.mediumGainHF() : 1.0f);
			}
			if (config.echoEnabled && !source.relative && source.attenuated) {
				echoSend = Math.min(1.0f, category.reverbSend);
			}
		}

		if (immediate) {
			source.curDirectGain = directGain;
			source.curDirectHF = directHF;
			source.curSendGain = sendGain;
			source.curSendHF = sendHF;
			source.curEchoSend = echoSend;
			source.curRedirectBlend = redirect;
			source.curRedirectX = target.redirectX();
			source.curRedirectY = target.redirectY();
			source.curRedirectZ = target.redirectZ();
			return;
		}
		final float a = (float) (1.0 - Math.exp(-dt / GAIN_SMOOTHING_SECONDS));
		source.curDirectGain += a * (directGain - source.curDirectGain);
		source.curDirectHF += a * (directHF - source.curDirectHF);
		source.curSendGain += a * (sendGain - source.curSendGain);
		source.curSendHF += a * (sendHF - source.curSendHF);
		source.curEchoSend += a * (echoSend - source.curEchoSend);

		final double b = 1.0 - Math.exp(-dt / REDIRECT_SMOOTHING_SECONDS);
		source.curRedirectBlend += (float) (b * (redirect - source.curRedirectBlend));
		if (redirect > 0.0f) {
			double x = source.curRedirectX + b * (target.redirectX() - source.curRedirectX);
			double y = source.curRedirectY + b * (target.redirectY() - source.curRedirectY);
			double z = source.curRedirectZ + b * (target.redirectZ() - source.curRedirectZ);
			final double length = Math.sqrt(x * x + y * y + z * z);
			if (length > 1.0e-6) {
				x /= length;
				y /= length;
				z /= length;
			} else {
				x = target.redirectX();
				y = target.redirectY();
				z = target.redirectZ();
			}
			source.curRedirectX = x;
			source.curRedirectY = y;
			source.curRedirectZ = z;
		}
	}

	/**
	 * 把当前平滑值写入 OpenAL（只写有变化的参数）。
	 *
	 * @param source 声源
	 * @param config 配置
	 * @param force 是否强制全部写入
	 */
	private void apply(final SpatialSource source, final SpatialAudioConfig config, final boolean force) {
		final int id = source.sourceId();
		final EfxManager efx = this.system.efx();
		if (efx.isReady()) {
			if (force || changed(source.curDirectGain, source.appliedDirectGain) || changed(source.curDirectHF, source.appliedDirectHF)) {
				efx.setDirectFilter(id, source.curDirectGain, source.curDirectHF);
				source.appliedDirectGain = source.curDirectGain;
				source.appliedDirectHF = source.curDirectHF;
			}
			if (force || changed(source.curSendGain, source.appliedSendGain) || changed(source.curSendHF, source.appliedSendHF)) {
				efx.setReverbSend(id, source.curSendGain, source.curSendHF);
				source.appliedSendGain = source.curSendGain;
				source.appliedSendHF = source.curSendHF;
			}
			if (force || changed(source.curEchoSend, source.appliedEchoSend)) {
				efx.setEchoSend(id, source.curEchoSend);
				source.appliedEchoSend = source.curEchoSend;
			}
			final float air = this.airAbsorption(source, config);
			if (force || Math.abs(air - source.appliedAirAbsorption) > 0.01f) {
				AL10.alSourcef(id, EXTEfx.AL_AIR_ABSORPTION_FACTOR, air);
				source.appliedAirAbsorption = air;
			}
		}

		if (!source.relative) {
			final float[] perceived = config.enabled ? this.perceivedPosition(source, config) : toArray(source.truePosition);
			if (force || positionChanged(perceived, source.appliedPosition)) {
				AL10.alSource3f(id, AL10.AL_POSITION, perceived[0], perceived[1], perceived[2]);
				System.arraycopy(perceived, 0, source.appliedPosition, 0, 3);
			}
			if (config.enabled && config.dopplerEnabled) {
				AL10.alSource3f(id, AL10.AL_VELOCITY, (float) source.velocityX, (float) source.velocityY, (float) source.velocityZ);
			} else if (force) {
				AL10.alSource3f(id, AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);
			}
		}
	}

	/**
	 * 写入屏幕锚定相关参数。
	 *
	 * @param source 声源
	 * @param config 配置
	 * @param category 类别设置
	 * @param force 是否强制写入
	 */
	private void applyAnchoring(final SpatialSource source, final SpatialAudioConfig config, final CategorySettings category, final boolean force) {
		final boolean anchored = config.enabled && config.anchorToScreen && category.screenAnchored;
		final ListenerController listener = this.system.listener();
		final int id = source.sourceId();
		if (source.channels >= 2 && !source.spatialized) {
			if (!this.system.capabilities().stereoAngles()) {
				return;
			}
			final float[] angles = anchored ? listener.anchoredStereoAngles() : new float[] {DEFAULT_STEREO_ANGLE, -DEFAULT_STEREO_ANGLE};
			if (force || Float.isNaN(source.appliedStereoLeft) || Math.abs(angles[0] - source.appliedStereoLeft) > ANGLE_EPSILON) {
				AL10.alSourcefv(id, EXTStereoAngles.AL_STEREO_ANGLES, angles);
				source.appliedStereoLeft = angles[0];
			}
			return;
		}
		if (!source.relative) {
			return;
		}
		final Vec3 offset = source.truePosition;
		if (offset.lengthSqr() < 1.0e-6) {
			return;
		}
		final float[] position = anchored ? listener.anchorRelative(offset.x, offset.y, offset.z) : toArray(offset);
		if (force || positionChanged(position, source.appliedPosition)) {
			AL10.alSource3f(id, AL10.AL_POSITION, position[0], position[1], position[2]);
			System.arraycopy(position, 0, source.appliedPosition, 0, 3);
		}
	}

	/**
	 * 计算感知位置：相对声源做屏幕锚定，世界声源按绕射方向重定向（保持真实距离，使距离衰减不变）。
	 *
	 * @param source 声源
	 * @param config 配置
	 * @return 位置数组
	 */
	private float[] perceivedPosition(final SpatialSource source, final SpatialAudioConfig config) {
		final Vec3 p = source.truePosition;
		if (source.relative) {
			final CategorySettings category = config.category(source.category);
			if (config.anchorToScreen && category.screenAnchored && p.lengthSqr() > 1.0e-6) {
				return this.system.listener().anchorRelative(p.x, p.y, p.z);
			}
			return toArray(p);
		}
		final float blend = source.curRedirectBlend;
		if (blend < 0.01f) {
			return toArray(p);
		}
		final double[] l = this.system.listener().position();
		final double dx = p.x - l[0];
		final double dy = p.y - l[1];
		final double dz = p.z - l[2];
		final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance < MIN_REDIRECT_DISTANCE) {
			return toArray(p);
		}
		double x = dx / distance * (1.0 - blend) + source.curRedirectX * blend;
		double y = dy / distance * (1.0 - blend) + source.curRedirectY * blend;
		double z = dz / distance * (1.0 - blend) + source.curRedirectZ * blend;
		final double length = Math.sqrt(x * x + y * y + z * z);
		if (length < 1.0e-6) {
			return toArray(p);
		}
		x /= length;
		y /= length;
		z /= length;
		return new float[] {(float) (l[0] + x * distance), (float) (l[1] + y * distance), (float) (l[2] + z * distance)};
	}

	/**
	 * 计算空气吸收系数：仅对世界中带距离衰减的声源生效，下雨时更强。
	 *
	 * @param source 声源
	 * @param config 配置
	 * @return 系数（0~10）
	 */
	private float airAbsorption(final SpatialSource source, final SpatialAudioConfig config) {
		if (!this.system.acousticsActive(config) || !config.airAbsorption || source.relative || !source.attenuated) {
			return 0.0f;
		}
		return Math.clamp(config.airAbsorptionFactor * this.system.global().airAbsorptionMultiplier(), 0.0f, 10.0f);
	}

	/**
	 * 根据位置变化估计声源速度（用于多普勒效应）。
	 *
	 * @param source 声源
	 * @param position 新位置
	 */
	private void trackVelocity(final SpatialSource source, final Vec3 position) {
		final long now = System.nanoTime();
		final Vec3 anchor = source.velocityAnchor;
		if (anchor != null && !source.relative) {
			final double dt = (now - source.velocityNanos) / 1.0e9;
			if (dt > 1.0e-3 && dt < 1.0) {
				final double vx = (position.x - anchor.x) / dt;
				final double vy = (position.y - anchor.y) / dt;
				final double vz = (position.z - anchor.z) / dt;
				if (vx * vx + vy * vy + vz * vz > 80.0 * 80.0) {
					source.velocityX = 0.0;
					source.velocityY = 0.0;
					source.velocityZ = 0.0;
				} else {
					final double alpha = 1.0 - Math.exp(-dt / 0.15);
					source.velocityX += alpha * (vx - source.velocityX);
					source.velocityY += alpha * (vy - source.velocityY);
					source.velocityZ += alpha * (vz - source.velocityZ);
				}
			}
		}
		source.velocityAnchor = position;
		source.velocityNanos = now;
	}

	/**
	 * 判断增益是否需要重新写入。
	 *
	 * @param current 当前值
	 * @param applied 已写入的值
	 * @return 需要写入时返回 true
	 */
	private static boolean changed(final float current, final float applied) {
		return applied < 0.0f || Math.abs(current - applied) > GAIN_EPSILON;
	}

	/**
	 * 判断位置是否需要重新写入。
	 *
	 * @param current 当前位置
	 * @param applied 已写入的位置
	 * @return 需要写入时返回 true
	 */
	private static boolean positionChanged(final float[] current, final float[] applied) {
		return Float.isNaN(applied[0])
			|| Math.abs(current[0] - applied[0]) > POSITION_EPSILON
			|| Math.abs(current[1] - applied[1]) > POSITION_EPSILON
			|| Math.abs(current[2] - applied[2]) > POSITION_EPSILON;
	}

	/**
	 * 向量转浮点数组。
	 *
	 * @param v 向量
	 * @return 数组
	 */
	private static float[] toArray(final Vec3 v) {
		return new float[] {(float) v.x, (float) v.y, (float) v.z};
	}
}
