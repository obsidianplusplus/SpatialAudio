package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 在单位球面上生成近似均匀分布的射线方向（斐波那契螺旋点集）。
 *
 * <p>与随机采样相比，斐波那契点集在少量射线下就能覆盖整个球面，且结果可复现，避免声学参数闪烁。
 */
public final class SphereSampler {
	/** 黄金角（弧度）：π × (3 − √5)。 */
	public static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

	private SphereSampler() {
	}

	/**
	 * 计算第 index 个方向。
	 *
	 * @param index 序号（0 ~ count-1）
	 * @param count 方向总数
	 * @param rotation 绕竖直轴的整体旋转（弧度），用于在多轮探测间错开采样方向
	 * @return 单位向量 {x, y, z}，y 轴朝上
	 */
	public static double[] direction(final int index, final int count, final double rotation) {
		final int n = Math.max(1, count);
		final double y = 1.0 - (2.0 * index + 1.0) / n;
		final double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
		final double phi = index * GOLDEN_ANGLE + rotation;
		return new double[] {Math.cos(phi) * radius, y, Math.sin(phi) * radius};
	}
}
