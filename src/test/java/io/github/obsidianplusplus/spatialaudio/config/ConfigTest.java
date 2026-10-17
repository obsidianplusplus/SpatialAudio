package io.github.obsidianplusplus.spatialaudio.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;

/**
 * 配置默认值、合法性修正与序列化测试。
 */
class ConfigTest {
	/** 与 ConfigManager 相同配置的 Gson。 */
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 每个声音类别都有默认设置；音乐与界面默认锚定屏幕且不加混响。
	 */
	@Test
	void everyCategoryHasDefaults() {
		final SpatialAudioConfig config = new SpatialAudioConfig();
		for (final SoundSource source : SoundSource.values()) {
			assertNotNull(config.categories.get(source.getName()), source.getName());
		}
		assertTrue(config.category(SoundSource.MUSIC).screenAnchored);
		assertEquals(0.0f, config.category(SoundSource.UI).reverbSend);
		assertTrue(config.category(SoundSource.HOSTILE).occlusion);
		assertTrue(config.category(SoundSource.RECORDS).spatialize);
	}

	/**
	 * 非法数值被修正到合法范围，缺失字段被补全。
	 */
	@Test
	void sanitizeFixesInvalidValues() {
		final SpatialAudioConfig config = GSON.fromJson("""
			{
			  "udpPort": 99999,
			  "trackingStrength": "NaN",
			  "predictionMs": -5,
			  "probeRays": 2,
			  "hrtfMode": "NOT_A_MODE",
			  "categories": {"music": {"reverbSend": 9.0}},
			  "blockOcclusionOverrides": {" Minecraft:Glass ": 0.3, "": 1.0}
			}
			""", SpatialAudioConfig.class);
		config.sanitize();
		assertEquals(65535, config.udpPort);
		assertEquals(1.0f, config.trackingStrength);
		assertEquals(0.0f, config.predictionMs);
		assertEquals(16, config.probeRays);
		assertEquals(HrtfMode.FORCE_ON, config.hrtfMode);
		assertEquals(2.0f, config.category(SoundSource.MUSIC).reverbSend);
		assertNotNull(config.categories.get("block"));
		assertEquals(0.3f, config.blockOcclusionOverrides.get("minecraft:glass"));
		assertFalse(config.blockOcclusionOverrides.containsKey(""));
	}

	/**
	 * 序列化后再反序列化保持一致。
	 */
	@Test
	void jsonRoundTrip() {
		final SpatialAudioConfig config = new SpatialAudioConfig();
		config.trackingSource = HeadTrackingSource.OPENTRACK;
		config.recenterMode = RecenterMode.YAW_ONLY;
		config.category(SoundSource.WEATHER).reverbSend = 0.25f;
		final SpatialAudioConfig back = GSON.fromJson(GSON.toJson(config), SpatialAudioConfig.class);
		back.sanitize();
		assertEquals(HeadTrackingSource.OPENTRACK, back.trackingSource);
		assertEquals(RecenterMode.YAW_ONLY, back.recenterMode);
		assertEquals(0.25f, back.category(SoundSource.WEATHER).reverbSend);
	}
}
