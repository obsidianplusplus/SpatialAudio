package io.github.obsidianplusplus.spatialaudio.config;

import net.minecraft.sounds.SoundSource;

/**
 * 单个声音类别（音乐、方块、敌对生物……）的空间音频设置。
 *
 * <p>Minecraft 的所有声音都归属于 {@link SoundSource} 中的某个类别，
 * 本模组按类别决定是否做三维定位、是否计算遮挡、混响发送量以及是否锚定屏幕。
 */
public final class CategorySettings {
	/** 是否参与三维空间化（对立体声文件强制按声源位置定位）。 */
	public boolean spatialize = true;

	/** 是否计算方块遮挡与绕射。 */
	public boolean occlusion = true;

	/** 发送到环境混响的比例，0 表示完全干声，1 为标准，最大 2。 */
	public float reverbSend = 1.0f;

	/** 非定位（跟随听者）的声音是否锚定在屏幕方向，转头时声音保持在屏幕前方。 */
	public boolean screenAnchored = false;

	/**
	 * 无参构造，供 Gson 反序列化使用。
	 */
	public CategorySettings() {
	}

	/**
	 * 完整构造。
	 *
	 * @param spatialize 是否三维定位
	 * @param occlusion 是否计算遮挡
	 * @param reverbSend 混响发送量
	 * @param screenAnchored 是否锚定屏幕
	 */
	public CategorySettings(final boolean spatialize, final boolean occlusion, final float reverbSend, final boolean screenAnchored) {
		this.spatialize = spatialize;
		this.occlusion = occlusion;
		this.reverbSend = reverbSend;
		this.screenAnchored = screenAnchored;
	}

	/**
	 * 返回指定声音类别的默认设置。
	 *
	 * <p>音乐与界面音效默认不加混响、不做遮挡，并锚定到屏幕；
	 * 唱片机、天气、方块、生物、玩家与语音等世界内声音全部参与声学模拟。
	 *
	 * @param source 声音类别
	 * @return 新建的默认设置
	 */
	public static CategorySettings defaultsFor(final SoundSource source) {
		return switch (source) {
			case MUSIC -> new CategorySettings(false, false, 0.0f, true);
			case UI -> new CategorySettings(false, false, 0.0f, true);
			case WEATHER -> new CategorySettings(true, true, 0.6f, false);
			case AMBIENT -> new CategorySettings(true, true, 0.8f, true);
			case RECORDS, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, VOICE, MASTER -> new CategorySettings(true, true, 1.0f, false);
		};
	}

	/**
	 * 把各字段限制在合法范围内。
	 */
	public void sanitize() {
		if (!Float.isFinite(this.reverbSend)) {
			this.reverbSend = 1.0f;
		}
		this.reverbSend = Math.clamp(this.reverbSend, 0.0f, 2.0f);
	}

	/**
	 * 深拷贝当前设置。
	 *
	 * @return 新对象
	 */
	public CategorySettings copy() {
		return new CategorySettings(this.spatialize, this.occlusion, this.reverbSend, this.screenAnchored);
	}
}
