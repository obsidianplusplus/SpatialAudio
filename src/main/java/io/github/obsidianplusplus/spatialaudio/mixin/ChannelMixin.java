package io.github.obsidianplusplus.spatialaudio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.SpatialChannel;
import io.github.obsidianplusplus.spatialaudio.audio.SpatialSource;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 声道（OpenAL 声源）注入：记录原版设置的各项参数，并在播放与销毁时通知空间音频处理器。
 *
 * <p>全部注入点都运行在原版声音线程中（个别清理流程在主线程，只涉及注册表操作）。
 */
@Mixin(Channel.class)
public abstract class ChannelMixin implements SpatialChannel {
	/** 附着的空间音频状态。 */
	@Unique
	private SpatialSource spatialaudio$source;

	/**
	 * 构造完成后创建状态对象。
	 *
	 * @param src OpenAL 声源名称
	 * @param ci 回调信息
	 */
	@Inject(method = "<init>(I)V", at = @At("TAIL"))
	private void spatialaudio$onInit(final int src, final CallbackInfo ci) {
		this.spatialaudio$source = new SpatialSource(src);
	}

	@Override
	public SpatialSource spatialaudio$state() {
		return this.spatialaudio$source;
	}

	/**
	 * 包装位置写入：记录真实位置，改写为考虑绕射重定向与屏幕锚定后的感知位置。
	 *
	 * @param source OpenAL 声源
	 * @param param 参数名（AL_POSITION）
	 * @param values 原版位置
	 * @param original 原始调用
	 */
	@WrapOperation(method = "setSelfPosition", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alSourcefv(II[F)V"))
	private void spatialaudio$wrapPosition(final int source, final int param, final float[] values, final Operation<Void> original) {
		original.call(source, param, AudioSystem.get().processor().onSetPosition(this.spatialaudio$source, values));
	}

	/**
	 * 记录是否为相对声源。
	 *
	 * @param relative 是否相对
	 * @param ci 回调信息
	 */
	@Inject(method = "setRelative", at = @At("TAIL"))
	private void spatialaudio$onSetRelative(final boolean relative, final CallbackInfo ci) {
		this.spatialaudio$source.relative = relative;
	}

	/**
	 * 记录线性衰减距离。
	 *
	 * @param maxDistance 最大距离
	 * @param ci 回调信息
	 */
	@Inject(method = "linearAttenuation", at = @At("TAIL"))
	private void spatialaudio$onLinearAttenuation(final float maxDistance, final CallbackInfo ci) {
		this.spatialaudio$source.attenuated = true;
		this.spatialaudio$source.maxDistance = maxDistance;
	}

	/**
	 * 记录关闭距离衰减。
	 *
	 * @param ci 回调信息
	 */
	@Inject(method = "disableAttenuation", at = @At("TAIL"))
	private void spatialaudio$onDisableAttenuation(final CallbackInfo ci) {
		this.spatialaudio$source.attenuated = false;
	}

	/**
	 * 记录静态缓冲区的声道数。
	 *
	 * @param buffer 声音缓冲区
	 * @param ci 回调信息
	 */
	@Inject(method = "attachStaticBuffer", at = @At("HEAD"))
	private void spatialaudio$onAttachStatic(final SoundBuffer buffer, final CallbackInfo ci) {
		this.spatialaudio$source.channels = buffer.format().getChannels();
		this.spatialaudio$source.streaming = false;
	}

	/**
	 * 记录流式音频的声道数。
	 *
	 * @param stream 音频流
	 * @param ci 回调信息
	 */
	@Inject(method = "attachBufferStream", at = @At("HEAD"))
	private void spatialaudio$onAttachStream(final AudioStream stream, final CallbackInfo ci) {
		this.spatialaudio$source.channels = stream.getFormat().getChannels();
		this.spatialaudio$source.streaming = true;
	}

	/**
	 * 开始播放前写入全部空间音频参数。
	 *
	 * @param ci 回调信息
	 */
	@Inject(method = "play", at = @At("HEAD"))
	private void spatialaudio$onPlay(final CallbackInfo ci) {
		AudioSystem.get().processor().onPlay(this.spatialaudio$source);
	}

	/**
	 * 销毁时从注册表移除。
	 *
	 * @param ci 回调信息
	 */
	@Inject(method = "destroy", at = @At("HEAD"))
	private void spatialaudio$onDestroy(final CallbackInfo ci) {
		AudioSystem.get().processor().onDestroy(this.spatialaudio$source);
	}
}
