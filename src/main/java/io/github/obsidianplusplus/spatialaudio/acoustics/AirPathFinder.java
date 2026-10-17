package io.github.obsidianplusplus.spatialaudio.acoustics;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 空气路径搜索：在方块网格中寻找声源与听者之间绕过障碍物的最短通路（门洞、窗户、走廊、拐角）。
 *
 * <p>算法：
 * <ol>
 *   <li>以声源所在格为起点，在“可通行”的格子中做 6 邻域加权 A* 搜索（启发函数为到听者的直线距离），
 *       优先朝听者方向扩展，遇到墙体时沿墙面寻找开口；搜索范围限制在以声源与听者为焦点的椭球内，
 *       并限制最多访问的格子数；</li>
 *   <li>得到的格子路径沿坐标轴折来折去，再用“拉绳”（string pulling）做视线平滑：
 *       从听者出发，尽量直接看到路径上更远的点，只在被遮挡处保留拐点；</li>
 *   <li>平滑后的折线长度即声音实际传播的路程；彼此相距很近的拐点（穿过同一个门洞产生的）合并计为一次绕射；
 *       听者看到的第一个拐点方向就是“声音听起来来自的方向”。</li>
 * </ol>
 * 这是游戏音频中常用的“基于寻路的声音传播”近似，结果确定、不会随机闪烁。
 */
public final class AirPathFinder {
	/** 遮挡系数低于该值的格子视为可通行（空气、树叶、植物、水等）。 */
	private static final float PASSABLE_OCCLUSION = 0.5f;

	/** 椭球搜索范围的额外路程余量（格）。 */
	private static final double PATH_SLACK = 12.0;

	/** A* 启发函数权重：大于 1 时搜索更快、路径略次优（拉绳平滑会修正路径形状）。 */
	private static final double HEURISTIC_WEIGHT = 1.5;

	/** 距离小于该值的相邻拐点视为同一次绕射（格）。 */
	private static final double CORNER_MERGE_DISTANCE = 1.8;

	/** 视线判定允许的最大遮挡量。 */
	private static final double LINE_OF_SIGHT_TOLERANCE = 0.2;

	/** 六个邻居方向。 */
	private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private AirPathFinder() {
	}

	/**
	 * 搜索结果。
	 *
	 * @param found 是否找到通路
	 * @param length 平滑后的路程（格）
	 * @param corners 拐点数量
	 * @param directionX 听者看到的第一个拐点方向 x（单位向量）
	 * @param directionY 方向 y
	 * @param directionZ 方向 z
	 * @param transmission 路径穿过树叶、水等半透声格子造成的透射系数（0~1）
	 */
	public record Path(boolean found, double length, int corners, double directionX, double directionY, double directionZ, double transmission) {
		/** 未找到。 */
		public static final Path NONE = new Path(false, 0.0, 0, 0.0, 0.0, 0.0, 0.0);
	}

