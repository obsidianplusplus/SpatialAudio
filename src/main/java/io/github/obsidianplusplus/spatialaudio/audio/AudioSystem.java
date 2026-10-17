package io.github.obsidianplusplus.spatialaudio.audio;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRenderer;
import io.github.obsidianplusplus.spatialaudio.acoustics.EchoParams;
import io.github.obsidianplusplus.spatialaudio.acoustics.ReverbParams;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.HrtfMode;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.config.SpatialRenderer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.client.Minecraft;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTDeferredUpdates;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.lwjgl.openal.SOFTOutputMode;
import org.lwjgl.system.MemoryStack;

/**
 * 空间音频系统中枢：连接 Minecraft 声音引擎、OpenAL 扩展、头部追踪与声学模拟。
 *
 * <p>生命周期：
 * <ol>
 *   <li>{@link #buildContextAttributes} —— 创建 OpenAL 上下文前，按配置与渲染方式请求 HRTF、输出模式与辅助发送通道；</li>
 *   <li>{@link #onLibraryInitialized} —— 上下文创建后探测扩展能力、读取 HRTF 状态并初始化 EFX；</li>
 *   <li>{@link #onEngineLoaded} —— 取得原版声音线程的执行器，之后周期性更新都投递到该线程；</li>
 *   <li>{@link #onLibraryCleanup} —— 上下文销毁前停止一切 OpenAL 调用。</li>
 * </ol>
 * 周期性更新由 {@link AudioUpdateThread} 触发，但真正的 OpenAL 调用全部在原版声音线程中执行，
 * 与原版对声源的创建、销毁严格串行，避免产生多余的 OpenAL 错误。
 */
public final class AudioSystem {
	/** 全局唯一实例。 */
	private static final AudioSystem INSTANCE = new AudioSystem();

	/** 投递的更新任务超过该时长仍未执行，视为已被丢弃（纳秒）。 */
	private static final long PENDING_TIMEOUT_NANOS = 250_000_000L;

	/** 声源声学参数的更新间隔（纳秒）。 */
	private static final long SOURCE_UPDATE_INTERVAL_NANOS = 50_000_000L;

	/** 混响与回声参数的更新间隔（纳秒）。 */
	private static final long ENVIRONMENT_UPDATE_INTERVAL_NANOS = 100_000_000L;

	/** 混响参数过渡的时间常数（秒）。 */
	private static final double REVERB_SMOOTHING_SECONDS = 0.6;

	/** 请求的辅助发送通道数量（混响 + 回声）。 */
	private static final int REQUESTED_AUX_SENDS = 2;

	/** 保护上下文生命周期的锁。 */
	private final ReentrantLock lock = new ReentrantLock();

	/** 声源注册表。 */
	private final SourceRegistry sources = new SourceRegistry();

	/** 听者控制。 */
	private final ListenerController listener = new ListenerController();

	/** EFX 管理。 */
	private final EfxManager efx = new EfxManager();

	/** 逐声源处理。 */
	private final SourceProcessor processor = new SourceProcessor(this);

	/** 是否存在可用的 OpenAL 上下文。 */
	private volatile boolean alive;

	/** 当前输出设备名称。 */
	private volatile String deviceName = "";

	/** OpenAL 扩展能力。 */
	private volatile Capabilities capabilities = Capabilities.NONE;

	/** HRTF 状态。 */
	private volatile HrtfInfo hrtf = HrtfInfo.UNKNOWN;

	/** 当前 OpenAL 上下文实际采用的渲染方式（创建上下文时确定）。 */
	private volatile SpatialRenderer activeRenderer = SpatialRenderer.BUILT_IN;

	/** 设备上可用的 HRTF 数据集名称。 */
	private volatile List<String> availableHrtfs = List.of();

	/** 原版声音线程执行器。 */
	private volatile Executor executor;

	/** 主线程计算的全局声学状态。 */
	private volatile GlobalAcoustics global = GlobalAcoustics.NEUTRAL;

	/** 是否检测到 Sound Physics Remastered。 */
	private volatile boolean soundPhysicsPresent;

