package io.github.obsidianplusplus.spatialaudio.apple;

import java.util.Locale;

/**
 * 渲染方式的运行状态，用于设置界面、调试叠加层与命令输出。
 */
public enum AppleRendererStatus {
	/** 使用模组内置渲染器。 */
	BUILT_IN(0xFFB0BEC5),
	/** 使用 Apple 系统空间化立体声（兼容模式），需要用户在 macOS 中打开。 */
	SYSTEM_STEREO(0xFF80DEEA),
	/** 系统不满足要求（非 macOS 或低于 macOS 14），已使用模组内置渲染器。 */
	UNSUPPORTED_OS(0xFFFF8A65),
	/** 正在启动助手或等待助手连接。 */
	STARTING(0xFFFFC107),
	/** 助手已连接，正在建立 Apple 渲染引擎。 */
	CONNECTING_ENGINE(0xFFFFC107),
	/** Apple 空间音频正在渲染。 */
	ACTIVE(0xFF4CAF50),
	/** 助手报告 Apple 渲染引擎出错。 */
	ENGINE_ERROR(0xFFFF5252),
	/** Apple 渲染不可用，已自动回退到模组内置渲染器。 */
	FALLBACK(0xFFFF8A65);

	/** 显示颜色（ARGB）。 */
	private final int color;

	/**
	 * 创建状态常量。
	 *
	 * @param color 显示颜色
	 */
	AppleRendererStatus(final int color) {
		this.color = color;
	}

	/**
	 * 显示颜色。
	 *
	 * @return ARGB 颜色
	 */
	public int color() {
		return this.color;
	}

	/**
	 * 状态名称的翻译键。
	 *
	 * @return 翻译键
	 */
	public String translationKey() {
		return "spatialaudio.apple_status." + this.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 排障提示的翻译键。
	 *
	 * @return 翻译键
	 */
	public String hintKey() {
		return this.translationKey() + ".hint";
	}

	/**
	 * 是否属于需要用户关注的状态（界面会附带排障提示）。
	 *
	 * @return 需要提示时返回 true
	 */
	public boolean needsAttention() {
		return this == UNSUPPORTED_OS || this == ENGINE_ERROR || this == FALLBACK || this == SYSTEM_STEREO;
	}
}
