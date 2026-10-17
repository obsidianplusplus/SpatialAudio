package io.github.obsidianplusplus.spatialaudio.acoustics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 基于客户端世界的 {@link BlockQuery} 实现，带有单刻方块缓存。
 *
 * <p>同一游戏刻内的多条射线经常穿过相同方块（例如同一面墙），缓存可显著减少区块查找次数。
 * 未加载的区块视为空气，避免在世界边缘产生虚假遮挡。只能在主线程使用。
 */
public final class LevelBlockQuery implements BlockQuery {
	/** 方块材质映射。 */
	private final BlockAcoustics acoustics = new BlockAcoustics();

	/** 本刻方块状态缓存。 */
	private final Long2ObjectOpenHashMap<BlockState> stateCache = new Long2ObjectOpenHashMap<>();

	/** 可复用的可变坐标。 */
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	/** 当前世界。 */
	private ClientLevel level;

	/**
	 * 开始新一刻的查询。
	 *
	 * @param newLevel 当前世界
	 */
	public void begin(final ClientLevel newLevel) {
		if (this.level != newLevel) {
			this.acoustics.clear();
		}
		this.level = newLevel;
		this.stateCache.clear();
	}

	/**
	 * 结束本刻查询，释放对世界的引用。
	 */
	public void end() {
		this.stateCache.clear();
	}

	/**
	 * 是否已绑定世界。
	 *
	 * @return 已绑定时返回 true
	 */
	public boolean hasLevel() {
		return this.level != null;
	}

	/**
	 * 清空材质缓存（配置变化时调用）。
	 */
	public void invalidateMaterials() {
		this.acoustics.clear();
	}

	@Override
	public Material material(final int x, final int y, final int z) {
		final BlockState state = this.state(x, y, z);
		if (state.isAir()) {
			return Material.AIR;
		}
		return this.acoustics.materialOf(state, this.level, this.cursor.set(x, y, z));
	}

	@Override
	public boolean segmentHits(final int x, final int y, final int z, final double ax, final double ay, final double az,
			final double bx, final double by, final double bz) {
		final BlockState state = this.state(x, y, z);
		final BlockPos pos = this.cursor.set(x, y, z);
		final VoxelShape shape = state.getCollisionShape(this.level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		final BlockHitResult hit = shape.clip(new Vec3(ax, ay, az), new Vec3(bx, by, bz), pos.immutable());
		return hit != null;
	}

	/**
	 * 读取方块状态（带缓存，未加载区块视为空气）。
	 *
	 * @param x 方块 x
	 * @param y 方块 y
	 * @param z 方块 z
	 * @return 方块状态
	 */
	private BlockState state(final int x, final int y, final int z) {
		if (this.level == null || this.level.isOutsideBuildHeight(y)) {
			return Blocks.AIR.defaultBlockState();
		}
		final long key = BlockPos.asLong(x, y, z);
		BlockState state = this.stateCache.get(key);
		if (state == null) {
			state = this.level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
				? this.level.getBlockState(this.cursor.set(x, y, z))
				: Blocks.AIR.defaultBlockState();
			this.stateCache.put(key, state);
		}
		return state;
	}
}
