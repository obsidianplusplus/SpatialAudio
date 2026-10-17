package io.github.obsidianplusplus.spatialaudio.acoustics;

import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 方块到声学材质的映射表。
 *
 * <p>Minecraft 26.x 已经没有“方块材质”概念，但每个方块都有用于脚步声的 {@link SoundType}，
 * 它与真实材料高度相关（石头、木头、羊毛、玻璃、金属……），因此以它为主要依据，
 * 再结合碰撞箱形状（完整方块 / 门、栅栏等非完整方块）与玩家在配置中填写的按方块覆盖值。
 *
 * <p>吸声系数参考建筑声学常用数据（500 Hz~1 kHz 附近）：清水混凝土与石材约 0.02~0.05，
 * 木板约 0.1~0.15，玻璃约 0.04，厚地毯与织物约 0.5~0.8，新雪约 0.6 以上。
 * 本类只在主线程使用，缓存不需要同步。
 */
public final class BlockAcoustics {
	/** 坚硬石质：强反射、强遮挡。 */
	private static final Material STONE = new Material(1.0f, 0.03f, true, false);

	/** 金属：几乎全反射。 */
	private static final Material METAL = new Material(1.1f, 0.02f, true, false);

	/** 木质：中等反射。 */
	private static final Material WOOD = new Material(0.8f, 0.12f, true, false);

	/** 玻璃：遮挡较弱，但反射很强。 */
	private static final Material GLASS = new Material(0.5f, 0.04f, true, false);

	/** 羊毛、苔藓等软质材料：强吸声、强遮挡（相当于隔音棉）。 */
	private static final Material SOFT = new Material(1.5f, 0.75f, true, false);

	/** 海绵：极强吸声。 */
	private static final Material SPONGE = new Material(1.4f, 0.9f, true, false);

	/** 沙、灵魂沙等颗粒材料。 */
	private static final Material SAND = new Material(1.0f, 0.25f, true, false);

	/** 沙砾、泥土类。 */
	private static final Material GRAVEL = new Material(1.0f, 0.2f, true, false);

	/** 雪。 */
	private static final Material SNOW = new Material(0.9f, 0.6f, true, false);

	/** 树叶与植物：稀疏、透声。 */
	private static final Material FOLIAGE = new Material(0.25f, 0.45f, true, false);

	/** 紫水晶：坚硬且共鸣明显。 */
	private static final Material CRYSTAL = new Material(0.9f, 0.02f, true, false);

	/** 幽匿块：吸收振动。 */
	private static final Material SCULK = new Material(1.0f, 0.55f, true, false);

	/** SoundType 到材质的映射。 */
	private static final Map<SoundType, Material> BY_SOUND_TYPE = buildSoundTypeTable();

	/** 方块状态到材质的缓存。 */
	private final Map<BlockState, Material> cache = new IdentityHashMap<>();

	/** 生成缓存时使用的配置对象（配置被替换后自动清空缓存）。 */
	private SpatialAudioConfig cachedFor;

	/**
	 * 查询方块状态的材质。
	 *
	 * @param state 方块状态
	 * @param level 世界
	 * @param pos 方块位置（用于计算碰撞箱）
	 * @return 材质
	 */
	public Material materialOf(final BlockState state, final BlockGetter level, final BlockPos pos) {
		final SpatialAudioConfig config = ConfigManager.get();
		if (this.cachedFor != config) {
			this.cache.clear();
			this.cachedFor = config;
		}
		if (state.isAir()) {
			return Material.AIR;
		}
		final Material cached = this.cache.get(state);
		if (cached != null) {
			return cached;
		}
		final Material computed = this.compute(state, level, pos, config);
		this.cache.put(state, computed);
		return computed;
	}

	/**
	 * 清空缓存。
	 */
	public void clear() {
		this.cache.clear();
	}

	/**
	 * 计算材质。
	 *
	 * @param state 方块状态
	 * @param level 世界
	 * @param pos 方块位置
	 * @param config 配置
	 * @return 材质
	 */
	private Material compute(final BlockState state, final BlockGetter level, final BlockPos pos, final SpatialAudioConfig config) {
		final boolean hasCollision = !state.getCollisionShape(level, pos).isEmpty();
		Material material;
		if (!hasCollision) {
			final FluidState fluid = state.getFluidState();
			if (fluid.isEmpty()) {
				// 花草、火把、蜘蛛网等无碰撞方块对声音几乎没有影响。
				material = Material.AIR;
			} else if (fluid.is(FluidTags.LAVA)) {
				material = Material.LAVA;
			} else {
				material = Material.WATER;
			}
		} else {
			final Material base = BY_SOUND_TYPE.getOrDefault(state.getSoundType(), Material.DEFAULT_SOLID);
			final boolean full = state.isCollisionShapeFullBlock(level, pos);
			material = new Material(base.occlusion(), base.absorption(), base.reflective(), !full);
		}

		final String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		final Float occlusion = config.blockOcclusionOverrides.get(id);
		final Float absorption = config.blockAbsorptionOverrides.get(id);
		if (occlusion != null || absorption != null) {
			// 覆盖值只修改遮挡与吸声系数；无碰撞方块即使设置了遮挡也不会成为反射面。
			material = material.withOverrides(occlusion == null ? -1.0f : occlusion, absorption == null ? -1.0f : absorption);
		}
		return material;
	}

