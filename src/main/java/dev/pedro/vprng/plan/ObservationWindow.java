package dev.pedro.vprng.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling list of the most recent observed trade rounds used to calibrate the sequence position.
 *
 * <p>Calibration needs <em>consecutive</em> rounds of the same trade set. Opens of our burners are consecutive
 * unless something else consumed the sequence in between (another player, a villager that generated its trades
 * on its own). When a search finds no match for the whole window the usual cause is exactly such a gap, so the
 * caller calls {@link #keepOnlyLatest()} and builds a fresh consecutive run from the newest observation.</p>
 */
public final class ObservationWindow<T> {
    private final int max;
    private final List<T> items = new ArrayList<>();

    public ObservationWindow(int max) {
        if (max < 1) throw new IllegalArgumentException("max must be >= 1");
        this.max = max;
    }

    public void add(T observation) {
        items.add(observation);
        while (items.size() > max) items.removeFirst();
    }

    /** Oldest first, in the order the rounds were generated. */
    public List<T> items() {
        return List.copyOf(items);
    }

    public int size() {
        return items.size();
    }

    /** Drops everything except the newest observation (a gap broke the consecutive run). */
    public void keepOnlyLatest() {
        if (items.size() <= 1) return;
        T latest = items.getLast();
        items.clear();
        items.add(latest);
    }

    public void clear() {
        items.clear();
    }
}
