package io.github.obsidianplusplus.spatialaudio.config;

import java.util.Locale;

/**
 * 空间音频的渲染方式：由谁完成声音的三维定位、双耳渲染与头部追踪。
 *
 * <p>三种方式共享本模组的声学模拟（混响、遮挡、绕射、回声、空气吸收、介质滤波、多普勒），
 * 区别只在“最后一步”由谁把声场渲染成耳机里的双耳声音。
 */
public enum SpatialRenderer {
	/** 模组内置：OpenAL Soft HRTF 双耳渲染，头部追踪作用于 OpenAL 听者（默认，适用于所有系统与耳机）。 */
	BUILT_IN,
	/**
	 * Apple 空间音频：OpenAL 只渲染以摄像机为参照的多声道声场（Ambisonics 或 7.1），
	 * 由 macOS 助手交给 Apple 的空间音频引擎（AVAudioEnvironmentNode）完成双耳渲染与头部追踪。
	 */
	APPLE,
	/**
	 * Apple 系统“空间化立体声”（兼容模式）：本模组只输出普通立体声，
	 * 由用户在 macOS 的 AirPods 菜单中为 Minecraft 打开“空间化立体声 → 头部追踪”。
	 */
	APPLE_SYSTEM_STEREO;

	/**
	 * 获取用于界面显示的翻译键。
	 *
	 * @return 翻译键
	 */
	public String translationKey() {
		return "spatialaudio.renderer." + this.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 本模组是否自己把头部旋转作用到 OpenAL 听者上（只有内置渲染器如此，Apple 两种方式由助手或系统完成）。
	 *
	 * @return 由 OpenAL 听者承担头部旋转时返回 true
	 */
	public boolean rotatesOpenAlListener() {
		return this == BUILT_IN;
	}
}
