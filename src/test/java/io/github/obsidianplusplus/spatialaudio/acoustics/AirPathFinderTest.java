package io.github.obsidianplusplus.spatialaudio.acoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 空气路径搜索测试。
 */
class AirPathFinderTest {
	/** 测试用石材。 */
	private static final Material STONE = new Material(1.0f, 0.03f, true, false);

	/** 测试用树叶（可通行但有轻微衰减）。 */
	private static final Material LEAVES = new Material(0.25f, 0.45f, true, false);

	/**
	 * 无障碍时直接连通，路程等于直线距离，没有拐点。
	 */
	@Test
	void straightLine() {
		final AirPathFinder.Path path = AirPathFinder.find(new TestWorld(), 0.5, 0.5, 0.5, 6.5, 0.5, 0.5, 2000);
		assertTrue(path.found());
		assertEquals(0, path.corners());
		assertEquals(6.0, path.length(), 1.0e-9);
	}

	/**
	 * 墙上有门洞：路径经过门洞，拐点数为 1~2，第一拐点方向指向门洞一侧，路程比直线长。
	 */
	@Test
	void pathGoesThroughDoorway() {
		final TestWorld world = new TestWorld().fill(4, -10, -20, 4, 10, 20, STONE).clear(4, 0, 5).clear(4, 1, 5);
		final AirPathFinder.Path path = AirPathFinder.find(world, 0.5, 0.5, 0.5, 7.5, 0.5, 0.5, 4000);
		assertTrue(path.found());
		assertTrue(path.corners() >= 1 && path.corners() <= 3, "拐点数 " + path.corners());
		assertTrue(path.length() > 7.0 && path.length() < 14.0, "路程 " + path.length());
		assertTrue(path.directionZ() > 0.5, "应朝门洞方向，实际 " + path.directionZ());
	}

	/**
	 * 完全封闭时找不到通路；访问格子数达到上限时也会放弃。
	 */
	@Test
	void sealedOrBudgetExceeded() {
		final TestWorld sealed = new TestWorld().hollowRoom(0, 4, STONE);
		assertFalse(AirPathFinder.find(sealed, 2.5, 2.5, 2.5, 12.5, 2.5, 2.5, 4000).found());
		final TestWorld door = new TestWorld().fill(4, -10, -20, 4, 10, 20, STONE).clear(4, 0, 5).clear(4, 1, 5);
		assertFalse(AirPathFinder.find(door, 0.5, 0.5, 0.5, 7.5, 0.5, 0.5, 20).found());
	}

	/**
	 * 穿过树叶等半透声格子时透射系数小于 1。
	 */
	@Test
	void foliageReducesTransmission() {
		final TestWorld world = new TestWorld().fill(3, -10, -10, 3, 10, 10, LEAVES);
		final AirPathFinder.Path path = AirPathFinder.find(world, 0.5, 0.5, 0.5, 6.5, 0.5, 0.5, 2000);
		assertTrue(path.found());
		assertTrue(path.transmission() < 1.0 && path.transmission() > 0.5, "透射系数 " + path.transmission());
	}

	/**
	 * 负坐标与高处坐标的打包解包正确（通过远离原点的场景验证）。
	 */
	@Test
	void worksFarFromOrigin() {
		final AirPathFinder.Path path = AirPathFinder.find(new TestWorld(), -1000.5, 200.5, -3000.5, -995.5, 200.5, -3000.5, 2000);
		assertTrue(path.found());
		assertEquals(5.0, path.length(), 1.0e-9);
	}
}
