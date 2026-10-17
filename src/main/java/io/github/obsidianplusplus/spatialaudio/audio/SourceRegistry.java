package io.github.obsidianplusplus.spatialaudio.audio;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正在播放的声源注册表。
 *
 * <p>声音线程在声源开始播放时登记、销毁时移除；主线程遍历它来计算遮挡。
 * 使用 {@link ConcurrentHashMap} 保证跨线程遍历安全（弱一致性即可）。
 */
public final class SourceRegistry {
	/** 声源 ID 到状态的映射。 */
	private final ConcurrentHashMap<Integer, SpatialSource> sources = new ConcurrentHashMap<>();

	/**
	 * 登记一个开始播放的声源。
	 *
	 * @param source 声源状态
	 */
	public void add(final SpatialSource source) {
		source.active = true;
		this.sources.put(source.sourceId(), source);
	}

	/**
	 * 移除声源（仅当登记的对象与传入对象一致时才移除，防止 ID 复用误删）。
	 *
	 * @param source 声源状态
	 */
	public void remove(final SpatialSource source) {
		source.active = false;
		this.sources.remove(source.sourceId(), source);
	}

	/**
	 * 清空注册表（OpenAL 上下文销毁时调用）。
	 */
	public void clear() {
		this.sources.values().forEach(source -> source.active = false);
		this.sources.clear();
	}

	/**
	 * 全部已登记声源的只读视图。
	 *
	 * @return 集合视图
	 */
	public Collection<SpatialSource> all() {
		return Collections.unmodifiableCollection(this.sources.values());
	}

	/**
	 * 已登记的声源数量。
	 *
	 * @return 数量
	 */
	public int size() {
		return this.sources.size();
	}
}
