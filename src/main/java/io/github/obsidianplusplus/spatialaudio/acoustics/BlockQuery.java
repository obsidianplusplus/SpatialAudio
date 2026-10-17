package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 声学计算所需的世界查询接口。
 *
 * <p>把对 Minecraft 世界的访问抽象出来，使遮挡、绕射、混响探测等算法可以在单元测试中用合成世界验证。
 */
public interface BlockQuery {
	/**
	 * 查询指定方块格的声学材质。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @return 材质，空气返回 {@link Material#AIR}
	 */
	Material material(int x, int y, int z);

	/**
	 * 对非完整方块做精确的线段与碰撞箱相交测试。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @param ax 线段起点 x
	 * @param ay 线段起点 y
	 * @param az 线段起点 z
	 * @param bx 线段终点 x
	 * @param by 线段终点 y
	 * @param bz 线段终点 z
	 * @return 相交时返回 true
	 */
	boolean segmentHits(int x, int y, int z, double ax, double ay, double az, double bx, double by, double bz);
}
