package io.github.obsidianplusplus.spatialaudio.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.obsidianplusplus.spatialaudio.acoustics.AcousticsManager;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.SourceBinding;
import io.github.obsidianplusplus.spatialaudio.audio.SpatialChannel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 声音引擎注入：在每个声音实例开始播放时，把类别、ID 与初始遮挡结果绑定到对应声道；
 * 并在引擎加载后把原版声音线程交给音频系统使用。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	/** 原版声音线程执行器。 */
	@Shadow
	@Final
	private SoundEngineExecutor executor;

	/**
	 * 引擎（重新）加载完成后登记声音线程。
	 *
	 * @param ci 回调信息
	 */
	@Inject(method = "loadLibrary", at = @At("TAIL"))
	private void spatialaudio$afterLoad(final CallbackInfo ci) {
		AudioSystem.get().onEngineLoaded(this.executor);
	}

	/**
	 * 在原版设置声道基础参数之后、开始播放之前，投递绑定任务。
	 *
	 * <p>原版先投递“设置音高、音量、衰减、位置、相对”的任务，再异步加载缓冲区并投递“播放”任务；
	 * 我们紧跟第一个任务投递绑定任务，声音线程按顺序执行，因此播放时绑定信息必然已就绪。
	 *
	 * @param instance 声音实例
	 * @param cir 返回值回调
	 * @param handle 新建的声道句柄
	 */
	@Inject(
		method = "play",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V",
			ordinal = 0,
			shift = At.Shift.AFTER
		)
	)
	private void spatialaudio$bindSource(final SoundInstance instance, final CallbackInfoReturnable<SoundEngine.PlayResult> cir,
			@Local final ChannelAccess.ChannelHandle handle) {
		final SourceBinding binding = AcousticsManager.get().createBinding(instance);
		handle.execute(channel -> ((SpatialChannel) channel).spatialaudio$state().bind(binding));
	}
}