	/**
	 * 搜索通路。
	 *
	 * @param query 世界查询
	 * @param lx 听者 x
	 * @param ly 听者 y
	 * @param lz 听者 z
	 * @param sx 声源 x
	 * @param sy 声源 y
	 * @param sz 声源 z
	 * @param maxCells 最多访问的格子数
	 * @return 搜索结果
	 */
	public static Path find(final BlockQuery query, final double lx, final double ly, final double lz,
			final double sx, final double sy, final double sz, final int maxCells) {
		final int startX = (int) Math.floor(sx);
		final int startY = (int) Math.floor(sy);
		final int startZ = (int) Math.floor(sz);
		final int goalX = (int) Math.floor(lx);
		final int goalY = (int) Math.floor(ly);
		final int goalZ = (int) Math.floor(lz);
		final long start = pack(startX, startY, startZ);
		final long goal = pack(goalX, goalY, goalZ);
		final double direct = Math.sqrt((lx - sx) * (lx - sx) + (ly - sy) * (ly - sy) + (lz - sz) * (lz - sz));
		final double limit = direct + 2.0 * PATH_SLACK;

		final Long2LongOpenHashMap parents = new Long2LongOpenHashMap();
		final Long2DoubleOpenHashMap costs = new Long2DoubleOpenHashMap();
		costs.defaultReturnValue(Double.POSITIVE_INFINITY);
		final NodeHeap open = new NodeHeap();
		parents.put(start, start);
		costs.put(start, 0.0);
		open.push(start, HEURISTIC_WEIGHT * distance(startX + 0.5, startY + 0.5, startZ + 0.5, lx, ly, lz));
		boolean found = start == goal;
		int expanded = 0;
		while (!found && !open.isEmpty() && expanded < maxCells) {
			final long current = open.pop();
			expanded++;
			final double currentCost = costs.get(current);
			final int cx = unpackX(current);
			final int cy = unpackY(current);
			final int cz = unpackZ(current);
			for (final int[] d : NEIGHBORS) {
				final int nx = cx + d[0];
				final int ny = cy + d[1];
				final int nz = cz + d[2];
				final long next = pack(nx, ny, nz);
				final double nextCost = currentCost + 1.0;
				if (nextCost >= costs.get(next)) {
					continue;
				}
				// 椭球裁剪：经过该格的路程下限超过余量则不再扩展。
				final double viaX = nx + 0.5;
				final double viaY = ny + 0.5;
				final double viaZ = nz + 0.5;
				final double toGoal = distance(viaX, viaY, viaZ, lx, ly, lz);
				if (distance(sx, sy, sz, viaX, viaY, viaZ) + toGoal > limit) {
					continue;
				}
				if (next != goal && !passable(query, cx, cy, cz, nx, ny, nz)) {
					continue;
				}
				parents.put(next, current);
				costs.put(next, nextCost);
				if (next == goal) {
					found = true;
					break;
				}
				open.push(next, nextCost + HEURISTIC_WEIGHT * toGoal);
			}
		}
		if (!found) {
			return Path.NONE;
		}

		// 从听者回溯到声源，得到路径点（首尾使用精确坐标）。
		final List<double[]> points = new ArrayList<>();
		points.add(new double[] {lx, ly, lz});
		double transmissionLoss = 0.0;
		long cursor = parents.get(goal);
		while (cursor != start) {
			final int x = unpackX(cursor);
			final int y = unpackY(cursor);
			final int z = unpackZ(cursor);
			points.add(new double[] {x + 0.5, y + 0.5, z + 0.5});
			transmissionLoss += query.material(x, y, z).occlusion();
			cursor = parents.get(cursor);
		}
		points.add(new double[] {sx, sy, sz});

		return pull(query, points, Math.exp(-transmissionLoss * 0.5));
	}

	/**
	 * 拉绳平滑：保留必要的拐点并计算路程与第一拐点方向。
	 *
	 * @param query 世界查询
	 * @param points 路径点（听者 → 声源）
	 * @param transmission 透射系数
	 * @return 搜索结果
	 */
	private static Path pull(final BlockQuery query, final List<double[]> points, final double transmission) {
		final List<double[]> anchors = new ArrayList<>();
		double[] anchor = points.getFirst();
		anchors.add(anchor);
		final int last = points.size() - 1;
		for (int i = 2; i <= last; i++) {
			final double[] candidate = points.get(i);
			if (!visible(query, anchor, candidate, i == last)) {
				anchor = points.get(i - 1);
				anchors.add(anchor);
			}
		}
		anchors.add(points.get(last));

		double length = 0.0;
		for (int i = 1; i < anchors.size(); i++) {
			final double[] a = anchors.get(i - 1);
			final double[] b = anchors.get(i);
			length += distance(a[0], a[1], a[2], b[0], b[1], b[2]);
		}
		final double[] origin = anchors.getFirst();
		final double[] first = anchors.get(1);
		double dx = first[0] - origin[0];
		double dy = first[1] - origin[1];
		double dz = first[2] - origin[2];
		final double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (norm > 1.0e-9) {
			dx /= norm;
			dy /= norm;
			dz /= norm;
		}
		// 合并相距很近的拐点：穿过一个门洞往往在门洞两侧各产生一个格心拐点，应只算一次绕射。
		int corners = 0;
		double[] previous = null;
		for (int i = 1; i < anchors.size() - 1; i++) {
			final double[] corner = anchors.get(i);
			if (previous == null || distance(previous[0], previous[1], previous[2], corner[0], corner[1], corner[2]) > CORNER_MERGE_DISTANCE) {
				corners++;
			}
			previous = corner;
		}
		return new Path(true, length, corners, dx, dy, dz, transmission);
	}

