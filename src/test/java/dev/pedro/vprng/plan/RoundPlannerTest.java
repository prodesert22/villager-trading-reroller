package dev.pedro.vprng.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoundPlannerTest {
    @Test
    void roundsToBurnIsDistanceToHit() {
        assertEquals(0, RoundPlanner.roundsToBurn(10, 10));
        assertEquals(7, RoundPlanner.roundsToBurn(3, 10));
    }

    @Test
    void roundsToBurnNeverNegativeWhenHitAlreadyPassed() {
        assertEquals(0, RoundPlanner.roundsToBurn(12, 10));
    }

    @Test
    void roundsToBurnUnknownWithoutPrediction() {
        assertEquals(RoundPlanner.UNKNOWN, RoundPlanner.roundsToBurn(-1, 10));
        assertEquals(RoundPlanner.UNKNOWN, RoundPlanner.roundsToBurn(5, -1));
    }

    @Test
    void keeperOnlyWhenSyncedAndNextRoundIsTheHit() {
        assertTrue(RoundPlanner.nextOpenIsKeeper(0, true));
        assertFalse(RoundPlanner.nextOpenIsKeeper(0, false));
        assertFalse(RoundPlanner.nextOpenIsKeeper(1, true));
        assertFalse(RoundPlanner.nextOpenIsKeeper(RoundPlanner.UNKNOWN, true));
    }

    @Test
    void cyclesCountTheKeeperOpenToo() {
        // K = 0: only the keeper -> one cycle regardless of pool size
        assertEquals(1, RoundPlanner.cyclesNeeded(0, 1));
        assertEquals(1, RoundPlanner.cyclesNeeded(0, 8));
        // K = 3, pool of 4: burners 0-2 then keeper = 4 opens = exactly one cycle
        assertEquals(1, RoundPlanner.cyclesNeeded(3, 4));
        // K = 4, pool of 4: 5 opens -> two cycles
        assertEquals(2, RoundPlanner.cyclesNeeded(4, 4));
        // K a multiple of N still needs the extra keeper open
        assertEquals(3, RoundPlanner.cyclesNeeded(8, 4));
    }

    @Test
    void singleVillagerNeedsKPlusOneCycles() {
        assertEquals(138, RoundPlanner.cyclesNeeded(137, 1));
    }

    @Test
    void hugeKDoesNotOverflow() {
        assertEquals(1_000_001, RoundPlanner.cyclesNeeded(1_000_000, 1));
        assertEquals(1, RoundPlanner.cyclesNeeded(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
    }

    @Test
    void emptyPoolOrUnknownHasNoCycles() {
        assertEquals(RoundPlanner.UNKNOWN, RoundPlanner.cyclesNeeded(5, 0));
        assertEquals(RoundPlanner.UNKNOWN, RoundPlanner.cyclesNeeded(RoundPlanner.UNKNOWN, 4));
    }

    @Test
    void etaScalesWithCyclesAndIsNegativeWhenUnknown() {
        assertEquals(40.0, RoundPlanner.etaSeconds(7, 4, 20.0), 1e-9);
        assertTrue(RoundPlanner.etaSeconds(RoundPlanner.UNKNOWN, 4, 20.0) < 0);
        assertTrue(RoundPlanner.etaSeconds(7, 4, 0.0) < 0);
    }
}
