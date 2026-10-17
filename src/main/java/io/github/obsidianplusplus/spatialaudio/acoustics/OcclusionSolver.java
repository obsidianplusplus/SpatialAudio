package io.github.obsidianplusplus.spatialaudio.acoustics;

import io.github.obsidianplusplus.spatialaudio.audio.AcousticTarget;

/**
 * 声源遮挡与绕射求解器（纯算法，不依赖 Minecraft）。
 *
 * <p>步骤：
 * <ol>
 *   <li><b>直达路径遮挡</b>：沿听者到声源的线段做体素遍历，按“材质遮挡系数 × 穿过厚度”累加遮挡量 O。
 *       直达声高频增益取 {@code exp(-O·K_HF)}，整体增益取 {@code exp(-O·K_LF)}，
 *       即墙越厚越闷、越小声，但低频比高频更容易穿透（与真实墙体的透射损失特性一致）。</li>
 *   <li><b>绕射路径</b>：若直达路径被遮挡，用 {@link AirPathFinder} 在空气格中搜索绕过障碍物的最短通路
 *       （门洞、窗户、走廊、拐角），其增益随额外路程与拐弯次数衰减。</li>
 *   <li><b>重定向</b>：听者沿通路看到的第一个拐点方向即“声音听起来来自的方向”，与真实方向按高频能量比混合。</li>
 * </ol>
 */
public final class OcclusionSolver {
	/** 遮挡对高频的衰减系数。 */
	public static final double K_HF = 1.0;

	/** 遮挡对整体音量的衰减系数。 */
	public static final double K_LF = 0.1;

	/** 直达路径遮挡量上限（再厚的墙也不会让计算无限进行）。 */
	private static final double MAX_OCCLUSION = 10.0;

	/** 绕射路径额外路程的衰减长度（格）。 */
	private static final double EXTRA_PATH_FALLOFF = 24.0;

	/** 每个拐点（绕射一次）的整体增益损失。 */
	private static final double CORNER_GAIN = 0.92;

	/** 每个拐点额外的高频损失。 */
	private static final double CORNER_HF = 0.85;

	/** 部分方块（门、栅栏等）命中时计入的最大厚度（格）。 */
	private static final double PARTIAL_THICKNESS = 0.3;

	/** 低于该遮挡量视为通畅，不再搜索间接路径。 */
	private static final double CLEAR_THRESHOLD = 0.05;

	/** 重定向权重上限，保留一部分真实方向感。 */
	private static final float MAX_REDIRECT_BLEND = 0.85f;

	private OcclusionSolver() {
	}

	/**
	 * 计算两点之间的遮挡量。
	 *
	 * @param query 世界查询
	 * @param ax 起点 x
	 * @param ay 起点 y
	 * @param az 起点 z
	 * @param bx 终点 x
	 * @param by 终点 y
	 * @param bz 终点 z
	 * @param cap 达到该值即提前结束
	 * @param skipStart 是否忽略起点所在方块（例如声源嵌在方块里）
	 * @param skipEnd 是否忽略终点所在方块
	 * @return 遮挡量（材质系数 × 厚度之和）
	 */
	public static double occlusionBetween(final BlockQuery query, final double ax, final double ay, final double az,
			final double bx, final double by, final double bz, final double cap, final boolean skipStart, final boolean skipEnd) {
		final int sx = (int) Math.floor(ax);
		final int sy = (int) Math.floor(ay);
		final int sz = (int) Math.floor(az);
		final int ex = (int) Math.floor(bx);
		final int ey = (int) Math.floor(by);
		final int ez = (int) Math.floor(bz);
		final double[] total = {0.0};
		VoxelRaycaster.traverseSegment(ax, ay, az, bx, by, bz, (x, y, z, enter, exit) -> {
			if (skipStart && x == sx && y == sy && z == sz) {
				return true;
			}
			if (skipEnd && x == ex && y == ey && z == ez) {
				return true;
			}
			final Material material = query.material(x, y, z);
			if (material.occlusion() <= 0.0f) {
				return true;
			}
			double thickness = exit - enter;
			if (material.partial()) {
				if (!query.segmentHits(x, y, z, ax, ay, az, bx, by, bz)) {
					return true;
				}
				thickness = Math.min(thickness, PARTIAL_THICKNESS);
			}
			total[0] += material.occlusion() * thickness;
			return total[0] < cap;
		});
		return Math.min(total[0], cap);
	}

