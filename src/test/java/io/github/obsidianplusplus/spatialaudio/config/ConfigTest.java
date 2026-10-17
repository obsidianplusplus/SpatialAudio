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

	/**
	 * 渲染方式相关字段的默认值：内置渲染器、3 阶 Ambisonics、优先 Apple 自动头部追踪、30 毫秒缓冲。
	 */
	@Test
	void rendererDefaults() {
		final SpatialAudioConfig config = new SpatialAudioConfig();
		assertEquals(SpatialRenderer.BUILT_IN, config.renderer);
		assertEquals(AppleBedFormat.AMBISONICS_3, config.appleBedFormat);
		assertTrue(config.applePreferAutoHeadTracking);
		assertEquals(30, config.appleBufferMs);
		config.sanitize();
		assertEquals(SpatialRenderer.BUILT_IN, config.renderer);
		assertEquals(30, config.appleBufferMs);
		assertTrue(SpatialRenderer.BUILT_IN.rotatesOpenAlListener());
		assertFalse(SpatialRenderer.APPLE.rotatesOpenAlListener());
		assertFalse(SpatialRenderer.APPLE_SYSTEM_STEREO.rotatesOpenAlListener());
	}

	/**
	 * 渲染方式与声场格式为 null 或未知值时恢复默认；缓冲时长被限制在 10~200 毫秒。
	 */
	@Test
	void sanitizeRepairsRendererFields() {
		final SpatialAudioConfig unknown = GSON.fromJson("""
			{
			  "renderer": "DOLBY_ATMOS",
			  "appleBedFormat": "AMBISONICS_9",
			  "appleBufferMs": 5
			}
			""", SpatialAudioConfig.class);
		unknown.sanitize();
		assertEquals(SpatialRenderer.BUILT_IN, unknown.renderer);
		assertEquals(AppleBedFormat.AMBISONICS_3, unknown.appleBedFormat);
		assertEquals(10, unknown.appleBufferMs);

		final SpatialAudioConfig nulls = GSON.fromJson("""
			{
			  "renderer": null,
			  "appleBedFormat": null,
			  "appleBufferMs": 100000
			}
			""", SpatialAudioConfig.class);
		nulls.sanitize();
		assertEquals(SpatialRenderer.BUILT_IN, nulls.renderer);
		assertEquals(AppleBedFormat.AMBISONICS_3, nulls.appleBedFormat);
		assertEquals(200, nulls.appleBufferMs);

		final SpatialAudioConfig direct = new SpatialAudioConfig();
		direct.renderer = null;
		direct.appleBedFormat = null;
		direct.appleBufferMs = Integer.MIN_VALUE;
		direct.sanitize();
		assertEquals(SpatialRenderer.BUILT_IN, direct.renderer);
		assertEquals(AppleBedFormat.AMBISONICS_3, direct.appleBedFormat);
		assertEquals(10, direct.appleBufferMs);

		final SpatialAudioConfig inRange = new SpatialAudioConfig();
		inRange.appleBufferMs = 120;
		inRange.sanitize();
		assertEquals(120, inRange.appleBufferMs);
	}

	/**
	 * 渲染方式相关字段经 JSON 往返（含 ConfigManager 的深拷贝）后保持一致，并以枚举名写入文件。
	 */
	@Test
	void rendererJsonRoundTrip() {
		for (final SpatialRenderer renderer : SpatialRenderer.values()) {
			for (final AppleBedFormat format : AppleBedFormat.values()) {
				final SpatialAudioConfig config = new SpatialAudioConfig();
				config.renderer = renderer;
				config.appleBedFormat = format;
				config.applePreferAutoHeadTracking = false;
				config.appleBufferMs = 75;
				final String json = GSON.toJson(config);
				assertTrue(json.contains("\"renderer\": \"" + renderer.name() + "\""), json);
				assertTrue(json.contains("\"appleBedFormat\": \"" + format.name() + "\""), json);
				final SpatialAudioConfig back = GSON.fromJson(json, SpatialAudioConfig.class);
				back.sanitize();
				assertEquals(renderer, back.renderer);
				assertEquals(format, back.appleBedFormat);
				assertFalse(back.applePreferAutoHeadTracking);
				assertEquals(75, back.appleBufferMs);

				final SpatialAudioConfig copy = ConfigManager.copy(config);
				assertEquals(renderer, copy.renderer);
				assertEquals(format, copy.appleBedFormat);
				assertFalse(copy.applePreferAutoHeadTracking);
				assertEquals(75, copy.appleBufferMs);
			}
		}

		// 旧版本配置文件没有这些字段：读取后使用默认值。
		final SpatialAudioConfig legacy = GSON.fromJson("{\"hrtfMode\": \"OFF\"}", SpatialAudioConfig.class);
		legacy.sanitize();
		assertEquals(HrtfMode.OFF, legacy.hrtfMode);
		assertEquals(SpatialRenderer.BUILT_IN, legacy.renderer);
		assertEquals(AppleBedFormat.AMBISONICS_3, legacy.appleBedFormat);
		assertTrue(legacy.applePreferAutoHeadTracking);
		assertEquals(30, legacy.appleBufferMs);
	}

	/**
	 * 声场格式的声道数、阶数与翻译键；渲染方式的翻译键。
	 */
	@Test
	void bedFormatAndRendererMetadata() {
		assertEquals(16, AppleBedFormat.AMBISONICS_3.channels());
		assertEquals(9, AppleBedFormat.AMBISONICS_2.channels());
		assertEquals(4, AppleBedFormat.AMBISONICS_1.channels());
		assertEquals(8, AppleBedFormat.SURROUND_7_1.channels());
		for (final AppleBedFormat format : AppleBedFormat.values()) {
			if (format.ambisonic()) {
				// Ambisonics 声道数 = (阶数 + 1)²。
				assertEquals((format.ambisonicOrder() + 1) * (format.ambisonicOrder() + 1), format.channels());
			} else {
				assertEquals(0, format.ambisonicOrder());
			}
			assertTrue(format.translationKey().startsWith("spatialaudio.apple_bed."));
		}
		assertFalse(AppleBedFormat.SURROUND_7_1.ambisonic());
		assertEquals("spatialaudio.apple_bed.ambisonics_3", AppleBedFormat.AMBISONICS_3.translationKey());
		assertEquals("spatialaudio.renderer.built_in", SpatialRenderer.BUILT_IN.translationKey());
		assertEquals("spatialaudio.renderer.apple", SpatialRenderer.APPLE.translationKey());
		assertEquals("spatialaudio.renderer.apple_system_stereo", SpatialRenderer.APPLE_SYSTEM_STEREO.translationKey());
	}
}