	/** 是否有尚未执行的更新任务。 */
	private final AtomicBoolean pending = new AtomicBoolean();

	/** 最近一次投递更新任务的时刻。 */
	private volatile long pendingSince;

	/** 上一次声源更新时刻（声音线程）。 */
	private long lastSourceUpdate;

	/** 上一次环境更新时刻（声音线程）。 */
	private long lastEnvironmentUpdate;

	/** 当前已平滑的混响参数（声音线程）。 */
	private ReverbParams currentReverb = ReverbParams.DRY;

	/** 当前已平滑的回声参数（声音线程）。 */
	private EchoParams currentEcho = EchoParams.NONE;

	/** 已写入的多普勒系数（声音线程）。 */
	private float appliedDoppler = -1.0f;

	/** 是否已经记录过更新异常（避免日志刷屏）。 */
	private boolean reportedUpdateError;

	private AudioSystem() {
	}

	/**
	 * 获取全局实例。
	 *
	 * @return 实例
	 */
	public static AudioSystem get() {
		return INSTANCE;
	}

	// ===================== 生命周期 =====================

	/**
	 * 构建 OpenAL 上下文属性（替代原版 Library#createAttributes）。
	 *
	 * @param device 已打开的设备句柄
	 * @param stack 内存栈（属性数组在其上分配）
	 * @param vanillaHrtf 原版“方向性音频”选项是否开启（且设备支持 HRTF）
	 * @return 属性数组；模组关闭或出错时返回 null，交由原版处理
	 */
	public IntBuffer buildContextAttributes(final long device, final MemoryStack stack, final boolean vanillaHrtf) {
		final SpatialAudioConfig config = ConfigManager.get();
		if (!config.enabled) {
			return null;
		}
		final AppleRenderer apple = AppleRenderer.get();
		if (apple.isLoopbackDevice(device)) {
			// Apple 空间音频：输出多声道声场，双耳渲染交给 Apple 引擎。
			this.availableHrtfs = List.of();
			return apple.buildLoopbackAttributes(device, stack);
		}
		final boolean systemStereo = config.renderer == SpatialRenderer.APPLE_SYSTEM_STEREO;
		try {
			final boolean hrtfExtension = ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF");
			final boolean outputModeExtension = ALC10.alcIsExtensionPresent(device, "ALC_SOFT_output_mode");
			final boolean efxExtension = ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
			final List<String> names = hrtfExtension ? enumerateHrtfs(device) : List.of();
			this.availableHrtfs = names;

			final IntBuffer attributes = stack.callocInt(16);
			if (hrtfExtension) {
				// Apple 系统空间化立体声：由 macOS 完成空间化，本模组必须输出未经 HRTF 处理的普通立体声。
				final boolean wantHrtf = !systemStereo && switch (config.hrtfMode) {
					case FORCE_ON -> true;
					case FOLLOW_VANILLA -> vanillaHrtf;
					case OFF -> false;
				};
				attributes.put(SOFTHRTF.ALC_HRTF_SOFT).put(wantHrtf ? ALC10.ALC_TRUE : ALC10.ALC_FALSE);
				if (wantHrtf && !names.isEmpty()) {
					attributes.put(SOFTHRTF.ALC_HRTF_ID_SOFT).put(Math.max(0, names.indexOf(config.hrtfName)));
				}
				if (wantHrtf && outputModeExtension && config.hrtfMode == HrtfMode.FORCE_ON) {
					// 要求以双声道 HRTF 方式输出，即使系统把设备报告为多声道也坚持双耳渲染。
					attributes.put(SOFTOutputMode.ALC_OUTPUT_MODE_SOFT).put(SOFTOutputMode.ALC_STEREO_HRTF_SOFT);
				}
			}
			if (systemStereo && outputModeExtension) {
				// 普通声像立体声（不做 UHJ 编码），macOS“空间化立体声”把左右声道当作前方两只虚拟扬声器。
				attributes.put(SOFTOutputMode.ALC_OUTPUT_MODE_SOFT).put(SOFTOutputMode.ALC_STEREO_BASIC_SOFT);
			}
			attributes.put(SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT).put(ALC10.ALC_TRUE);
			if (efxExtension) {
				attributes.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(REQUESTED_AUX_SENDS);
			}
			attributes.put(0);
			ALC10.alcGetError(device);
			return attributes.flip();
		} catch (RuntimeException e) {
			SpatialAudio.LOGGER.error("构建 OpenAL 上下文属性失败，回退到原版设置", e);
			return null;
		}
	}

