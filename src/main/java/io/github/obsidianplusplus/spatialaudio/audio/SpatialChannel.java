package io.github.obsidianplusplus.spatialaudio.audio;

/**
 * 由 Mixin 注入到原版 {@code com.mojang.blaze3d.audio.Channel} 的接口，用于取得附着的空间音频状态。
 */
public interface SpatialChannel {
	/**
	 * 获取该声道的空间音频状态。
	 *
	 * @return 状态对象
	 */
	SpatialSource spatialaudio$state();
}
