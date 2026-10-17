package io.github.obsidianplusplus.spatialaudio.config;

import java.util.Locale;

/**
 * 头部追踪数据来源。
 */
public enum HeadTrackingSource {
	/** 自动：macOS 上启动 AirPods 助手，同时接受 OpenTrack UDP 数据。 */
	AUTO,
	/** 仅使用 AirPods（通过 macOS 助手读取 CoreMotion 耳机姿态）。 */
	AIRPODS,
	/** 仅接收 OpenTrack「UDP over network」协议数据（支持任意 OpenTrack 兼容追踪器）。 */
	OPENTRACK,
	/** 关闭头部追踪。 */
	DISABLED;

	/**
	 * 是否需要启动 macOS AirPods 助手。
	 *
	 * @return 需要启动时返回 true
	 */
	public boolean usesAirPodsHelper() {
		return this == AUTO || this == AIRPODS;
	}

	/**
	 * 是否接受 OpenTrack UDP 数据包。
	 *
	 * @return 接受时返回 true
	 */
	public boolean acceptsOpenTrack() {
		return this == AUTO || this == OPENTRACK;
	}

	/**
	 * 获取用于界面显示的翻译键。
	 *
	 * @return 翻译键
	 */
	public String translationKey() {
		return "spatialaudio.tracking_source." + this.name().toLowerCase(Locale.ROOT);
	}
}
