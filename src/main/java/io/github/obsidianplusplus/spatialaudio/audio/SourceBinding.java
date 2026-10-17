package io.github.obsidianplusplus.spatialaudio.audio;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * 声音实例播放时由主线程传递给声音线程的绑定信息。
 *
 * @param category 声音类别
 * @param soundId 声音事件 ID
 * @param relative 是否跟随听者（相对坐标）
 * @param attenuated 是否有距离衰减
 * @param initialTarget 播放瞬间计算好的声学目标，保证声音一开始就带有正确的遮挡效果
 */
public record SourceBinding(
	SoundSource category,
	Identifier soundId,
	boolean relative,
	boolean attenuated,
	AcousticTarget initialTarget
) {
}
