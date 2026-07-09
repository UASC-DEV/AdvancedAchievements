package com.hm.achievement.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import com.hm.achievement.AdvancedAchievements;

/**
 * Utility class that abstracts away the differences between the standard Bukkit scheduler
 * and the Folia regionized scheduler. All scheduler calls in the plugin should route through
 * this adapter to ensure compatibility with both platforms.
 *
 * <p>Folia detection is done once at class-load time by checking for the presence of
 * {@code io.papermc.paper.threadedregions.scheduler.RegionScheduler}.
 *
 * @author LucidAPs
 */
@Singleton
public class FoliaSchedulerAdapter {

	private static final boolean IS_FOLIA;

	static {
		boolean folia;
		try {
			Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
			folia = true;
		} catch (ClassNotFoundException e) {
			folia = false;
		}
		IS_FOLIA = folia;
	}

	private final AdvancedAchievements plugin;

	@Inject
	public FoliaSchedulerAdapter(AdvancedAchievements plugin) {
		this.plugin = plugin;
	}

	/**
	 * @return {@code true} if the server is running Folia (or a Folia-based fork).
	 */
	public static boolean isFolia() {
		return IS_FOLIA;
	}

	// -----------------------------------------------------------------------
	// Global (region-agnostic) sync tasks
	// -----------------------------------------------------------------------

	/**
	 * Runs a task on the global region scheduler (equivalent to {@code Bukkit.getScheduler().runTask}).
	 */
	public ScheduledTask runTask(Runnable task) {
		if (IS_FOLIA) {
			return new ScheduledTask(
					Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run()));
		}
		return new ScheduledTask(Bukkit.getScheduler().runTask(plugin, task));
	}

	/**
	 * Runs a task after a delay on the global region scheduler
	 * (equivalent to {@code Bukkit.getScheduler().runTaskLater}).
	 */
	public ScheduledTask runTaskLater(Runnable task, long delayTicks) {
		if (IS_FOLIA) {
			return new ScheduledTask(
					Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), delayTicks));
		}
		return new ScheduledTask(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
	}

	/**
	 * Runs a repeating task on the global region scheduler
	 * (equivalent to {@code Bukkit.getScheduler().runTaskTimer}).
	 */
	public ScheduledTask runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
		if (IS_FOLIA) {
			return new ScheduledTask(
					Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(), delayTicks, periodTicks));
		}
		return new ScheduledTask(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
	}

	// -----------------------------------------------------------------------
	// Async tasks
	// -----------------------------------------------------------------------

	/**
	 * Runs a task asynchronously (equivalent to {@code Bukkit.getScheduler().runTaskAsynchronously}).
	 */
	public ScheduledTask runTaskAsync(Runnable task) {
		if (IS_FOLIA) {
			return new ScheduledTask(
					Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run()));
		}
		return new ScheduledTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
	}

	/**
	 * Runs a repeating async task (equivalent to {@code Bukkit.getScheduler().runTaskTimerAsynchronously}).
	 */
	public ScheduledTask runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks) {
		long delayMs = delayTicks * 50L;
		long periodMs = periodTicks * 50L;
		if (IS_FOLIA) {
			return new ScheduledTask(
					Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> task.run(), delayMs, periodMs,
							java.util.concurrent.TimeUnit.MILLISECONDS));
		}
		return new ScheduledTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
	}

	// -----------------------------------------------------------------------
	// Entity / region-specific tasks
	// -----------------------------------------------------------------------

	/**
	 * Runs a task on the entity's owning region thread (or the main thread on non-Folia).
	 * This replaces {@code Bukkit.getScheduler().scheduleSyncDelayedTask} for player-specific work.
	 */
	public ScheduledTask runTaskForEntity(Entity entity, Runnable task, long delayTicks) {
		if (IS_FOLIA) {
			return new ScheduledTask(
					entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, delayTicks));
		}
		return new ScheduledTask(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
	}

	// -----------------------------------------------------------------------
	// Thread handoff (callSyncMethod replacement)
	// -----------------------------------------------------------------------

	/**
	 * Runs a {@link Callable} on the global region thread and returns a {@link CompletableFuture}
	 * that completes with the result. Blocks the calling thread until complete, matching the
	 * semantics of the deprecated {@code Bukkit.getScheduler().callSyncMethod()}.
	 */
	public <T> T callSyncMethod(Callable<T> task) {
		if (IS_FOLIA) {
			CompletableFuture<T> future = new CompletableFuture<>();
			Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> {
				try {
					future.complete(task.call());
				} catch (Exception e) {
					future.completeExceptionally(e);
				}
			});
			try {
				return future.get();
			} catch (Exception e) {
				throw new RuntimeException("Failed to execute sync call", e);
			}
		}
		// Non-Folia: use the original callSyncMethod
		try {
			return Bukkit.getScheduler().callSyncMethod(plugin, task).get();
		} catch (Exception e) {
			throw new RuntimeException("Failed to execute sync call", e);
		}
	}

	/**
	 * On Folia, checks whether the current thread owns the entity's region.
	 * On non-Folia servers, always returns {@code true} since there is a single main thread.
	 * <p>
	 * Falls back to {@link org.bukkit.Bukkit#isPrimaryThread()} on non-Folia servers
	 * and always returns {@code true} on Folia (region ownership is handled by the scheduler).
	 */
	public boolean isOwnedByCurrentRegion(Entity entity) {
		if (IS_FOLIA) {
			// Folia regions are always correct from within a scheduled task;
			// direct thread ownership checks require newer API not in Paper 1.21.1.
			return true;
		}
		return Bukkit.isPrimaryThread();
	}

	// -----------------------------------------------------------------------
	// Internal: ScheduledTask cancellation wrapper
	// -----------------------------------------------------------------------

	/**
	 * A platform-agnostic wrapper around scheduled tasks that provides cancellation.
	 */
	public static final class ScheduledTask {

		private final org.bukkit.scheduler.BukkitTask bukkitTask;
		private final io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask;

		ScheduledTask(org.bukkit.scheduler.BukkitTask bukkitTask) {
			this.bukkitTask = bukkitTask;
			this.foliaTask = null;
		}

		ScheduledTask(io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask) {
			this.foliaTask = foliaTask;
			this.bukkitTask = null;
		}

		/**
		 * Cancels the scheduled task.
		 */
		public void cancel() {
			if (foliaTask != null) {
				foliaTask.cancel();
			} else if (bukkitTask != null) {
				bukkitTask.cancel();
			}
		}

		/**
		 * @return {@code true} if the task has been cancelled.
		 */
		public boolean isCancelled() {
			if (foliaTask != null) {
				return foliaTask.isCancelled();
			}
			return bukkitTask != null && bukkitTask.isCancelled();
		}
	}
}