	/**
	 * OpenAL 上下文创建完成后调用（在原版 Library#init 末尾）。
	 *
	 * @param device 设备句柄
	 * @param name 设备名称
	 */
	public void onLibraryInitialized(final long device, final String name) {
		this.lock.lock();
		try {
			this.deviceName = name == null ? "" : name;
			this.sources.clear();
			this.global = GlobalAcoustics.NEUTRAL;
			this.currentReverb = ReverbParams.DRY;
			this.currentEcho = EchoParams.NONE;
			this.appliedDoppler = -1.0f;
			this.reportedUpdateError = false;

			final ALCapabilities al = AL.getCapabilities();
			final SpatialAudioConfig config = ConfigManager.get();
			final boolean loopback = AppleRenderer.get().isLoopbackDevice(device);
			if (loopback) {
				this.activeRenderer = SpatialRenderer.APPLE;
			} else if (config.enabled && config.renderer == SpatialRenderer.APPLE_SYSTEM_STEREO) {
				this.activeRenderer = SpatialRenderer.APPLE_SYSTEM_STEREO;
			} else {
				this.activeRenderer = SpatialRenderer.BUILT_IN;
			}
			final boolean hrtfExtension = ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF");
			final boolean efxExtension = ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
			final int sends = efxExtension ? ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS) : 0;
			this.capabilities = new Capabilities(efxExtension, sends, al.AL_SOFT_source_spatialize, al.AL_EXT_STEREO_ANGLES,
				al.AL_SOFT_deferred_updates, hrtfExtension);

			if (hrtfExtension) {
				final boolean enabled = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_SOFT) == ALC10.ALC_TRUE;
				final int status = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
				final String hrtfName = enabled ? ALC10.alcGetString(device, SOFTHRTF.ALC_HRTF_SPECIFIER_SOFT) : "";
				this.hrtf = new HrtfInfo(enabled, status, hrtfName == null ? "" : hrtfName);
				if (this.availableHrtfs.isEmpty()) {
					this.availableHrtfs = enumerateHrtfs(device);
				}
			} else {
				this.hrtf = HrtfInfo.UNSUPPORTED;
			}
			if (this.activeRenderer == SpatialRenderer.APPLE) {
				this.hrtf = HrtfInfo.APPLE;
			} else if (this.activeRenderer == SpatialRenderer.APPLE_SYSTEM_STEREO) {
				this.hrtf = HrtfInfo.SYSTEM_STEREO;
			}
			ALC10.alcGetError(device);

