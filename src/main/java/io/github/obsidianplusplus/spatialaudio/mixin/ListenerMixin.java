package io.github.obsidianplusplus.spatialaudio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Listener;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 听者注入：把原版写入的“摄像机朝向”替换为“摄像机朝向 × 真实头部旋转”，实现头部追踪。
 *
 * <p>原版 {@code Listener#setTransform} 只保存摄像机变换，本注入不改变保存的值（其它代码读取到的仍是摄像机朝向），
 * 只改变实际写入 OpenAL 的朝向与位置。
 */
@Mixin(Listener.class)
public abstract class ListenerMixin {
	/**
	 * 包装朝向写入。
	 *
	 * @param param 参数名（AL_ORIENTATION）
	 * @param values 原版朝向
	 * @param original 原始调用
	 */
	@WrapOperation(method = "setTransform", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alListenerfv(I[F)V"))
	private void spatialaudio$wrapOrientation(final int param, final float[] values, final Operation<Void> original) {
		original.call(param, AudioSystem.get().listener().onCameraOrientation(values));
	}

	/**
	 * 包装位置写入（记录速度并叠加可选的头部平移）。
	 *
	 * @param param 参数名（AL_POSITION）
	 * @param x 坐标 x
	 * @param y 坐标 y
	 * @param z 坐标 z
	 * @param original 原始调用
	 */
	@WrapOperation(method = "setTransform", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alListener3f(IFFF)V"))
	private void spatialaudio$wrapPosition(final int param, final float x, final float y, final float z, final Operation<Void> original) {
		final float[] position = AudioSystem.get().listener().onCameraPosition(x, y, z);
		original.call(param, position[0], position[1], position[2]);
	}
}
