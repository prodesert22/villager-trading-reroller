package dev.pedro.vprng.plan;

/**
 * Pure planning maths for the shared trade-set sequence (no Minecraft types, unit-testable).
 *
 * <p>Vocabulary: every first open of a villager that has a profession but no offers yet consumes exactly
 * one <em>round</em> of the world-global sequence. {@code nextRound} is the round the next such open will
 * receive; {@code hitRound} is the round that contains a wanted enchantment. The number of rounds that
 * must be burned <em>before</em> the keeper is opened is {@code hitRound - nextRound}.</p>
 *
 * <p>Prediction cannot skip rounds (each needs one GUI open); it only tells us how many are left, so the
 * numbers here drive the ETA, the "impossible target" abort and which open is expected to be the keeper.</p>
 */
public final class RoundPlanner {
    /** Marker for "no prediction available". */
    public static final int UNKNOWN = -1;

    private RoundPlanner() {}

    /** Rounds to consume before the hit round is generated; 0 means the very next open is the hit. */
    public static int roundsToBurn(int nextRound, int hitRound) {
        if (nextRound < 0 || hitRound < 0) return UNKNOWN;
        return Math.max(0, hitRound - nextRound);
    }

    /** True when the next open is predicted to receive the hit round (needs a trustworthy position). */
    public static boolean nextOpenIsKeeper(int roundsToBurn, boolean synced) {
        return synced && roundsToBurn == 0;
    }

    /**
     * Full burner cycles still needed, counting the keeper open: ceil((K + 1) / poolSize).
     * Returns {@link #UNKNOWN} without a prediction or an empty pool.
     */
    public static int cyclesNeeded(int roundsToBurn, int poolSize) {
        if (roundsToBurn == UNKNOWN || poolSize <= 0) return UNKNOWN;
        return (int) (((long) roundsToBurn + 1 + poolSize - 1) / poolSize);
    }

    /** Estimated seconds to the hit given a measured per-cycle time, or a negative value when unknown. */
    public static double etaSeconds(int roundsToBurn, int poolSize, double cycleSeconds) {
        int cycles = cyclesNeeded(roundsToBurn, poolSize);
        if (cycles == UNKNOWN || cycleSeconds <= 0) return -1;
        return cycles * cycleSeconds;
    }
}
