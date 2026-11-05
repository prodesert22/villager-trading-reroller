package dev.pedro.vprng.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationWindowTest {
    @Test
    void keepsOrderOldestFirst() {
        var w = new ObservationWindow<String>(5);
        w.add("a");
        w.add("b");
        w.add("c");
        assertEquals(List.of("a", "b", "c"), w.items());
    }

    @Test
    void dropsOldestBeyondMax() {
        var w = new ObservationWindow<Integer>(3);
        for (int i = 1; i <= 5; i++) w.add(i);
        assertEquals(List.of(3, 4, 5), w.items());
        assertEquals(3, w.size());
    }

    @Test
    void keepOnlyLatestStartsAFreshRun() {
        var w = new ObservationWindow<String>(10);
        w.add("old1");
        w.add("old2");
        w.add("new");
        w.keepOnlyLatest();
        assertEquals(List.of("new"), w.items());
        w.add("next");
        assertEquals(List.of("new", "next"), w.items());
    }

    @Test
    void keepOnlyLatestOnEmptyOrSingleIsHarmless() {
        var w = new ObservationWindow<String>(4);
        w.keepOnlyLatest();
        assertEquals(0, w.size());
        w.add("x");
        w.keepOnlyLatest();
        assertEquals(List.of("x"), w.items());
    }

    @Test
    void itemsIsASnapshot() {
        var w = new ObservationWindow<String>(4);
        w.add("a");
        var snapshot = w.items();
        w.add("b");
        assertEquals(List.of("a"), snapshot);
    }

    @Test
    void clearEmpties() {
        var w = new ObservationWindow<String>(4);
        w.add("a");
        w.clear();
        assertEquals(0, w.size());
    }

    @Test
    void rejectsNonPositiveMax() {
        assertThrows(IllegalArgumentException.class, () -> new ObservationWindow<String>(0));
    }
}
