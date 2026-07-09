package com.hm.achievement.listener.statistics;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import com.hm.achievement.AdvancedAchievements;
import com.hm.achievement.category.NormalAchievements;
import com.hm.achievement.config.AchievementMap;
import com.hm.achievement.db.AbstractDatabaseManager;
import com.hm.achievement.db.CacheManager;
import com.hm.achievement.db.data.ConnectionInformation;
import com.hm.achievement.utils.FoliaSchedulerAdapter;

/**
 * Listener class to deal with Connections achievements. This class uses delays processing of tasks to avoid spamming a
 * barely connected player.
 *
 * @author Pyves
 *
 */
@Singleton
public class ConnectionsListener extends AbstractListener {

	private final AdvancedAchievements advancedAchievements;
	private final AbstractDatabaseManager databaseManager;
	private final FoliaSchedulerAdapter schedulerAdapter;

	@Inject
	public ConnectionsListener(@Named("main") YamlConfiguration mainConfig, AchievementMap achievementMap,
			CacheManager cacheManager, AdvancedAchievements advancedAchievements, AbstractDatabaseManager databaseManager,
			FoliaSchedulerAdapter schedulerAdapter) {
		super(NormalAchievements.CONNECTIONS, mainConfig, achievementMap, cacheManager);
		this.advancedAchievements = advancedAchievements;
		this.databaseManager = databaseManager;
		this.schedulerAdapter = schedulerAdapter;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerJoin(PlayerJoinEvent event) {
		scheduleAwardConnection(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWorldChanged(PlayerChangedWorldEvent event) {
		scheduleAwardConnection(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onGameModeChange(PlayerGameModeChangeEvent event) {
		scheduleAwardConnection(event.getPlayer());
	}

	/**
	 * Schedules a delayed task to deal with Connection achievements.
	 *
	 * @param player
	 */
	private void scheduleAwardConnection(Player player) {
		schedulerAdapter.runTaskAsync(() -> {
			ConnectionInformation connectionInformation = databaseManager.getConnectionInformation(player.getUniqueId());
			if (!ConnectionInformation.today().equals(connectionInformation.getDate())) {
				// Switch to entity's region thread as Bukkit APIs require correct thread context.
				schedulerAdapter.runTaskForEntity(player, () -> {
					if (player.isOnline() && shouldIncreaseBeTakenIntoAccount(player, category)) {
						long updatedConnectionCount = connectionInformation.getCount() + 1;
						databaseManager.updateConnectionInformation(player.getUniqueId(), updatedConnectionCount);
						checkThresholdsAndAchievements(player, category, updatedConnectionCount);
					}
				}, 100);
			}
		});
	}
}
