package io.github.obsidianplusplus.spatialaudio.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.obsidianplusplus.spatialaudio.client.screen.SpatialAudioConfigScreen;

/**
 * Mod Menu 集成：在模组列表中提供“配置”按钮。未安装 Mod Menu 时此类不会被加载。
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SpatialAudioConfigScreen::new;
	}
}
