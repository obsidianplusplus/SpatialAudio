package io.github.obsidianplusplus.spatialaudio.headtracking;

import java.util.Locale;

/**
 * 头部追踪的当前状态，用于界面提示与故障排查。
 */
public enum TrackerStatus {
	/** 头部追踪已关闭。 */
	DISABLED(false, 0xFF9E9E9E),
	/** 正在等待数据。 */
	WAITING_FOR_DATA(false, 0xFFFFC107),
	/** 正在接收 AirPods 姿态数据。 */
	ACTIVE_AIRPODS(true, 0xFF4CAF50),
	/** 正在接收 OpenTrack 姿态数据。 */
	ACTIVE_OPENTRACK(true, 0xFF4CAF50),
	/** 助手正在启动。 */
	HELPER_STARTING(false, 0xFFFFC107),
	/** 正在本机编译助手（首次使用且 jar 内无预编译版本时）。 */
	HELPER_COMPILING(false, 0xFFFFC107),
	/** 找不到助手且本机没有 Xcode 命令行工具，无法编译。 */
	HELPER_NOT_FOUND(false, 0xFFF44336),
	/** 助手启动失败。 */
	HELPER_FAILED(false, 0xFFF44336),
	/** 助手已启动但长时间没有回应。 */
	HELPER_NO_RESPONSE(false, 0xFFF44336),
	/** 助手报告了运动数据错误。 */
	HELPER_ERROR(false, 0xFFF44336),
	/** macOS 版本过低（需要 macOS 14 Sonoma 或更新）。 */
	UNSUPPORTED_OS(false, 0xFFF44336),
	/** 当前系统或耳机不支持头部运动数据。 */
	NOT_AVAILABLE(false, 0xFFF44336),
	/** 用户拒绝了“运动与健身”权限。 */
	NOT_AUTHORIZED(false, 0xFFF44336),
	/** 正在等待用户授权“运动与健身”权限。 */
	AWAITING_AUTHORIZATION(false, 0xFFFFC107),
	/** 没有连接支持头部追踪的耳机，或耳机未佩戴。 */
	WAITING_FOR_HEADPHONES(false, 0xFFFFC107),
	/** 配置的 UDP 端口被占用，已改用临时端口（OpenTrack 无法连接）。 */
	PORT_IN_USE(false, 0xFFFF9800);

	/** 是否正在提供有效的头部姿态。 */
	private final boolean active;

	/** 界面显示颜色（ARGB）。 */
	private final int color;

	TrackerStatus(final boolean active, final int color) {
		this.active = active;
		this.color = color;
	}

	/**
	 * 是否正在提供有效的头部姿态。
	 *
	 * @return 有效时返回 true
	 */
	public boolean isActive() {
		return this.active;
	}

	/**
	 * 界面显示颜色。
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
		return "spatialaudio.status." + this.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 状态说明（排障提示）的翻译键。
	 *
	 * @return 翻译键
	 */
	public String hintKey() {
		return this.translationKey() + ".hint";
	}
}
