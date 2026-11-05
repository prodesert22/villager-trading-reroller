package dev.pedro.vprng.plan;

/**
 * Decides whether the predicted sequence position can be trusted.
 *
 * <p>Each open of a fresh burner should advance the tracked next round by exactly one. If it does not
 * (another player consumed the shared sequence, wrong seed, or the villager generated its trades on its own
 * during daytime work), the position is <em>unsynced</em> and the controller falls back to pure
 * verification: it still stops on the first observed hit, it just stops claiming to know how far away it is.</p>
 */
public final class SyncTracker {
    /** Consecutive in-sync opens needed before the prediction is trusted again. */
    public static final int RESYNC_STREAK = 2;

    private int streak;
    private int drifts;

    /** @param before next round before the open, @param after next round after the tracker processed the offers. */
    public void onOpen(int before, int after) {
        if (before >= 0 && after == before + 1) {
            streak++;
        } else {
            streak = 0;
            drifts++;
        }
    }

    /** Singleplayer reads the exact server state, so the position is always trusted. */
    public void assumeSynced() {
        streak = RESYNC_STREAK;
    }

    public boolean synced() {
        return streak >= RESYNC_STREAK;
    }

    public int streak() {
        return streak;
    }

    public int drifts() {
        return drifts;
    }

    public void reset() {
        streak = 0;
        drifts = 0;
    }
}
