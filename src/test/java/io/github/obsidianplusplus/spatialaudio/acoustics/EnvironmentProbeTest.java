package io.github.obsidianplusplus.spatialaudio.acoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 环境探测与混响模型测试。
 */
class EnvironmentProbeTest {
	/** 测试用石材。 */
	private static final Material STONE = new Material(1.0f, 0.03f, true, false);

	/** 测试用羊毛。 */
	private static final Material WOOL = new Material(1.5f, 0.75f, true, false);

	/**
	 * 运行一整轮探测。
	 *
	 * @param world 世界
	 * @param x 听者 x
	 * @param y 听者 y
	 * @param z 听者 z
	 * @return 快照
	 */
	private static EnvironmentSnapshot probe(final BlockQuery world, final double x, final double y, final double z) {
		final EnvironmentProbe probe = new EnvironmentProbe();
		probe.step(world, x, y, z, 64, 64, 48, 96);
		return probe.latest();
	}

	/**
	 * 开阔地：封闭度为 0，没有混响。
	 */
	@Test
	void openFieldIsDry() {
		final EnvironmentSnapshot env = probe(new TestWorld(), 0.5, 10.5, 0.5);
		assertEquals(0.0f, env.enclosure(), 1.0e-6f);
		assertEquals(1.0f, env.skyOpenness(), 1.0e-6f);
		assertEquals(ReverbParams.DRY, ReverbModel.reverbFor(env));
	}

	/**
	 * 只有地面的平原：约一半射线命中地面，向上射线全部逃逸，混响很短。
	 */
	@Test
	void flatGroundIsMostlyOpen() {
		final TestWorld world = new TestWorld().fill(-48, 0, -48, 48, 0, 48, STONE);
		final EnvironmentSnapshot env = probe(world, 0.5, 2.6, 0.5);
		assertTrue(env.enclosure() > 0.3f && env.enclosure() < 0.6f, "封闭度约一半，实际 " + env.enclosure());
		assertEquals(1.0f, env.skyOpenness(), 1.0e-6f);
		assertTrue(env.rt60() < 1.0f, "开阔地混响应很短，实际 " + env.rt60());
	}

	/**
	 * 封闭石室：完全封闭、天空不可见，混响时间明显更长；越大的石室混响越长。
	 */
	@Test
	void stoneRoomsReverberate() {
		final EnvironmentSnapshot small = probe(new TestWorld().hollowRoom(0, 4, STONE), 2.5, 2.5, 2.5);
		final EnvironmentSnapshot large = probe(new TestWorld().hollowRoom(0, 20, STONE), 10.5, 10.5, 10.5);
		assertEquals(1.0f, small.enclosure(), 1.0e-6f);
		assertEquals(0.0f, small.skyOpenness(), 1.0e-6f);
		assertTrue(small.rt60() > 0.5f, "小石室应有可感知混响，实际 " + small.rt60());
		assertTrue(large.rt60() > small.rt60(), "大石室混响应更长");
	}

	/**
	 * 羊毛房间吸声强，混响比同尺寸石室短得多。
	 */
	@Test
	void woolRoomIsDead() {
		final EnvironmentSnapshot stone = probe(new TestWorld().hollowRoom(0, 6, STONE), 3.5, 3.5, 3.5);
		final EnvironmentSnapshot wool = probe(new TestWorld().hollowRoom(0, 6, WOOL), 3.5, 3.5, 3.5);
		assertTrue(wool.rt60() < stone.rt60() * 0.2f);
		assertTrue(ReverbModel.reverbFor(wool).decayHFRatio() < ReverbModel.reverbFor(stone).decayHFRatio());
	}

	/**
	 * 生成的混响参数全部位于 EFX 合法范围内。
	 */
	@Test
	void reverbParamsAreInRange() {
		final EnvironmentSnapshot env = probe(new TestWorld().hollowRoom(0, 40, STONE), 20.5, 20.5, 20.5);
		final ReverbParams p = ReverbModel.reverbFor(env);
		assertTrue(p.decayTime() >= 0.1f && p.decayTime() <= 20.0f);
		assertTrue(p.reflectionsDelay() >= 0.0f && p.reflectionsDelay() <= 0.3f);
		assertTrue(p.lateReverbDelay() >= 0.0f && p.lateReverbDelay() <= 0.1f);
		assertTrue(p.gain() >= 0.0f && p.gain() <= 1.0f);
		assertEquals(p, p.clamped());
	}

	/**
	 * 峡谷：两侧远处有高墙、头顶开阔，应产生回声。
	 */
	@Test
	void canyonProducesEcho() {
		// 四周 52 格外是高墙（超出混响探测距离，但在回声探测距离内），头顶开阔。
		final TestWorld world = new TestWorld()
			.fill(-60, 0, -60, 60, 0, 60, STONE)
			.fill(-60, 1, -52, 60, 30, -52, STONE)
			.fill(-60, 1, 52, 60, 30, 52, STONE)
			.fill(-52, 1, -60, -52, 30, 60, STONE)
			.fill(52, 1, -60, 52, 30, 60, STONE);
		final EnvironmentSnapshot env = probe(world, 0.5, 2.6, 0.5);
		assertTrue(env.echoDistance() > 45.0f, "回声面距离应在 52 格以上，实际 " + env.echoDistance());
		final EchoParams echo = ReverbModel.echoFor(env);
		assertTrue(echo.strength() > 0.0f, "应产生回声");
		assertTrue(echo.delay() > 0.2f, "50 格以上往返超过 0.29 秒");
	}
}
