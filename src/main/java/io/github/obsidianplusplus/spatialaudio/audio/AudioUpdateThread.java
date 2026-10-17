package io.github.obsidianplusplus.spatialaudio.audio;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import java.util.concurrent.locks.LockSupport;

/**
 * 以固定频率（默认 100 Hz）驱动空间音频更新的后台线程。
 *
 * <p>原版只在每一帧渲染时更新一次听者朝向，帧率较低或游戏暂停时头部追踪会显得卡顿。
 * 本线程独立于渲染帧率，按配置频率向声音线程投递更新任务，使转头时的声场变化平滑、低延迟。
 */
public final class AudioUpdateThread extends Thread {
	/** 唯一实例。 */
	private static AudioUpdateThread instance;

	/** 运行标志。 */
	private volatile boolean running = true;

	private AudioUpdateThread() {
		super("SpatialAudio-Updater");
		this.setDaemon(true);
		this.setPriority(Thread.NORM_PRIORITY + 1);
	}

	/**
	 * 启动更新线程（重复调用无副作用）。
	 */
	public static synchronized void startIfNeeded() {
		if (instance == null || !instance.isAlive()) {
			instance = new AudioUpdateThread();
			instance.start();
		}
	}

	/**
	 * 停止更新线程。
	 */
	public static synchronized void shutdown() {
		if (instance != null) {
			instance.running = false;
			LockSupport.unpark(instance);
			instance = null;
		}
	}

	@Override
	public void run() {
		while (this.running) {
			final long start = System.nanoTime();
			try {
				AudioSystem.get().scheduleUpdate();
			} catch (RuntimeException e) {
				SpatialAudio.LOGGER.error("调度空间音频更新失败", e);
			}
			final long period = 1_000_000_000L / Math.max(20, ConfigManager.get().listenerUpdateRate);
			final long remaining = period - (System.nanoTime() - start);
			if (remaining > 0L) {
				LockSupport.parkNanos(this, remaining);
			}
		}
	}
}
