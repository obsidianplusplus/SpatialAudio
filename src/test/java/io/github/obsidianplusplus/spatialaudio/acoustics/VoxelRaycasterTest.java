package io.github.obsidianplusplus.spatialaudio.acoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 体素遍历测试。
 */
class VoxelRaycasterTest {
	/**
	 * 沿 x 轴遍历时依次访问相邻方块，且各段长度之和等于总长度。
	 */
	@Test
	void axisAlignedTraversal() {
		final List<int[]> cells = new ArrayList<>();
		final double[] length = {0.0};
		VoxelRaycaster.traverseSegment(0.5, 0.5, 0.5, 4.5, 0.5, 0.5, (x, y, z, enter, exit) -> {
			cells.add(new int[] {x, y, z});
			length[0] += exit - enter;
			return true;
		});
		assertEquals(5, cells.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(i, cells.get(i)[0]);
		}
		assertEquals(4.0, length[0], 1.0e-9);
	}

	/**
	 * 斜向遍历不会跳过方块：每一步只跨越一个坐标轴。
	 */
	@Test
	void diagonalTraversalIsConnected() {
		final List<int[]> cells = new ArrayList<>();
		VoxelRaycaster.traverseSegment(0.2, 0.3, 0.1, 5.7, 3.9, -2.6, (x, y, z, enter, exit) -> {
			cells.add(new int[] {x, y, z});
			return true;
		});
		for (int i = 1; i < cells.size(); i++) {
			final int[] a = cells.get(i - 1);
			final int[] b = cells.get(i);
			final int manhattan = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
			assertEquals(1, manhattan, "相邻访问的方块必须共面");
		}
		final int[] last = cells.getLast();
		assertEquals(5, last[0]);
		assertEquals(3, last[1]);
		assertEquals(-3, last[2]);
	}

	/**
	 * 回调返回 false 时立即停止。
	 */
	@Test
	void stopsWhenVisitorReturnsFalse() {
		final int[] count = {0};
		VoxelRaycaster.traverse(0.5, 0.5, 0.5, 1.0, 0.0, 0.0, 100.0, (x, y, z, enter, exit) -> ++count[0] < 3);
		assertEquals(3, count[0]);
	}
}
