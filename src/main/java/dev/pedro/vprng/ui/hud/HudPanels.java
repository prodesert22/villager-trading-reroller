package dev.pedro.vprng.ui.hud;

import dev.pedro.vprng.i18n.I18n;
import dev.pedro.vprng.bridge.VtpBridge;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.plan.RoundPlanner;
import dev.pedro.vprng.plan.SyncTracker;
import dev.pedro.vprng.roll.CellStatus;
import dev.pedro.vprng.roll.PoolController;
import dev.pedro.vprng.ui.Theme;
import dev.pedro.vprng.vjf.VjfSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The HUD is three small panels in VTP's look. Each one can be dragged anywhere in the HUD editor and hidden
 * individually. This class knows what each panel shows ({@link #rows}) and how to draw it ({@link #draw}).
 */
public enum HudPanels {
    POOL("pool", "hud.pool", Items.LECTERN),
    PREDICTION("prediction", "hud.prediction", Items.ENCHANTED_BOOK),
    ACTIVITY("activity", "hud.activity", Items.CLOCK);

    public static final int WIDTH = 158;
    public static final int MIN_WIDTH = 110;
    public static final int MAX_WIDTH = 420;
    public static final int MAX_HEIGHT = 320;
    private static final int PAD = 7;
    private static final int HEADER = 24;

    /** One line of a panel. */
    public sealed interface Row permits KeyValue, Chip, Bar, Note, ItemLine {}

    public record KeyValue(String label, String value, int color) implements Row {}

    public record Chip(String text, int color) implements Row {}

    public record Bar(float fraction, String text, int color) implements Row {}

    public record Note(String text, int color) implements Row {}

    /** An item icon with a line of text, e.g. the enchanted book the villager will have. */
    public record ItemLine(ItemStack icon, String text, int color, int price) implements Row {}

    public final String id;
    private final String titleKey;
    private final Item icon;

    /** The panel title in the current language. */
    public String title() {
        return I18n.tr(titleKey);
    }

    HudPanels(String id, String titleKey, Item icon) {
        this.id = id;
        this.titleKey = titleKey;
        this.icon = icon;
    }

    // ------------------------------------------------------------------ content

    public static String stateLabel(PoolController.State state) {
        return switch (state) {
            case IDLE -> I18n.tr("state.idle");
            case PLACE -> I18n.tr("state.place");
            case WAIT_CLAIM -> I18n.tr("state.waitClaim");
            case OPEN -> I18n.tr("state.open");
            case WAIT_OFFERS -> I18n.tr("state.waitOffers");
            case BREAK -> I18n.tr("state.break");
            case WAIT_RESET -> I18n.tr("state.waitReset");
            case COLLECT -> I18n.tr("state.collect");
            case PAUSED -> I18n.tr("state.paused");
            case FOUND -> I18n.tr("state.found");
        };
    }

    public static int stateColor(PoolController.State state) {
        return switch (state) {
            case IDLE -> Theme.MUTED;
            case PAUSED -> Theme.WARN;
            case FOUND -> Theme.accent();
            default -> Theme.INFO;
        };
    }

    /** @param sample true in the HUD editor when nothing is running: shows believable placeholder values. */
    public List<Row> rows(PoolController c, boolean sample) {
        Minecraft mc = Minecraft.getInstance();
        List<Row> rows = new ArrayList<>();
        switch (this) {
            case POOL -> {
                if (sample) {
                    rows.add(new Chip(I18n.tr("state.open"), Theme.INFO));
                    rows.add(new KeyValue(I18n.tr("hud.cells"), I18n.tr("hud.cellsOk", 4, 4), Theme.GOOD));
                    rows.add(new KeyValue(I18n.tr("card.cyclesOpens"), "5 · 24", Theme.TEXT));
                    rows.add(new KeyValue(I18n.tr("card.rate"), I18n.tr("hud.sampleRate"), Theme.TEXT));
                    rows.add(new KeyValue(I18n.tr("card.recovery"), I18n.tr("hud.chest"), Theme.TEXT));
                    return rows;
                }
                rows.add(new Chip(stateLabel(c.state()),
                        stateColor(c.state())));
                int ok = 0;
                for (var cell : c.cells()) {
                    if (CellStatus.ofCell(mc, cell).ok()) ok++;
                }
                int total = c.cells().size();
                rows.add(new KeyValue(I18n.tr("hud.cells"), I18n.tr("hud.cellsOk", ok, total), ok == total ? Theme.GOOD : Theme.BAD));
                rows.add(new KeyValue(I18n.tr("card.cyclesOpens"), c.cycles() + " · " + c.opens(), Theme.TEXT));
                rows.add(new KeyValue(I18n.tr("card.rate"), I18n.tr("hud.rateFmt", c.opensPerMinute()), Theme.TEXT));
                rows.add(new KeyValue(I18n.tr("card.recovery"), c.containerPos() != null ? I18n.tr("hud.chest") : I18n.tr("win.floor"), Theme.TEXT));
            }
            case PREDICTION -> {
                if (sample) {
                    rows.add(new Bar(0.62f, I18n.tr("win.rounds", 59), Theme.accent()));
                    rows.add(new ItemLine(new ItemStack(Items.ENCHANTED_BOOK), "Mending I", Theme.accent(), 11));
                    rows.add(new ItemLine(new ItemStack(Items.PAPER), I18n.tr("hud.samplePaper"), Theme.MUTED, -1));
                    rows.add(new KeyValue(I18n.tr("hud.eta"), "1:52", Theme.TEXT));
                    rows.add(new Chip(I18n.tr("win.inSync"), Theme.GOOD));
                    rows.add(new KeyValue(I18n.tr("hud.drifts"), "2", Theme.WARN));
                    return rows;
                }
                var hit = c.forecast().hit();
                if (hit.isEmpty() && c.isOutOfWindow()) {
                    rows.add(new Chip(I18n.tr("hud.outOfWindow"), Theme.WARN));
                    rows.add(new Note(I18n.tr("hud.burning"), Theme.MUTED));
                    rows.add(new Note(c.forecast().note(), Theme.MUTED));
                    return rows;
                }
                if (hit.isEmpty()) {
                    String why = VtpBridge.apiOk() ? c.forecast().note() : I18n.tr("hud.vtpApi", VtpBridge.apiProblem());
                    rows.add(new Chip(I18n.tr("win.noPrediction"), Theme.WARN));
                    rows.add(new Note(why, Theme.MUTED));
                    rows.add(new Note(I18n.tr("hud.verifying"), Theme.MUTED));
                    return rows;
                }
                int burn = c.roundsToBurn();
                int start = Math.max(c.roundsToBurnStart(), burn);
                float fraction = start <= 0 ? 1f : 1f - (float) burn / start;
                rows.add(new Bar(fraction, c.nextOpenIsKeeper() ? I18n.tr("hud.nextTarget") : I18n.tr("win.rounds", burn), Theme.accent()));
                // what the predicted round contains: the wanted enchantment first, then the other offers
                var offers = hit.get().offers();
                for (VtpBridge.Offer offer : offers) {
                    if (offer.match()) rows.add(new ItemLine(offer.icon(), offer.text(), Theme.accent(), offer.price()));
                }
                int others = 0;
                for (VtpBridge.Offer offer : offers) {
                    if (!offer.match() && others++ < 4) rows.add(new ItemLine(offer.icon(), offer.text(), Theme.MUTED, offer.price()));
                }
                double eta = c.etaSeconds();
                if (eta >= 0) {
                    long s = Math.round(eta);
                    rows.add(new KeyValue(I18n.tr("hud.eta"), String.format("%d:%02d", s / 60, s % 60), Theme.TEXT));
                }
                SyncTracker sync = c.sync();
                rows.add(new Chip(sync.synced() ? I18n.tr("win.inSync") : I18n.tr("win.unverified"), sync.synced() ? Theme.GOOD : Theme.WARN));
                if (sync.drifts() > 0) rows.add(new KeyValue(I18n.tr("hud.drifts"), String.valueOf(sync.drifts()), Theme.WARN));
                if (!c.calibrationNote().isEmpty()) {
                    rows.add(new Note(I18n.tr("hud.calibrating", c.calibrationNote()), Theme.WARN));
                } else if (c.calibrations() > 0) {
                    rows.add(new KeyValue(I18n.tr("hud.autoCalibrated"), c.calibrations() + "x", Theme.MUTED));
                }
            }
            case ACTIVITY -> {
                if (sample) {
                    rows.add(new KeyValue(I18n.tr("hud.last"), I18n.tr("offer.noBook"), Theme.TEXT));
                    rows.add(new KeyValue("Villager Job Fix", I18n.tr("hud.active"), Theme.GOOD));
                    rows.add(new Note(I18n.tr("match.describe", "Mending I", 11), Theme.accent()));
                    return rows;
                }
                if (!c.foundText().isEmpty()) rows.add(new Note(I18n.tr("st.found", c.foundText()), Theme.accent()));
                rows.add(new KeyValue(I18n.tr("hud.last"), c.lastSeen().isEmpty() ? "—" : c.lastSeen(), Theme.TEXT));
                if (VprngConfig.get().useVillagerJobFix) {
                    var vjf = VjfSupport.result();
                    rows.add(new KeyValue("Villager Job Fix", vjf.usable() ? I18n.tr("hud.active") : I18n.tr("hud.missing"),
                            vjf.usable() ? Theme.GOOD : Theme.WARN));
                }
                if (c.state() == PoolController.State.PAUSED || c.state() == PoolController.State.WAIT_CLAIM) {
                    rows.add(new Note(c.status(), Theme.MUTED));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ drawing

    private static int rowHeight(Row row) {
        return switch (row) {
            case KeyValue k -> 12;
            case Chip c -> 15;
            case Bar b -> 16;
            case Note n -> 12;
            case ItemLine i -> 18;
        };
    }

    public int height(List<Row> rows) {
        int h = HEADER + 4;
        for (Row row : rows) h += rowHeight(row);
        return h + 4;
    }

    /** Draws the panel at (x, y) and returns its height. */
    public int draw(GuiGraphicsExtractor g, Font font, int x, int y, List<Row> rows, float opacity) {
        return draw(g, font, x, y, WIDTH, height(rows), rows, opacity);
    }

    /**
     * Draws the panel at (x, y) with the given size and returns the height used. A width below
     * {@link #MIN_WIDTH} or a height below the natural content height is raised to fit; extra height is spread
     * evenly between the rows so a taller panel breathes instead of just leaving a gap at the bottom.
     */
    public int draw(GuiGraphicsExtractor g, Font font, int x, int y, int width, int height, List<Row> rows, float opacity) {
        width = Math.max(MIN_WIDTH, width);
        int natural = height(rows);
        int h = Math.max(natural, height);
        int extraPerRow = rows.isEmpty() ? 0 : (h - natural) / rows.size();
        int accent = Theme.accent();
        Theme.panel(g, x, y, width, h, 0.78f * opacity);
        g.item(new ItemStack(icon), x + PAD - 1, y + 4);
        g.text(font, title(), x + PAD + 20, y + 9, Theme.alpha(accent, opacity));
        // accent underline under the header, fading out to the right like a Jade tooltip divider
        for (int i = 0; i < width - 2 * PAD; i++) {
            float fade = 1f - (float) i / (width - 2 * PAD);
            g.fill(x + PAD + i, y + HEADER - 1, x + PAD + i + 1, y + HEADER, Theme.alpha(accent, (0.15f + 0.6f * fade) * opacity));
        }
        int cy = y + HEADER + 4;
        int inner = width - 2 * PAD;
        for (Row row : rows) {
            switch (row) {
                case KeyValue k -> {
                    g.text(font, Theme.fit(font, k.label(), inner / 2), x + PAD, cy + 1, Theme.alpha(Theme.MUTED, opacity));
                    String value = Theme.fit(font, k.value(), inner - font.width(k.label()) - 6);
                    g.text(font, value, x + width - PAD - font.width(value), cy + 1, Theme.alpha(k.color(), opacity));
                }
                case Chip chip -> Theme.chip(g, font, x + PAD, cy + 1, Theme.fit(font, chip.text(), inner - 16), chip.color(), opacity);
                case Bar bar -> {
                    Theme.bar(g, x + PAD, cy + 2, inner, 7, bar.fraction(), bar.color(), opacity);
                    String text = Theme.fit(font, bar.text(), inner - 4);
                    g.text(font, text, x + PAD + (inner - font.width(text)) / 2, cy + 2, Theme.alpha(Theme.TEXT, opacity));
                }
                case Note note -> g.text(font, Theme.fit(font, note.text(), inner), x + PAD, cy + 1,
                        Theme.alpha(note.color(), opacity));
                case ItemLine line -> {
                    if (!line.icon().isEmpty()) g.item(line.icon(), x + PAD, cy);
                    int tagWidth = line.price() >= 0
                            ? Theme.price(g, font, x + width - PAD + 2, cy, line.price(), line.color(), opacity) + 4 : 0;
                    g.text(font, Theme.fit(font, line.text(), inner - 22 - tagWidth), x + PAD + 21, cy + 5,
                            Theme.alpha(line.color(), opacity));
                }
            }
            cy += rowHeight(row) + extraPerRow;
        }
        return h;
    }
}