	/**
	 * 判断两点之间是否互相可见。
	 *
	 * @param query 世界查询
	 * @param a 起点
	 * @param b 终点
	 * @param endIsSource 终点是否为声源（声源可能嵌在方块中，需要忽略其所在格）
	 * @return 可见时返回 true
	 */
	private static boolean visible(final BlockQuery query, final double[] a, final double[] b, final boolean endIsSource) {
		return OcclusionSolver.occlusionBetween(query, a[0], a[1], a[2], b[0], b[1], b[2],
			LINE_OF_SIGHT_TOLERANCE + 0.01, true, endIsSource) <= LINE_OF_SIGHT_TOLERANCE;
	}

	/**
	 * 判断能否从当前格移动到相邻格。
	 *
	 * @param query 世界查询
	 * @param fx 当前格 x
	 * @param fy 当前格 y
	 * @param fz 当前格 z
	 * @param tx 目标格 x
	 * @param ty 目标格 y
	 * @param tz 目标格 z
	 * @return 可通行时返回 true
	 */
	private static boolean passable(final BlockQuery query, final int fx, final int fy, final int fz,
			final int tx, final int ty, final int tz) {
		final Material material = query.material(tx, ty, tz);
		if (material.occlusion() < PASSABLE_OCCLUSION) {
			return true;
		}
		if (material.partial()) {
			// 非完整方块（栅栏、打开的门、台阶等）：沿移动方向的中心线未碰到碰撞箱即可通过。
			return !query.segmentHits(tx, ty, tz, fx + 0.5, fy + 0.5, fz + 0.5, tx + 0.5, ty + 0.5, tz + 0.5);
		}
		return false;
	}

	/**
	 * A* 开放列表使用的最小堆（按估价排序的方块格），避免装箱开销。
	 */
	private static final class NodeHeap {
		/** 方块格。 */
		private long[] cells = new long[256];

		/** 估价。 */
		private double[] keys = new double[256];

		/** 元素数量。 */
		private int size;

		/**
		 * 是否为空。
		 *
		 * @return 为空时返回 true
		 */
		boolean isEmpty() {
			return this.size == 0;
		}

		/**
		 * 插入元素。
		 *
		 * @param cell 方块格
		 * @param key 估价
		 */
		void push(final long cell, final double key) {
			if (this.size == this.cells.length) {
				this.cells = Arrays.copyOf(this.cells, this.size * 2);
				this.keys = Arrays.copyOf(this.keys, this.size * 2);
			}
			int i = this.size++;
			while (i > 0) {
				final int parent = (i - 1) >>> 1;
				if (this.keys[parent] <= key) {
					break;
				}
				this.cells[i] = this.cells[parent];
				this.keys[i] = this.keys[parent];
				i = parent;
			}
			this.cells[i] = cell;
			this.keys[i] = key;
		}

		/**
		 * 取出估价最小的元素。
		 *
		 * @return 方块格
		 */
		long pop() {
			final long result = this.cells[0];
			final long lastCell = this.cells[--this.size];
			final double lastKey = this.keys[this.size];
			int i = 0;
			while (true) {
				int child = 2 * i + 1;
				if (child >= this.size) {
					break;
				}
				if (child + 1 < this.size && this.keys[child + 1] < this.keys[child]) {
					child++;
				}
				if (this.keys[child] >= lastKey) {
					break;
				}
				this.cells[i] = this.cells[child];
				this.keys[i] = this.keys[child];
				i = child;
			}
			this.cells[i] = lastCell;
			this.keys[i] = lastKey;
			return result;
		}
	}

	/**
	 * 两点距离。
	 *
	 * @param ax 点一 x
	 * @param ay 点一 y
	 * @param az 点一 z
	 * @param bx 点二 x
	 * @param by 点二 y
	 * @param bz 点二 z
	 * @return 距离
	 */
	private static double distance(final double ax, final double ay, final double az, final double bx, final double by, final double bz) {
		final double dx = bx - ax;
		final double dy = by - ay;
		final double dz = bz - az;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * 把方块坐标打包为长整数（与 Minecraft BlockPos 的打包方式无关，仅内部使用）。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @return 打包值
	 */
	private static long pack(final int x, final int y, final int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
	}

	/**
	 * 解包 x。
	 *
	 * @param packed 打包值
	 * @return x
	 */
	private static int unpackX(final long packed) {
		return (int) (packed >> 38);
	}

	/**
	 * 解包 y。
	 *
	 * @param packed 打包值
	 * @return y
	 */
	private static int unpackY(final long packed) {
		return (int) (packed << 52 >> 52);
	}

	/**
	 * 解包 z。
	 *
	 * @param packed 打包值
	 * @return z
	 */
	private static int unpackZ(final long packed) {
		return (int) (packed << 26 >> 38);
	}
}
