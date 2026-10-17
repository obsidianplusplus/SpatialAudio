package io.github.obsidianplusplus.spatialaudio.acoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.obsidianplusplus.spatialaudio.audio.AcousticTarget;
import org.junit.jupiter.api.Test;

/**
 * 遮挡与绕射求解测试。
 */
class OcclusionSolverTest {
	/** 测试用石材。 */
	private static final Material STONE = new Material(1.0f, 0.03f, true, false);

	/** 测试用羊毛。 */
	private static final Material WOOL = new Material(1.5f, 0.75f, true, false);

	/**
	 * 无遮挡时增益为 1。
	 */
	@Test
	void clearPathIsUnoccluded() {
		final AcousticTarget target = OcclusionSolver.solve(new TestWorld(), 0.5, 0.5, 0.5, 8.5, 0.5, 0.5, 1.0, 4000, true);
		assertEquals(1.0f, target.directGain(), 1.0e-6f);
		assertEquals(1.0f, target.directGainHF(), 1.0e-6f);
		assertEquals(0.0f, target.redirectBlend(), 1.0e-6f);
	}

	/**
	 * 一堵石墙让高频显著衰减，墙越厚越闷。
	 */
	@Test
	void thickerWallsMuffleMore() {
		final TestWorld thin = new TestWorld().fill(4, -20, -20, 4, 20, 20, STONE);
		final TestWorld thick = new TestWorld().fill(3, -20, -20, 5, 20, 20, STONE);
		final AcousticTarget a = OcclusionSolver.solve(thin, 0.5, 0.5, 0.5, 8.5, 0.5, 0.5, 1.0, 0, false);
		final AcousticTarget b = OcclusionSolver.solve(thick, 0.5, 0.5, 0.5, 8.5, 0.5, 0.5, 1.0, 0, false);
		assertEquals(Math.exp(-1.0), a.directGainHF(), 1.0e-3);
		assertTrue(b.directGainHF() < a.directGainHF());
		assertTrue(b.directGain() < a.directGain());
		assertTrue(a.directGain() > 0.85f, "一格墙对低频只有轻微衰减");
	}

	/**
	 * 羊毛比同样厚度的石头隔音效果更好。
	 */
	@Test
	void woolInsulatesBetterThanStone() {
		final TestWorld stone = new TestWorld().fill(4, -20, -20, 4, 20, 20, STONE);
		final TestWorld wool = new TestWorld().fill(4, -20, -20, 4, 20, 20, WOOL);
		final AcousticTarget s = OcclusionSolver.solve(stone, 0.5, 0.5, 0.5, 8.5, 0.5, 0.5, 1.0, 0, false);
		final AcousticTarget w = OcclusionSolver.solve(wool, 0.5, 0.5, 0.5, 8.5, 0.5, 0.5, 1.0, 0, false);
		assertTrue(w.directGainHF() < s.directGainHF());
	}

	/**
	 * 墙上开门洞时，声音通过门洞绕射：增益高于纯直达路径，且方向偏向门洞。
	 */
	@Test
	void doorwayCreatesDiffractionPath() {
		// 一面位于 x=4 的大墙，门洞在 z=5 处（离直线路径 5 格）。
		final TestWorld world = new TestWorld().fill(4, -10, -20, 4, 10, 20, STONE);
		world.clear(4, 0, 5).clear(4, 1, 5);
		final AcousticTarget direct = OcclusionSolver.solve(world, 0.5, 0.5, 0.5, 7.5, 0.5, 0.5, 1.0, 0, true);
		final AcousticTarget indirect = OcclusionSolver.solve(world, 0.5, 0.5, 0.5, 7.5, 0.5, 0.5, 1.0, 4000, true);
		assertTrue(indirect.directGainHF() > direct.directGainHF(), "绕射路径应让声音不那么闷");
		assertTrue(indirect.redirectBlend() > 0.0f, "应产生重定向");
		assertTrue(indirect.redirectZ() > 0.2, "感知方向应偏向门洞所在的 +z 侧，实际 " + indirect.redirectZ());
	}

	/**
	 * 完全封闭的房间里没有任何绕射路径。
	 */
	@Test
	void sealedRoomHasNoPath() {
		final TestWorld world = new TestWorld().hollowRoom(0, 4, STONE);
		final AcousticTarget target = OcclusionSolver.solve(world, 2.5, 2.5, 2.5, 12.5, 2.5, 2.5, 1.0, 4000, true);
		assertEquals(0.0f, target.redirectBlend(), 1.0e-6f);
		assertTrue(target.directGainHF() < 0.5f);
	}

	/**
	 * 声源所在方块与听者所在方块不计入遮挡（例如音符盒、唱片机自身）。
	 */
	@Test
	void endpointsAreSkipped() {
		final TestWorld world = new TestWorld().set(8, 0, 0, STONE).set(0, 0, 0, STONE);
		final double occlusion = OcclusionSolver.occlusionBetween(world, 0.5, 0.5, 0.5, 8.5, 0.5, 0.5, 10.0, true, true);
		assertEquals(0.0, occlusion, 1.0e-9);
	}
}
