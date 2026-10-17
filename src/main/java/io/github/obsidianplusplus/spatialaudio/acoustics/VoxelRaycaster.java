package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 体素射线遍历（Amanatides & Woo 1987，《A Fast Voxel Traversal Algorithm for Ray Tracing》）。
 *
 * <p>沿射线按顺序访问其穿过的每一个方块格，并给出射线在该格内的进入与离开距离，
 * 从而可以按“穿过的实际厚度”累计遮挡。相比逐步采样，它不会漏掉薄角落，且每步只需几次加法。
 */
public final class VoxelRaycaster {
	/** 单条射线最多访问的方块数，防止异常数据导致死循环。 */
	private static final int MAX_STEPS = 1024;

	private VoxelRaycaster() {
	}

	/**
	 * 方块访问回调。
	 */
	@FunctionalInterface
	public interface Visitor {
		/**
		 * 访问射线穿过的一个方块格。
		 *
		 * @param x 方块 x
		 * @param y 方块 y
		 * @param z 方块 z
		 * @param enter 射线进入该格时距起点的距离
		 * @param exit 射线离开该格（或到达终点）时距起点的距离
		 * @return 返回 false 立即停止遍历
		 */
		boolean visit(int x, int y, int z, double enter, double exit);
	}

	/**
	 * 从起点沿单位方向遍历，直到达到最大距离或回调要求停止。
	 *
	 * @param ox 起点 x
	 * @param oy 起点 y
	 * @param oz 起点 z
	 * @param dx 方向 x（需归一化）
	 * @param dy 方向 y
	 * @param dz 方向 z
	 * @param maxDistance 最大距离
	 * @param visitor 回调
	 */
	public static void traverse(final double ox, final double oy, final double oz,
			final double dx, final double dy, final double dz, final double maxDistance, final Visitor visitor) {
		if (!(maxDistance > 0.0) || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)) {
			return;
		}
		int x = (int) Math.floor(ox);
		int y = (int) Math.floor(oy);
		int z = (int) Math.floor(oz);
		final int stepX = dx > 0.0 ? 1 : (dx < 0.0 ? -1 : 0);
		final int stepY = dy > 0.0 ? 1 : (dy < 0.0 ? -1 : 0);
		final int stepZ = dz > 0.0 ? 1 : (dz < 0.0 ? -1 : 0);
		final double deltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
		final double deltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
		final double deltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;
		double tMaxX = stepX > 0 ? (x + 1.0 - ox) * deltaX : (stepX < 0 ? (ox - x) * deltaX : Double.POSITIVE_INFINITY);
		double tMaxY = stepY > 0 ? (y + 1.0 - oy) * deltaY : (stepY < 0 ? (oy - y) * deltaY : Double.POSITIVE_INFINITY);
		double tMaxZ = stepZ > 0 ? (z + 1.0 - oz) * deltaZ : (stepZ < 0 ? (oz - z) * deltaZ : Double.POSITIVE_INFINITY);
		double t = 0.0;
		for (int i = 0; i < MAX_STEPS; i++) {
			final double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
			final double exit = Math.min(next, maxDistance);
			if (!visitor.visit(x, y, z, t, exit) || next >= maxDistance) {
				return;
			}
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				x += stepX;
				t = tMaxX;
				tMaxX += deltaX;
			} else if (tMaxY <= tMaxZ) {
				y += stepY;
				t = tMaxY;
				tMaxY += deltaY;
			} else {
				z += stepZ;
				t = tMaxZ;
				tMaxZ += deltaZ;
			}
		}
	}

	/**
	 * 遍历两点之间的线段。
	 *
	 * @param ax 起点 x
	 * @param ay 起点 y
	 * @param az 起点 z
	 * @param bx 终点 x
	 * @param by 终点 y
	 * @param bz 终点 z
	 * @param visitor 回调
	 */
	public static void traverseSegment(final double ax, final double ay, final double az,
			final double bx, final double by, final double bz, final Visitor visitor) {
		final double dx = bx - ax;
		final double dy = by - ay;
		final double dz = bz - az;
		final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0e-9) {
			return;
		}
		traverse(ax, ay, az, dx / length, dy / length, dz / length, length, visitor);
	}
}
