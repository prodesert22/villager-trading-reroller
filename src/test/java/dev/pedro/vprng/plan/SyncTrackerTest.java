package dev.pedro.vprng.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SyncTrackerTest {
    @Test
    void startsUnsyncedAndNeedsTwoConsecutiveInSyncOpens() {
        SyncTracker t = new SyncTracker();
        assertFalse(t.synced());
        t.onOpen(10, 11);
        assertFalse(t.synced());
        t.onOpen(11, 12);
        assertTrue(t.synced());
    }

    @Test
    void anyDriftResetsTheStreakAndCountsIt() {
        SyncTracker t = new SyncTracker();
        t.onOpen(0, 1);
        t.onOpen(1, 2);
        assertTrue(t.synced());
        t.onOpen(2, 2); // tracker did not advance: someone else / pre-generated offers
        assertFalse(t.synced());
        assertEquals(1, t.drifts());
        t.onOpen(2, 5); // resync jump to a different position is also not "+1"
        assertEquals(2, t.drifts());
        assertFalse(t.synced());
    }

    @Test
    void unknownPositionCountsAsDrift() {
        SyncTracker t = new SyncTracker();
        t.onOpen(-1, 0);
        assertEquals(1, t.drifts());
        assertFalse(t.synced());
    }

    @Test
    void singleplayerIsAlwaysTrusted() {
        SyncTracker t = new SyncTracker();
        t.assumeSynced();
        assertTrue(t.synced());
    }

    @Test
    void resetClearsEverything() {
        SyncTracker t = new SyncTracker();
        t.onOpen(0, 1);
        t.onOpen(1, 2);
        t.onOpen(2, 2);
        t.reset();
        assertEquals(0, t.drifts());
        assertEquals(0, t.streak());
        assertFalse(t.synced());
    }
}
