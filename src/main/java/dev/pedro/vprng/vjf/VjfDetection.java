package dev.pedro.vprng.vjf;

import dev.pedro.vprng.i18n.I18n;
import java.util.Collection;

/**
 * Pure interpretation of the server's {@code /trigger vjf_} suggestions (no Minecraft types, unit-testable).
 *
 * <p>The Villager Job Fix data pack exposes {@code vjf_single} and {@code vjf_multiple} as scoreboard
 * triggers. The vanilla {@code /trigger} command only suggests triggers that are <em>enabled for the asking
 * player</em>, so a suggestion means "exists and usable by you right now". The absence of a suggestion cannot
 * tell "pack not installed" apart from "installed but the trigger is not enabled for you", so the note says both.</p>
 */
public final class VjfDetection {
    public static final String SINGLE = "vjf_single";
    public static final String MULTIPLE = "vjf_multiple";
    public static final String RANGE_TOGGLE = "vjf_range_toggle";

    public enum Status {
        /** Not asked yet, or the server did not answer (timeout / request cancelled by chat typing). */
        UNKNOWN,
        CHECKING,
        PRESENT,
        ABSENT
    }

    public record Result(Status status, boolean single, boolean multiple, boolean rangeToggle, String note) {
        public static Result unknown(String note) {
            return new Result(Status.UNKNOWN, false, false, false, note);
        }

        public static Result checking() {
            return new Result(Status.CHECKING, false, false, false, I18n.tr("vjf.asking"));
        }

        /** True when at least one of the two forcing triggers can be used. */
        public boolean usable() {
            return status == Status.PRESENT && (single || multiple);
        }

        public String describe() {
            return switch (status) {
                case PRESENT -> I18n.tr("vjf.present", (single ? SINGLE : "") + (single && multiple ? ", " : "")
                        + (multiple ? MULTIPLE : "") + (rangeToggle ? ", " + RANGE_TOGGLE : ""));
                case ABSENT -> I18n.tr("vjf.notDetected", note);
                case CHECKING -> I18n.tr("vjf.checking");
                case UNKNOWN -> I18n.tr("vjf.unknown", note);
            };
        }
    }

    private VjfDetection() {}

    /** @param suggestions texts the server suggested for {@code trigger vjf_} */
    public static Result interpret(Collection<String> suggestions) {
        boolean single = false;
        boolean multiple = false;
        boolean rangeToggle = false;
        for (String s : suggestions) {
            if (SINGLE.equals(s)) single = true;
            else if (MULTIPLE.equals(s)) multiple = true;
            else if (RANGE_TOGGLE.equals(s)) rangeToggle = true;
        }
        if (single || multiple) return new Result(Status.PRESENT, single, multiple, rangeToggle, "");
        return new Result(Status.ABSENT, false, false, rangeToggle,
I18n.tr("vjf.noTrigger"));
    }
}
