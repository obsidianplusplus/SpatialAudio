package io.github.obsidianplusplus.spatialaudio.config;

import java.util.Locale;

/**
 * 头部姿态“回中”方式。
 */
public enum RecenterMode {
	/** 完全回中：把回中瞬间的头部姿态（偏航、俯仰、横滚）全部视为“正对屏幕”。适合笔记本低头使用。 */
	FULL,
	/** 仅回中偏航：只重置左右转头角度，俯仰与横滚保持以重力方向为基准。 */
	YAW_ONLY;

	/**
	 * 获取用于界面显示的翻译键。
	 *
	 * @return 翻译键
	 */
	public String translationKey() {
		return "spatialaudio.recenter_mode." + this.name().toLowerCase(Locale.ROOT);
	}
}
