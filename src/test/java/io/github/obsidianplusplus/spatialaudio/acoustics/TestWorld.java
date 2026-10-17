package io.github.obsidianplusplus.spatialaudio.acoustics;

import java.util.HashMap;
import java.util.Map;

/**
 * 单元测试用的合成世界：只包含手动放置的完整方块，其余位置为空气。
 */
final class TestWorld implements BlockQuery {
	/** 坐标到材质的映射。 */
	private final Map<Long, Material> blocks = new HashMap<>();

	/**
	 * 放置一个方块。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @param material 材质
	 * @return 自身，便于链式调用
	 */
	TestWorld set(final int x, final int y, final int z, final Material material) {
		this.blocks.put(key(x, y, z), material);
		return this;
	}

	/**
	 * 填充一个长方体区域。
	 *
	 * @param x0 起点 x
	 * @param y0 起点 y
	 * @param z0 起点 z
	 * @param x1 终点 x（含）
	 * @param y1 终点 y（含）
	 * @param z1 终点 z（含）
	 * @param material 材质
	 * @return 自身
	 */
	TestWorld fill(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1, final Material material) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					this.set(x, y, z, material);
				}
			}
		}
		return this;
	}

	/**
	 * 移除一个方块（变为空气）。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @return 自身
	 */
	TestWorld clear(final int x, final int y, final int z) {
		this.blocks.remove(key(x, y, z));
		return this;
	}

	/**
	 * 构建一个空心立方体房间（墙厚 1 格）。
	 *
	 * @param min 内部最小坐标
	 * @param max 内部最大坐标
	 * @param material 墙体材质
	 * @return 自身
	 */
	TestWorld hollowRoom(final int min, final int max, final Material material) {
		for (int x = min - 1; x <= max + 1; x++) {
			for (int y = min - 1; y <= max + 1; y++) {
				for (int z = min - 1; z <= max + 1; z++) {
					final boolean inside = x >= min && x <= max && y >= min && y <= max && z >= min && z <= max;
					if (!inside) {
						this.set(x, y, z, material);
					}
				}
			}
		}
		return this;
	}

	@Override
	public Material material(final int x, final int y, final int z) {
		return this.blocks.getOrDefault(key(x, y, z), Material.AIR);
	}

	@Override
	public boolean segmentHits(final int x, final int y, final int z, final double ax, final double ay, final double az,
			final double bx, final double by, final double bz) {
		return true;
	}

	/**
	 * 坐标编码。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @return 键
	 */
	private static long key(final int x, final int y, final int z) {
		return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | ((long) z & 0x1FFFFFL);
	}
}
