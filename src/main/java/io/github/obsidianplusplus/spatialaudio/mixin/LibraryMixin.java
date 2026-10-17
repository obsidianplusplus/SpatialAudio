package io.github.obsidianplusplus.spatialaudio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRenderer;
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
 * OpenAL 库注入：接管上下文属性（HRTF、输出模式、辅助发送通道），在 Apple 空间音频方式下改用回环设备，
 * 并在上下文创建、销毁时通知音频系统。
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
	 * 包装原版打开设备的调用：选择 Apple 空间音频时改为打开 OpenAL Soft 回环设备，失败则照常打开真实设备。
	 *
	 * @param preferredDevice 用户选择的设备，null 表示系统默认
	 * @param systemDefaultDevice 系统默认设备
	 * @param original 原始调用
	 * @return 设备句柄
	 */
	@WrapOperation(method = "init", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/audio/Library;openDeviceOrFallback(Ljava/lang/String;Ljava/lang/String;)J"))
	private long spatialaudio$openDevice(final @Nullable String preferredDevice, final @Nullable String systemDefaultDevice,
		final Operation<Long> original) {
		final long loopback = AppleRenderer.get().openLoopbackDevice(preferredDevice, systemDefaultDevice);
		return loopback != 0L ? loopback : original.call(preferredDevice, systemDefaultDevice);
	}

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
	 * 上下文创建完成后初始化空间音频；使用回环设备时把设备名称改为真实的目标输出设备，
	 * 使原版的“设备消失 / 默认设备变化”检测照常工作。
	 *
	 * @param preferredDevice 首选设备
	 * @param currentDevices 当前设备列表
	 * @param useHrtf 原版 HRTF 选项
	 * @param ci 回调信息
	 */
	@Inject(method = "init", at = @At("TAIL"))
	private void spatialaudio$afterInit(final @Nullable String preferredDevice, final DeviceList currentDevices, final boolean useHrtf, final CallbackInfo ci) {
		final AppleRenderer apple = AppleRenderer.get();
		final boolean loopback = apple.isLoopbackDevice(this.currentDevice);
		if (loopback) {
			this.currentDeviceName = apple.targetDeviceName();
		}
		AudioSystem.get().onLibraryInitialized(this.currentDevice, this.currentDeviceName);
		if (loopback) {
			apple.onContextReady(this.currentDevice);
		}
	}

	/**
	 * 上下文销毁前停止空间音频的一切 OpenAL 调用（包括 Apple 方式下的回环渲染）。
	 *
	 * @param ci 回调信息
	 */
	@Inject(method = "cleanup", at = @At("HEAD"))
	private void spatialaudio$beforeCleanup(final CallbackInfo ci) {
		AppleRenderer.get().onLibraryCleanup();
		AudioSystem.get().onLibraryCleanup();
	}
}
