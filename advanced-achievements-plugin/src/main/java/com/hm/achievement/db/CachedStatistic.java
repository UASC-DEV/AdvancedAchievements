package com.hm.achievement.db;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class used to provide a cache wrapper for a database statistic.
 * <p>
 * All fields use atomic types to guarantee visibility and atomicity across threads.
 * This is required for Folia compatibility where event handlers for different regions
 * may execute concurrently.
 *
 * @author Pyves
 */
public class CachedStatistic {

	private final AtomicLong value;
	// Indicates whether this in-memory value has been written to the database.
	// Written by the async DB flush thread; read by both the game threads and the async thread.
	private final AtomicBoolean databaseConsistent;
	// Indicates whether the player linked to this statistic has recently disconnected.
	private final AtomicBoolean disconnection;

	public CachedStatistic(long value, boolean databaseConsistent) {
		this.value = new AtomicLong(value);
		this.databaseConsistent = new AtomicBoolean(databaseConsistent);
		this.disconnection = new AtomicBoolean(false);
	}

	public long getValue() {
		return value.get();
	}

	public void setValue(long newValue) {
		value.set(newValue);
		databaseConsistent.set(false);
	}

	public boolean isDatabaseConsistent() {
		return databaseConsistent.get();
	}

	/**
	 * Attempts to mark this statistic as consistent with the database. Uses compare-and-set
	 * to prevent races with concurrent {@link #setValue(long)} calls: if another thread has
	 * already set databaseConsistent to {@code false} (because the value changed), this
	 * method returns {@code false} and the caller should skip the database write for this cycle.
	 *
	 * @return {@code true} if the flag was successfully claimed, {@code false} if a concurrent
	 *         modification means the DB write should be deferred to the next flush cycle.
	 */
	public boolean prepareDatabaseWrite() {
		return databaseConsistent.compareAndSet(false, true);
	}

	public boolean didPlayerDisconnect() {
		return disconnection.get();
	}

	public void signalPlayerDisconnection() {
		disconnection.set(true);
	}

	public void resetDisconnection() {
		disconnection.set(false);
	}
}
