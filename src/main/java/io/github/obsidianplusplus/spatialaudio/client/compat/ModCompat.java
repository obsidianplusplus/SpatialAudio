package io.github.obsidianplusplus.spatialaudio.client.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 与其它声音类模组的兼容性检测。
 */
public final class ModCompat {
	/** Sound Physics Remastered 的模组 ID。 */
	public static final String SOUND_PHYSICS_REMASTERED = "sound_physics_remastered";

	/** Simple Voice Chat 的模组 ID。 */
	public static final String SIMPLE_VOICE_CHAT = "voicechat";

	private ModCompat() {
	}

	/**
	 * 是否安装了 Sound Physics Remastered。它同样修改声源的滤波器与混响发送，
	 * 两者同时工作会互相覆盖，因此默认由它负责声学、本模组只负责 HRTF 与头部追踪。
	 *
	 * @return 已安装时返回 true
	 */
	public static boolean isSoundPhysicsLoaded() {
		return FabricLoader.getInstance().isModLoaded(SOUND_PHYSICS_REMASTERED);
	}

	/**
	 * 是否安装了 Simple Voice Chat（其语音使用独立的 OpenAL 设备与上下文播放，不经过原版声音引擎，本模组不处理语音）。
	 *
	 * @return 已安装时返回 true
	 */
	public static boolean isVoiceChatLoaded() {
		return FabricLoader.getInstance().isModLoaded(SIMPLE_VOICE_CHAT);
	}
}