	/**
	 * 求解单个声源的声学目标。
	 *
	 * @param query 世界查询
	 * @param lx 听者 x
	 * @param ly 听者 y
	 * @param lz 听者 z
	 * @param sx 声源 x
	 * @param sy 声源 y
	 * @param sz 声源 z
	 * @param strength 遮挡强度倍率
	 * @param pathCells 绕射路径搜索最多访问的格子数（0 表示只算直达路径）
	 * @param redirection 是否计算重定向方向
	 * @return 声学目标
	 */
	public static AcousticTarget solve(final BlockQuery query, final double lx, final double ly, final double lz,
			final double sx, final double sy, final double sz, final double strength, final int pathCells, final boolean redirection) {
		final double occlusion = occlusionBetween(query, lx, ly, lz, sx, sy, sz, MAX_OCCLUSION, true, true) * strength;
		final float directGain = (float) Math.exp(-occlusion * K_LF);
		final float directHF = (float) Math.exp(-occlusion * K_HF);
		if (occlusion < CLEAR_THRESHOLD || pathCells <= 0) {
			return new AcousticTarget(directGain, directHF, 0.0f, 0.0, 0.0, 0.0);
		}

		final AirPathFinder.Path path = AirPathFinder.find(query, lx, ly, lz, sx, sy, sz, pathCells);
		if (!path.found()) {
			return new AcousticTarget(directGain, directHF, 0.0f, 0.0, 0.0, 0.0);
		}
		// 绕射路径：额外路程越长、拐弯越多，声音越小、越闷（低频比高频更容易绕过障碍物）。
		final double directDistance = distance(lx, ly, lz, sx, sy, sz);
		final double extra = Math.max(0.0, path.length() - directDistance);
		final double pathGain = Math.exp(-extra / EXTRA_PATH_FALLOFF) * Math.pow(CORNER_GAIN, path.corners()) * path.transmission();
		final double pathHF = pathGain * Math.pow(CORNER_HF, path.corners());
		final float gain = (float) Math.max(directGain, pathGain);
		final float gainHF = (float) Math.max(directHF, pathHF);
		if (!redirection || path.corners() == 0) {
			return new AcousticTarget(gain, gainHF, 0.0f, 0.0, 0.0, 0.0);
		}
		final float blend = (float) Math.min(MAX_REDIRECT_BLEND, pathHF / (pathHF + directHF + 1.0e-3));
		return new AcousticTarget(gain, gainHF, blend, path.directionX(), path.directionY(), path.directionZ());
	}

	/**
	 * 射线命中结果。
	 *
	 * @param hit 是否命中反射面
	 * @param distance 命中距离
	 * @param absorption 命中表面的吸声系数
	 */
	public record RayHit(boolean hit, double distance, float absorption) {
		/** 未命中。 */
		public static final RayHit MISS = new RayHit(false, Double.POSITIVE_INFINITY, 0.0f);
	}

	/**
	 * 沿方向投射射线，返回第一个反射面（忽略起点所在方块）。
	 *
	 * @param query 世界查询
	 * @param ox 起点 x
	 * @param oy 起点 y
	 * @param oz 起点 z
	 * @param dx 方向 x
	 * @param dy 方向 y
	 * @param dz 方向 z
	 * @param range 射程
	 * @return 命中结果
	 */
	public static RayHit castToSurface(final BlockQuery query, final double ox, final double oy, final double oz,
			final double dx, final double dy, final double dz, final double range) {
		final int sx = (int) Math.floor(ox);
		final int sy = (int) Math.floor(oy);
		final int sz = (int) Math.floor(oz);
		final RayHit[] result = {RayHit.MISS};
		VoxelRaycaster.traverse(ox, oy, oz, dx, dy, dz, range, (x, y, z, enter, exit) -> {
			if (x == sx && y == sy && z == sz) {
				return true;
			}
			final Material material = query.material(x, y, z);
			if (!material.reflective()) {
				return true;
			}
			if (material.partial() && !query.segmentHits(x, y, z,
				ox + dx * enter, oy + dy * enter, oz + dz * enter, ox + dx * exit, oy + dy * exit, oz + dz * exit)) {
				return true;
			}
			result[0] = new RayHit(true, enter, material.absorption());
			return false;
		});
		return result[0];
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
}
