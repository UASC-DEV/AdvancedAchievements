package com.hm.achievement.advancement;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.hm.achievement.utils.FoliaSchedulerAdapter;

public class AdvancementTabListener implements Listener {
	private final FoliaSchedulerAdapter schedulerAdapter;
	private final AdvancementManager advancementManager;

	public AdvancementTabListener(FoliaSchedulerAdapter schedulerAdapter, AdvancementManager advancementManager) {
		this.schedulerAdapter = schedulerAdapter;
		this.advancementManager = advancementManager;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		// 1 tick later so generation (if running) has time to register the parent
		schedulerAdapter.runTaskForEntity(e.getPlayer(),
				() -> advancementManager.ensureRootVisible(e.getPlayer()), 1L);
	}
}
