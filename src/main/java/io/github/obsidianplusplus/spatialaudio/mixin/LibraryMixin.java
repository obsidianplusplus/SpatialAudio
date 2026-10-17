package io.github.obsidianplusplus.spatialaudio.mixin;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OpenAL 库注入：接管上下文属性（HRTF、输出模式、辅助发送通道），并在上下文创建、销毁时通知音频系统。
 */
@Mixin(Library.class)
public abstract class LibraryMixin {
	/** 当前设备句柄。 */
	@Shadow
	private long currentDevice;

	/** 当前设备名称。 */
	@Shadow
	private String currentDeviceName;

	/**
	 * 替换原版上下文属性。
	 *
	 * @param stack 内存栈
	 * @param enableHrtf 原版是否请求 HRTF
	 * @param cir 返回值回调
	 */
	@Inject(method = "createAttributes", at = @At("HEAD"), cancellable = true)
	private void spatialaudio$createAttributes(final MemoryStack stack, final boolean enableHrtf, final CallbackInfoReturnable<IntBuffer> cir) {
		final IntBuffer attributes = AudioSystem.get().buildContextAttributes(this.currentDevice, stack, enableHrtf);
		if (attributes != null) {
			cir.setReturnValue(attributes);
		}
	}

	/**
	 * 上下文创建完成后初始化空间音频。
	 *
	 * @param preferredDevice 首选设备
	 * @param currentDevices 当前设备列表
	 * @param useHrtf 原版 HRTF 选项
	 * @param ci 回调信息
	 */
	@Inject(method = "init", at = @At("TAIL"))
	private void spatialaudio$afterInit(final @Nullable String preferredDevice, final DeviceList currentDevices, final boolean useHrtf, final CallbackInfo ci) {
		AudioSystem.get().onLibraryInitialized(this.currentDevice, this.currentDeviceName);
	}

	/**
	 * 上下文销毁前停止空间音频的一切 OpenAL 调用。
	 *
	 * @param ci 回调信息
	 */
	@Inject(method = "cleanup", at = @At("HEAD"))
	private void spatialaudio$beforeCleanup(final CallbackInfo ci) {
		AudioSystem.get().onLibraryCleanup();
	}
}
