package io.github.obsidianplusplus.spatialaudio.acoustics;

import io.github.obsidianplusplus.spatialaudio.audio.AcousticTarget;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.GlobalAcoustics;
import io.github.obsidianplusplus.spatialaudio.audio.SourceBinding;
import io.github.obsidianplusplus.spatialaudio.audio.SpatialSource;
import io.github.obsidianplusplus.spatialaudio.config.CategorySettings;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * 声学模拟的主线程调度器。
 *
 * <p>所有涉及读取世界方块的计算都在客户端主线程完成（这是唯一能安全访问世界数据的线程），
 * 结果以不可变对象写入 volatile 字段，再由声音线程平滑地应用到 OpenAL。每刻工作量受时间预算限制：
 * <ul>
 *   <li>环境探测：每刻发射约 1/8 的射线，约 0.4 秒完成一轮；</li>
 *   <li>声源遮挡：按距离分级轮询，近处声源每 3 刻、远处每 6 刻刷新一次；</li>
 *   <li>新播放的声音：在播放瞬间同步计算，保证一开始就带有正确的遮挡。</li>
 * </ul>
 */
public final class AcousticsManager {
	/** 全局唯一实例。 */
	private static final AcousticsManager INSTANCE = new AcousticsManager();

	/** 环境探测分摊到的刻数。 */
	private static final int PROBE_SPREAD_TICKS = 8;

	/** 每刻允许在播放瞬间做完整绕射求解的声音数量。 */
	private static final int MAX_FULL_SOLVES_PER_TICK = 24;

	/** 每刻允许在播放瞬间做直达遮挡求解的声音数量（超过后推迟到下一刻）。 */
	private static final int MAX_DIRECT_SOLVES_PER_TICK = 96;

	/** 近处声源的距离阈值（格）。 */
	private static final double NEAR_DISTANCE = 8.0;

	/** 低于该距离的声源不做遮挡（自己的脚步声、手中物品声等）。 */
	private static final double MIN_OCCLUSION_DISTANCE = 1.0;

	/** 世界查询。 */
	private final LevelBlockQuery query = new LevelBlockQuery();

	/** 环境探测。 */
	private final EnvironmentProbe probe = new EnvironmentProbe();

	/** 当前游戏刻计数。 */
	private long tick;

	/** 本刻已在播放瞬间完成的求解数量。 */
	private int solvesThisTick;

	/** 上一刻所在的世界（用于检测切换世界）。 */
	private ClientLevel lastLevel;

	private AcousticsManager() {
	}

	/**
	 * 获取全局实例。
	 *
	 * @return 实例
	 */
	public static AcousticsManager get() {
		return INSTANCE;
	}

	/**
	 * 每个客户端游戏刻结束时调用（主线程）。
	 *
	 * @param minecraft 客户端实例
	 */
	public void tick(final Minecraft minecraft) {
		this.tick++;
		this.solvesThisTick = 0;
		final AudioSystem audio = AudioSystem.get();
		final SpatialAudioConfig config = ConfigManager.get();
		final ClientLevel level = minecraft.level;
		if (level != this.lastLevel) {
			this.probe.reset();
			this.query.invalidateMaterials();
			this.lastLevel = level;
		}
		if (!audio.isAlive() || level == null || !audio.acousticsActive(config)) {
			audio.setGlobal(GlobalAcoustics.NEUTRAL);
			return;
		}
		final Camera camera = minecraft.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		final long deadline = System.nanoTime() + (long) (config.acousticsBudgetMs * 1_000_000.0);
		final Vec3 eye = camera.position();
		this.query.begin(level);
		try {
			// 1. 环境探测（混响与回声）。
			final int batch = Math.max(1, (config.probeRays + PROBE_SPREAD_TICKS - 1) / PROBE_SPREAD_TICKS);
			this.probe.step(this.query, eye.x, eye.y, eye.z, batch, config.probeRays, config.probeDistance, config.echoDistance);
			audio.setGlobal(this.buildGlobal(level, camera, eye));

			// 2. 声源遮挡轮询。
			for (final SpatialSource source : audio.sources().all()) {
				if (System.nanoTime() > deadline) {
					break;
				}
				if (source.relative || this.tick < source.nextAcousticsTick) {
					continue;
				}
				final CategorySettings category = config.category(source.category);
				final Vec3 position = source.truePosition;
				final double distance = position.distanceTo(eye);
				if (!category.occlusion || !config.occlusionEnabled || distance < MIN_OCCLUSION_DISTANCE
					|| (source.attenuated && distance > source.maxDistance + 2.0)) {
					source.target = AcousticTarget.CLEAR;
				} else {
					source.target = OcclusionSolver.solve(this.query, eye.x, eye.y, eye.z, position.x, position.y, position.z,
						config.occlusionStrength, config.diffractionCells, config.soundRedirection);
				}
				source.nextAcousticsTick = this.tick + (distance < NEAR_DISTANCE ? 3 : 6);
			}
		} finally {
			this.query.end();
		}
	}