	/**
	 * 构建 SoundType 映射表。
	 *
	 * @return 映射表
	 */
	private static Map<SoundType, Material> buildSoundTypeTable() {
		final Map<SoundType, Material> map = new IdentityHashMap<>();
		register(map, STONE, SoundType.STONE, SoundType.DEEPSLATE, SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES,
			SoundType.POLISHED_DEEPSLATE, SoundType.TUFF, SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.CALCITE,
			SoundType.BASALT, SoundType.NETHER_BRICKS, SoundType.NETHERRACK, SoundType.NETHER_ORE, SoundType.NETHER_GOLD_ORE,
			SoundType.GILDED_BLACKSTONE, SoundType.BONE_BLOCK, SoundType.ANCIENT_DEBRIS, SoundType.LODESTONE, SoundType.DRIPSTONE_BLOCK,
			SoundType.POINTED_DRIPSTONE, SoundType.MUD_BRICKS, SoundType.PACKED_MUD, SoundType.RESIN_BRICKS, SoundType.CORAL_BLOCK,
			SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED, SoundType.FROGLIGHT, SoundType.SHROOMLIGHT);
		register(map, METAL, SoundType.METAL, SoundType.ANVIL, SoundType.NETHERITE_BLOCK, SoundType.COPPER, SoundType.COPPER_BULB,
			SoundType.COPPER_GRATE, SoundType.COPPER_GOLEM_STATUE, SoundType.IRON, SoundType.CHAIN, SoundType.LANTERN,
			SoundType.HEAVY_CORE, SoundType.VAULT, SoundType.TRIAL_SPAWNER, SoundType.SPAWNER);
		register(map, WOOD, SoundType.WOOD, SoundType.BAMBOO_WOOD, SoundType.NETHER_WOOD, SoundType.CHERRY_WOOD, SoundType.LADDER,
			SoundType.SCAFFOLDING, SoundType.CHISELED_BOOKSHELF, SoundType.SHELF, SoundType.HANGING_SIGN,
			SoundType.NETHER_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.CHERRY_WOOD_HANGING_SIGN,
			SoundType.BAMBOO, SoundType.STEM, SoundType.CREAKING_HEART);
		register(map, GLASS, SoundType.GLASS);
		register(map, SOFT, SoundType.WOOL, SoundType.MOSS, SoundType.MOSS_CARPET, SoundType.HONEY_BLOCK, SoundType.SLIME_BLOCK,
			SoundType.STRAW_BED, SoundType.WART_BLOCK, SoundType.NYLIUM, SoundType.RESIN);
		register(map, SPONGE, SoundType.SPONGE, SoundType.WET_SPONGE);
		register(map, SAND, SoundType.SAND, SoundType.SUSPICIOUS_SAND, SoundType.SOUL_SAND, SoundType.SOUL_SOIL);
		register(map, GRAVEL, SoundType.GRAVEL, SoundType.SUSPICIOUS_GRAVEL, SoundType.ROOTED_DIRT, SoundType.MUD,
			SoundType.MUDDY_MANGROVE_ROOTS, SoundType.MANGROVE_ROOTS, SoundType.GRASS);
		register(map, SNOW, SoundType.SNOW, SoundType.POWDER_SNOW);
		register(map, FOLIAGE, SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES, SoundType.POPLAR_LEAVES, SoundType.AZALEA,
			SoundType.FLOWERING_AZALEA, SoundType.CACTUS_FLOWER, SoundType.SWEET_BERRY_BUSH, SoundType.VINE, SoundType.CAVE_VINES,
			SoundType.WEEPING_VINES, SoundType.TWISTING_VINES, SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF,
			SoundType.HANGING_ROOTS, SoundType.LEAF_LITTER, SoundType.PINK_PETALS, SoundType.SPORE_BLOSSOM, SoundType.CROP,
			SoundType.HARD_CROP, SoundType.WET_GRASS, SoundType.LILY_PAD, SoundType.BAMBOO_SAPLING, SoundType.CHERRY_SAPLING,
			SoundType.ROOTS, SoundType.FUNGUS, SoundType.NETHER_SPROUTS, SoundType.NETHER_WART, SoundType.GLOW_LICHEN,
			SoundType.COBWEB, SoundType.RED_SHRUB);
		register(map, CRYSTAL, SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER, SoundType.SMALL_AMETHYST_BUD,
			SoundType.MEDIUM_AMETHYST_BUD, SoundType.LARGE_AMETHYST_BUD);
		register(map, SCULK, SoundType.SCULK, SoundType.SCULK_SENSOR, SoundType.SCULK_CATALYST, SoundType.SCULK_SHRIEKER,
			SoundType.SCULK_VEIN);
		return map;
	}

	/**
	 * 批量登记映射。
	 *
	 * @param map 映射表
	 * @param material 材质
	 * @param types SoundType 列表
	 */
	private static void register(final Map<SoundType, Material> map, final Material material, final SoundType... types) {
		for (final SoundType type : types) {
			map.put(type, material);
		}
	}
}
