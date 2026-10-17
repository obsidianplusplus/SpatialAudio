package io.github.obsidianplusplus.spatialaudio.config;

import java.util.Locale;

/**
 * HRTF（头相关传输函数）双耳渲染的启用策略。
 *
 * <p>HRTF 是空间音频的核心：它用人头与耳廓的声学滤波模拟声音从各个方向到达双耳的差异，
 * 使耳机（包括 AirPods）能够还原上下、前后、左右的三维方位感。
 */
public enum HrtfMode {
	/** 强制开启 HRTF，并要求 OpenAL 以立体声 HRTF 模式输出（推荐，AirPods 必选）。 */
	FORCE_ON,
	/** 跟随原版“方向性音频”选项。 */
	FOLLOW_VANILLA,
	/** 关闭 HRTF，仅使用普通立体声声像。 */
	OFF;

	/**
	 * 获取用于界面显示的翻译键。
	 *
	 * @return 翻译键
	 */
	public String translationKey() {
		return "spatialaudio.hrtf_mode." + this.name().toLowerCase(Locale.ROOT);
	}
}
