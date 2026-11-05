package dev.pedro.vprng.vjf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VjfDetectionTest {
    @Test
    void bothTriggersMeansPresentAndUsable() {
        var r = VjfDetection.interpret(List.of("vjf_range_toggle", "vjf_single", "vjf_multiple"));
        assertEquals(VjfDetection.Status.PRESENT, r.status());
        assertTrue(r.single());
        assertTrue(r.multiple());
        assertTrue(r.rangeToggle());
        assertTrue(r.usable());
    }

    @Test
    void oneTriggerIsEnoughToBeUsable() {
        var onlyMultiple = VjfDetection.interpret(List.of("vjf_multiple"));
        assertTrue(onlyMultiple.usable());
        assertFalse(onlyMultiple.single());
        var onlySingle = VjfDetection.interpret(List.of("vjf_single"));
        assertTrue(onlySingle.usable());
        assertFalse(onlySingle.multiple());
    }

    @Test
    void noSuggestionsMeansAbsent() {
        var r = VjfDetection.interpret(List.of());
        assertEquals(VjfDetection.Status.ABSENT, r.status());
        assertFalse(r.usable());
        assertTrue(r.note().contains("vjf_single"));
    }

    @Test
    void onlyTheRangeToggleIsNotEnoughToForceJobs() {
        var r = VjfDetection.interpret(List.of("vjf_range_toggle"));
        assertEquals(VjfDetection.Status.ABSENT, r.status());
        assertFalse(r.usable());
        assertTrue(r.rangeToggle());
    }

    @Test
    void unrelatedTriggersAreIgnored() {
        var r = VjfDetection.interpret(List.of("vjf_something_else", "other_pack_single", "VJF_SINGLE"));
        assertEquals(VjfDetection.Status.ABSENT, r.status());
    }

    @Test
    void describeListsWhatWasFound() {
        assertEquals("present (vjf_single, vjf_multiple)",
                VjfDetection.interpret(List.of("vjf_single", "vjf_multiple")).describe());
        assertEquals("present (vjf_multiple)", VjfDetection.interpret(List.of("vjf_multiple")).describe());
        assertTrue(VjfDetection.Result.unknown("x").describe().startsWith("unknown"));
        assertFalse(VjfDetection.Result.checking().usable());
    }
}
