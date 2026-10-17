package io.github.obsidianplusplus.spatialaudio.acoustics;

/**
 * 方块的声学材质属性。
 *
 * @param occlusion 每格（米）厚度造成的遮挡量，石头约为 1，玻璃约 0.5，羊毛约 1.5
 * @param absorption 表面吸声系数（0 完全反射 ~ 1 完全吸收），决定混响长短与音色
 * @param reflective 是否作为声音的反射面（参与混响探测与绕射计算）
 * @param partial 碰撞箱是否不是完整方块（门、栅栏、台阶等），需要精确的射线检测
 */
public record Material(float occlusion, float absorption, boolean reflective, boolean partial) {
	/** 空气：不遮挡、不反射。 */
	public static final Material AIR = new Material(0.0f, 0.0f, false, false);

	/** 默认固体（未知方块的回退值）。 */
	public static final Material DEFAULT_SOLID = new Material(1.0f, 0.1f, true, false);

	/** 水：声音穿过水体会明显变闷，水面是强反射面。 */
	public static final Material WATER = new Material(0.35f, 0.03f, true, false);

	/** 岩浆。 */
	public static final Material LAVA = new Material(0.8f, 0.1f, true, false);

	/**
	 * 是否会对声音产生任何影响。
	 *
	 * @return 遮挡或反射时返回 true
	 */
	public boolean isAcousticallyActive() {
		return this.occlusion > 0.0f || this.reflective;
	}

	/**
	 * 生成一个修改了遮挡与吸声系数的副本。
	 *
	 * @param newOcclusion 新遮挡系数，小于 0 表示保持不变
	 * @param newAbsorption 新吸声系数，小于 0 表示保持不变
	 * @return 新材质
	 */
	public Material withOverrides(final float newOcclusion, final float newAbsorption) {
		return new Material(
			newOcclusion >= 0.0f ? newOcclusion : this.occlusion,
			newAbsorption >= 0.0f ? newAbsorption : this.absorption,
			this.reflective,
			this.partial
		);
	}
}