			if (efxExtension && sends > 0 && config.enabled) {
				this.efx.init(sends);
			} else {
				this.efx.invalidate();
			}
			AL11.alSpeedOfSound(343.3f);
			AL10.alDopplerFactor(0.0f);
			EfxManager.clearErrors();
			this.alive = true;
			SpatialAudio.LOGGER.info("空间音频已接管设备「{}」：渲染方式 {}，HRTF {}（{}，状态码 {}），立体声定位{}，屏幕锚定{}",
				this.deviceName,
				this.activeRenderer,
				this.hrtf.enabled() ? "已开启" : "未开启",
				this.hrtf.name().isEmpty() ? "-" : this.hrtf.name(),
				this.hrtf.status(),
				this.capabilities.sourceSpatialize() ? "可用" : "不可用",
				this.capabilities.stereoAngles() ? "可用" : "不可用");
		} catch (RuntimeException e) {
			SpatialAudio.LOGGER.error("初始化空间音频失败，将以原版方式播放声音", e);
			this.alive = false;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * OpenAL 上下文即将销毁时调用（在原版 Library#cleanup 开头）。
	 */
	public void onLibraryCleanup() {
		this.lock.lock();
		try {
			this.alive = false;
			this.efx.invalidate();
			this.sources.clear();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 原版声音引擎加载完成时调用，记录声音线程执行器。
	 *
	 * @param soundExecutor 声音线程执行器
	 */
	public void onEngineLoaded(final Executor soundExecutor) {
		this.executor = soundExecutor;
	}

	/**
	 * 重新加载原版声音引擎（HRTF 等上下文属性变化后必须调用，效果等同于切换“方向性音频”选项）。
	 */
	public void reloadSoundEngine() {
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.getSoundManager() != null) {
			minecraft.getSoundManager().reload();
		}
	}

	// ===================== 周期性更新 =====================

	/**
	 * 由更新线程调用：向原版声音线程投递一次更新任务（同一时刻最多一个待执行任务）。
	 */
	public void scheduleUpdate() {
		if (!this.alive) {
			return;
		}
		final Executor target = this.executor;
		if (target == null) {
			return;
		}
		final long now = System.nanoTime();
		if (this.pending.get() && now - this.pendingSince < PENDING_TIMEOUT_NANOS) {
			return;
		}
		this.pending.set(true);
		this.pendingSince = now;
		try {
			target.execute(this::runUpdate);
		} catch (RuntimeException e) {
			this.pending.set(false);
		}
	}

	/**
	 * 在声音线程中执行的更新：刷新听者朝向、屏幕锚定、声源滤波与环境混响。
	 */
	private void runUpdate() {
		this.pending.set(false);
		if (!this.lock.tryLock()) {
			return;
		}
		try {
			if (!this.alive) {
				return;
			}
			final SpatialAudioConfig config = ConfigManager.get();
			final long now = System.nanoTime();
			final boolean defer = this.capabilities.deferredUpdates();
			if (defer) {
				// 批量提交：本批次所有参数在同一个混音周期内生效，避免听者与声源更新错位。
				SOFTDeferredUpdates.alDeferUpdatesSOFT();
			}
			try {
				this.listener.refresh(config.enabled && config.useTrackerTranslation);
				this.listener.writeVelocity(config.enabled && config.dopplerEnabled);
				this.processor.updateAnchoring();
				if (now - this.lastSourceUpdate >= SOURCE_UPDATE_INTERVAL_NANOS) {
					final double dt = this.lastSourceUpdate == 0L ? 0.05 : Math.min(0.5, (now - this.lastSourceUpdate) / 1.0e9);
					this.processor.update(dt);
					this.lastSourceUpdate = now;
				}
				if (now - this.lastEnvironmentUpdate >= ENVIRONMENT_UPDATE_INTERVAL_NANOS) {
					final double dt = this.lastEnvironmentUpdate == 0L ? 0.1 : Math.min(1.0, (now - this.lastEnvironmentUpdate) / 1.0e9);
					this.updateEnvironment(config, dt);
					this.lastEnvironmentUpdate = now;
				}
			} finally {
				if (defer) {
					SOFTDeferredUpdates.alProcessUpdatesSOFT();
				}
			}
			final int error = AL10.alGetError();
			if (error != AL10.AL_NO_ERROR) {
				SpatialAudio.debug("空间音频更新产生 OpenAL 错误 0x{}", Integer.toHexString(error));
			}
		} catch (RuntimeException e) {
			if (!this.reportedUpdateError) {
				this.reportedUpdateError = true;
				SpatialAudio.LOGGER.error("空间音频更新出错（后续同类错误不再记录）", e);
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * 平滑更新混响、回声与多普勒参数。
	 *
	 * @param config 配置
	 * @param dt 时间间隔（秒）
	 */
	private void updateEnvironment(final SpatialAudioConfig config, final double dt) {
		final boolean acoustics = this.acousticsActive(config);
		final GlobalAcoustics state = this.global;
		final ReverbParams reverbTarget = acoustics && config.reverbEnabled
			? state.reverb().withWetScale(config.reverbGain)
			: ReverbParams.DRY;
		final EchoParams echoTarget;
		if (acoustics && config.echoEnabled) {
			final EchoParams e = state.echo();
			echoTarget = new EchoParams(e.delay(), e.lrDelay(), e.damping(), e.feedback(), e.spread(), e.strength() * config.echoGain);
		} else {
			echoTarget = EchoParams.NONE;
		}
		final float a = (float) (1.0 - Math.exp(-dt / REVERB_SMOOTHING_SECONDS));
		this.currentReverb = this.currentReverb.lerp(reverbTarget, a);
		this.currentEcho = this.currentEcho.lerp(echoTarget, a);
		this.efx.applyReverb(this.currentReverb);
		this.efx.applyEcho(this.currentEcho);

		final float doppler = config.enabled && config.dopplerEnabled ? config.dopplerFactor : 0.0f;
		if (doppler != this.appliedDoppler) {
			AL10.alDopplerFactor(doppler);
			this.appliedDoppler = doppler;
		}
	}

	// ===================== 查询 =====================

	/**
	 * 声学模拟当前是否生效。
	 *
	 * @param config 配置
	 * @return 生效时返回 true
	 */
	public boolean acousticsActive(final SpatialAudioConfig config) {
		return config.enabled && config.acousticsEnabled && this.efx.isReady()
			&& !(config.respectSoundPhysicsMod && this.soundPhysicsPresent);
	}

	/**
	 * 记录是否检测到 Sound Physics Remastered。
	 *
	 * @param present 是否存在
	 */
	public void setSoundPhysicsPresent(final boolean present) {
		this.soundPhysicsPresent = present;
	}

	/**
	 * 是否检测到 Sound Physics Remastered。
	 *
	 * @return 存在时返回 true
	 */
	public boolean soundPhysicsPresent() {
		return this.soundPhysicsPresent;
	}

	/**
	 * 是否存在可用的 OpenAL 上下文。
	 *
	 * @return 可用时返回 true
	 */
	public boolean isAlive() {
		return this.alive;
	}

	/**
	 * 主线程发布新的全局声学状态。
	 *
	 * @param state 新状态
	 */
	public void setGlobal(final GlobalAcoustics state) {
		this.global = state == null ? GlobalAcoustics.NEUTRAL : state;
	}

	/**
	 * 当前全局声学状态。
	 *
	 * @return 状态
	 */
	public GlobalAcoustics global() {
		return this.global;
	}

	/**
	 * 声源注册表。
	 *
	 * @return 注册表
	 */
	public SourceRegistry sources() {
		return this.sources;
	}

	/**
	 * 听者控制器（仅声音线程使用）。
	 *
	 * @return 控制器
	 */
	public ListenerController listener() {
		return this.listener;
	}

	/**
	 * 逐声源处理器（仅声音线程使用）。
	 *
	 * @return 处理器
	 */
	public SourceProcessor processor() {
		return this.processor;
	}

	/**
	 * EFX 管理器（仅声音线程使用）。
	 *
	 * @return 管理器
	 */
	EfxManager efx() {
		return this.efx;
	}

	/**
	 * OpenAL 扩展能力。
	 *
	 * @return 能力
	 */
	public Capabilities capabilities() {
		return this.capabilities;
	}

	/**
	 * HRTF 状态。
	 *
	 * @return 状态
	 */
	public HrtfInfo hrtf() {
		return this.hrtf;
	}

	/**
	 * 当前 OpenAL 上下文实际采用的渲染方式。选择了 Apple 空间音频但未能启用（系统不支持或已回退）时为内置渲染器。
	 *
	 * @return 渲染方式
	 */
	public SpatialRenderer activeRenderer() {
		return this.activeRenderer;
	}

	/**
	 * 设备上可用的 HRTF 数据集。
	 *
	 * @return 名称列表
	 */
	public List<String> availableHrtfs() {
		return this.availableHrtfs;
	}

	/**
	 * 当前输出设备名称。
	 *
	 * @return 名称
	 */
	public String deviceName() {
		return this.deviceName;
	}

	/**
	 * 当前输出设备是否为 AirPods / Beats 等支持头部追踪的 Apple 耳机。
	 *
	 * @return 推测为 Apple 耳机时返回 true
	 */
	public boolean isAppleHeadphones() {
		final String name = this.deviceName.toLowerCase(Locale.ROOT);
		return name.contains("airpods") || name.contains("beats");
	}

	/**
	 * 枚举设备支持的 HRTF 数据集名称。
	 *
	 * @param device 设备句柄
	 * @return 名称列表
	 */
	private static List<String> enumerateHrtfs(final long device) {
		final List<String> names = new ArrayList<>();
		final int count = ALC10.alcGetInteger(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT);
		for (int i = 0; i < count; i++) {
			final String name = SOFTHRTF.alcGetStringiSOFT(device, SOFTHRTF.ALC_HRTF_SPECIFIER_SOFT, i);
			names.add(name == null ? "HRTF #" + i : name);
		}
		ALC10.alcGetError(device);
		return List.copyOf(names);
	}

	/**
	 * OpenAL 扩展能力。
	 *
	 * @param efx 是否支持 EFX
	 * @param auxSends 辅助发送通道数
	 * @param sourceSpatialize 是否支持强制立体声定位（AL_SOFT_source_spatialize）
	 * @param stereoAngles 是否支持立体声角度（AL_EXT_STEREO_ANGLES）
	 * @param deferredUpdates 是否支持批量提交（AL_SOFT_deferred_updates）
	 * @param hrtf 是否支持 HRTF 控制（ALC_SOFT_HRTF）
	 */
	public record Capabilities(boolean efx, int auxSends, boolean sourceSpatialize, boolean stereoAngles, boolean deferredUpdates, boolean hrtf) {
		/** 未初始化时的默认值。 */
		public static final Capabilities NONE = new Capabilities(false, 0, false, false, false, false);
	}

	/**
	 * HRTF 运行状态。
	 *
	 * @param enabled 是否已启用
	 * @param status ALC_HRTF_STATUS_SOFT 状态码
	 * @param name 当前数据集名称
	 */
	public record HrtfInfo(boolean enabled, int status, String name) {
		/** 尚未初始化。 */
		public static final HrtfInfo UNKNOWN = new HrtfInfo(false, -1, "");

		/** 设备不支持 HRTF。 */
		public static final HrtfInfo UNSUPPORTED = new HrtfInfo(false, -2, "");

		/** 双耳渲染由 Apple 空间音频引擎完成。 */
		public static final HrtfInfo APPLE = new HrtfInfo(true, -3, "Apple");

		/** 双耳渲染由 macOS 系统“空间化立体声”完成。 */
		public static final HrtfInfo SYSTEM_STEREO = new HrtfInfo(true, -4, "macOS");

		/**
		 * 状态码对应的翻译键。
		 *
		 * @return 翻译键
		 */
		public String statusKey() {
			if (this.status == SOFTHRTF.ALC_HRTF_ENABLED_SOFT) {
				return "spatialaudio.hrtf_status.enabled";
			} else if (this.status == SOFTHRTF.ALC_HRTF_REQUIRED_SOFT) {
				return "spatialaudio.hrtf_status.required";
			} else if (this.status == SOFTHRTF.ALC_HRTF_HEADPHONES_DETECTED_SOFT) {
				return "spatialaudio.hrtf_status.headphones";
			} else if (this.status == SOFTHRTF.ALC_HRTF_DENIED_SOFT) {
				return "spatialaudio.hrtf_status.denied";
			} else if (this.status == SOFTHRTF.ALC_HRTF_UNSUPPORTED_FORMAT_SOFT) {
				return "spatialaudio.hrtf_status.unsupported_format";
			} else if (this.status == SOFTHRTF.ALC_HRTF_DISABLED_SOFT) {
				return "spatialaudio.hrtf_status.disabled";
			} else if (this.status == -2) {
				return "spatialaudio.hrtf_status.unsupported";
			} else if (this.status == -3) {
				return "spatialaudio.hrtf_status.apple";
			} else if (this.status == -4) {
				return "spatialaudio.hrtf_status.system_stereo";
			}
			return "spatialaudio.hrtf_status.unknown";
		}
	}
}