	/**
	 * 声音开始播放时（主线程，原版 SoundEngine#play 内）生成绑定信息，并立即计算初始遮挡。
	 *
	 * @param instance 声音实例
	 * @return 绑定信息
	 */
	public SourceBinding createBinding(final SoundInstance instance) {
		final boolean relative = instance.isRelative();
		final boolean attenuated = instance.getAttenuation() == SoundInstance.Attenuation.LINEAR;
		AcousticTarget target;
		try {
			target = this.initialTarget(instance, relative);
		} catch (RuntimeException e) {
			// 计算失败不影响播放，后续轮询会重新计算。
			target = AcousticTarget.CLEAR;
		}
		return new SourceBinding(instance.getSource(), instance.getIdentifier(), relative, attenuated, target);
	}

	/**
	 * 计算播放瞬间的遮挡目标。
	 *
	 * @param instance 声音实例
	 * @param relative 是否相对听者
	 * @return 声学目标
	 */
	private AcousticTarget initialTarget(final SoundInstance instance, final boolean relative) {
		final Minecraft minecraft = Minecraft.getInstance();
		final SpatialAudioConfig config = ConfigManager.get();
		if (relative || minecraft.level == null || !minecraft.isSameThread() || !AudioSystem.get().acousticsActive(config)
			|| !config.occlusionEnabled || !config.category(instance.getSource()).occlusion) {
			return AcousticTarget.CLEAR;
		}
		final Camera camera = minecraft.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return AcousticTarget.CLEAR;
		}
		final Vec3 eye = camera.position();
		final double x = instance.getX();
		final double y = instance.getY();
		final double z = instance.getZ();
		final double dx = x - eye.x;
		final double dy = y - eye.y;
		final double dz = z - eye.z;
		if (dx * dx + dy * dy + dz * dz < MIN_OCCLUSION_DISTANCE * MIN_OCCLUSION_DISTANCE) {
			return AcousticTarget.CLEAR;
		}
		if (this.solvesThisTick >= MAX_DIRECT_SOLVES_PER_TICK) {
			return AcousticTarget.CLEAR;
		}
		final int cells = this.solvesThisTick < MAX_FULL_SOLVES_PER_TICK ? config.diffractionCells : 0;
		this.solvesThisTick++;
		this.query.begin(minecraft.level);
		try {
			return OcclusionSolver.solve(this.query, eye.x, eye.y, eye.z, x, y, z, config.occlusionStrength, cells, config.soundRedirection);
		} finally {
			this.query.end();
		}
	}

	/**
	 * 组合全局声学状态：环境混响、回声、听者所处介质与天气。
	 *
	 * @param level 世界
	 * @param camera 摄像机
	 * @param eye 听者位置
	 * @return 全局状态
	 */
	private GlobalAcoustics buildGlobal(final ClientLevel level, final Camera camera, final Vec3 eye) {
		final EnvironmentSnapshot environment = this.probe.latest();
		ReverbParams reverb = ReverbModel.reverbFor(environment);
		EchoParams echo = ReverbModel.echoFor(environment);
		float mediumGain = 1.0f;
		float mediumHF = 1.0f;
		final FogType fog = camera.getFluidInCamera();
		switch (fog) {
			case WATER -> {
				// 水中高频被强烈吸收，声音沉闷而略带悠长的回响。
				mediumGain = 0.8f;
				mediumHF = 0.15f;
				reverb = ReverbParams.UNDERWATER;
				echo = EchoParams.NONE;
			}
			case LAVA -> {
				mediumGain = 0.6f;
				mediumHF = 0.06f;
				reverb = ReverbParams.LAVA;
				echo = EchoParams.NONE;
			}
			case POWDER_SNOW -> {
				mediumGain = 0.85f;
				mediumHF = 0.45f;
				echo = EchoParams.NONE;
			}
			default -> {
				// 空气中不做介质滤波。
			}
		}
		final BlockPos pos = BlockPos.containing(eye);
		float air = 1.0f;
		if (level.isRaining() && level.canSeeSky(pos)) {
			// 雨天空气湿度大，远处声音的高频衰减更明显。
			air = level.isThundering() ? 1.8f : 1.6f;
		}
		return new GlobalAcoustics(reverb, echo, mediumGain, mediumHF, air, environment);
	}

	/**
	 * 最近一次环境探测结果（用于界面显示）。
	 *
	 * @return 环境快照
	 */
	public EnvironmentSnapshot environment() {
		return this.probe.latest();
	}
}
