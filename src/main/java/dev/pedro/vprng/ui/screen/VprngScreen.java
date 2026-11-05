package dev.pedro.vprng.ui.screen;

import dev.pedro.vprng.ui.VprngKeys;
import dev.pedro.vprng.i18n.I18n;
import dev.pedro.vprng.ui.VprngCommands;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.bridge.VtpBridge;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.Cell;
import dev.pedro.vprng.roll.CellStatus;
import dev.pedro.vprng.roll.PoolController;
import dev.pedro.vprng.ui.Highlighter;
import dev.pedro.vprng.ui.PoolActions;
import dev.pedro.vprng.ui.Theme;
import dev.pedro.vprng.ui.hud.HudPanels;
import dev.pedro.vprng.vjf.VjfSupport;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Main window with the same options as the {@code /vprng} commands, in VTP's dark panel style.
 * Drawn immediately each frame (no widget objects); every clickable rectangle is registered in a {@link HitMap}.
 * Actions that read the crosshair close the window first and reopen it afterwards.
 */
public final class VprngScreen extends Screen {
    public enum Tab {
        CONTROL("tab.control"), BLOCKS("tab.blocks"), SETTINGS("tab.settings");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        String label() {
            return I18n.tr(key);
        }
    }

    private static final int MAX_W = 400;
    private static final int MAX_H = 276;
    private static final int MIN_W = 300;
    private static final int MIN_H = 200;
    private static final int GRAB = 5;

    /** Which edge of the window is being dragged: 1 = right, 2 = bottom, 3 = both (corner), 0 = none. */
    private int resizing;

    private final HitMap hits = new HitMap();
    private Tab tab;
    private int scroll;
    private int contentHeight;
    private String flash = "";
    private long flashUntil;
    private long clearConfirmUntil;

    public VprngScreen(Tab tab) {
        super(Component.literal("Villager Trading Reroller"));
        this.tab = tab;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x88000000);
    }

    private void say(String message) {
        flash = message;
        flashUntil = System.currentTimeMillis() + 4500;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        hits.clear();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int accent = Theme.accent();

        int w = windowW();
        int h = windowH();
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;
        Theme.panel(g, x0, y0, w, h, 0.97f);
        drawResizeGrips(g, mouseX, mouseY, x0, y0, w, h, accent);

        // header
        g.item(new ItemStack(Items.LECTERN), x0 + 8, y0 + 6);
        g.text(font, "Villager Trading Reroller", x0 + 30, y0 + 8, accent);
        g.text(font, I18n.tr("win.companion"), x0 + 30, y0 + 18, Theme.MUTED);
        button(g, mouseX, mouseY, x0 + w - 22, y0 + 7, 14, 14, "×", true, false, this::onClose, I18n.tr("win.close"));
        for (int i = 0; i < w - 16; i++) {
            float fade = 1f - (float) i / (w - 16);
            g.fill(x0 + 8 + i, y0 + 30, x0 + 9 + i, y0 + 31, Theme.alpha(accent, 0.15f + 0.6f * fade));
        }

        // tabs
        int tx = x0 + 8;
        for (Tab t : Tab.values()) {
            int tw = font.width(t.label()) + 18;
            boolean active = t == tab;
            boolean hover = hits.hovered(tx, y0 + 34, tw, 16, mouseX, mouseY);
            g.fill(tx, y0 + 34, tx + tw, y0 + 50, active ? 0xFF1C1C1C : hover ? 0xFF181818 : 0xFF101010);
            g.text(font, t.label(), tx + 9, y0 + 39, active ? accent : hover ? Theme.TEXT : Theme.MUTED);
            if (active) g.fill(tx, y0 + 49, tx + tw, y0 + 50, accent);
            final Tab target = t;
            hits.add(tx, y0 + 34, tw, 16, () -> {
                tab = target;
                scroll = 0;
                languageOpen = false;
            }, null);
            tx += tw + 2;
        }

        // content (scrollable, clipped)
        int cx = x0 + 8;
        int cy = y0 + 54;
        int cw = w - 16;
        int ch = h - 54 - 24;
        Theme.slot(g, cx - 1, cy - 1, cw + 2, ch + 2);
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        // While the dropdown is open it is modal: the rows behind it must not react to the cursor (no hover).
        realMouseX = mouseX;
        realMouseY = mouseY;
        int contentMouseX = languageOpen && tab == Tab.SETTINGS ? -10000 : mouseX;
        int contentMouseY = languageOpen && tab == Tab.SETTINGS ? -10000 : mouseY;
        int used = switch (tab) {
            case CONTROL -> drawControl(g, mc, font, contentMouseX, contentMouseY, cx + 4, cy + 4 - scroll, cw - 8, cy, cy + ch);
            case BLOCKS -> drawBlocks(g, mc, font, contentMouseX, contentMouseY, cx + 4, cy + 4 - scroll, cw - 8, cy, cy + ch);
            case SETTINGS -> drawSettings(g, mc, font, contentMouseX, contentMouseY, cx + 4, cy + 4 - scroll, cw - 8, cy, cy + ch);
        };
        g.disableScissor();
        contentHeight = used + scroll + 8 - (cy + 4);
        int maxScroll = Math.max(0, contentHeight - ch);
        scroll = Math.clamp(scroll, 0, maxScroll);
        if (maxScroll > 0) { // scrollbar
            int barH = Math.max(14, ch * ch / contentHeight);
            int barY = cy + (int) ((long) (ch - barH) * scroll / maxScroll);
            g.fill(cx + cw - 3, cy, cx + cw - 1, cy + ch, 0xFF0C0C0C);
            g.fill(cx + cw - 3, barY, cx + cw - 1, barY + barH, Theme.alpha(accent, 0.8f));
        }

        // footer: transient message or the run state
        PoolController c = VprngClient.controller();
        String foot = System.currentTimeMillis() < flashUntil ? flash
                : I18n.tr("win.footer", VprngKeys.guiKeyName(), VprngKeys.toggleKeyName());
        g.text(font, Theme.fit(font, foot, w - 16), x0 + 8, y0 + h - 15,
                System.currentTimeMillis() < flashUntil ? Theme.TEXT : Theme.MUTED);

        hits.commit();
        String tip = hits.tooltipAt(mouseX, mouseY);
        if (tip != null) g.setTooltipForNextFrame(Component.literal(tip), mouseX, mouseY);
    }

    // ------------------------------------------------------------------ resizing

    private int windowW() {
        int wanted = VprngConfig.get().windowW > 0 ? VprngConfig.get().windowW : MAX_W;
        return Math.max(Math.min(MIN_W, width - 16), Math.min(width - 16, wanted));
    }

    private int windowH() {
        int wanted = VprngConfig.get().windowH > 0 ? VprngConfig.get().windowH : MAX_H;
        return Math.max(Math.min(MIN_H, height - 16), Math.min(height - 16, wanted));
    }

    /** Edge under the cursor: 1 right, 2 bottom, 3 corner, 0 none. */
    private int edgeAt(double mx, double my) {
        int w = windowW();
        int h = windowH();
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;
        boolean inY = my >= y0 && my <= y0 + h + GRAB;
        boolean inX = mx >= x0 && mx <= x0 + w + GRAB;
        boolean right = inY && mx >= x0 + w - GRAB && mx <= x0 + w + GRAB;
        boolean bottom = inX && my >= y0 + h - GRAB && my <= y0 + h + GRAB;
        if (right && bottom) return 3;
        if (right && my >= y0 + h - 12) return 3;
        if (bottom && mx >= x0 + w - 12) return 3;
        return right ? 1 : bottom ? 2 : 0;
    }

    private void drawResizeGrips(GuiGraphicsExtractor g, int mx, int my, int x0, int y0, int w, int h, int accent) {
        int active = resizing != 0 ? resizing : edgeAt(mx, my);
        int hot = Theme.alpha(accent, 0.95f);
        int idle = Theme.alpha(accent, 0.25f);
        g.fill(x0 + w - 1, y0 + 34, x0 + w + 1, y0 + h - 12, active == 1 ? hot : idle);
        g.fill(x0 + 34, y0 + h - 1, x0 + w - 12, y0 + h + 1, active == 2 ? hot : idle);
        for (int i = 0; i < 3; i++) { // corner ticks
            int o = 3 + i * 3;
            int color = active == 3 ? hot : idle;
            g.fill(x0 + w - o, y0 + h - 2, x0 + w - o + 1, y0 + h - 1, color);
            g.fill(x0 + w - 2, y0 + h - o, x0 + w - 1, y0 + h - o + 1, color);
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (resizing == 0) return super.mouseDragged(event, dx, dy);
        VprngConfig cfg = VprngConfig.get();
        // the window is centred, so the dragged edge follows the cursor when the size changes by twice the distance
        if ((resizing & 1) != 0) cfg.windowW = Math.clamp((int) Math.round(Math.abs(event.x() - width / 2.0) * 2), MIN_W, 1600);
        if ((resizing & 2) != 0) cfg.windowH = Math.clamp((int) Math.round(Math.abs(event.y() - height / 2.0) * 2), MIN_H, 1200);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (resizing != 0) {
            resizing = 0;
            VprngConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    // ------------------------------------------------------------------ helpers

    private boolean visible(int y, int h, int top, int bottom) {
        return y + h > top && y < bottom;
    }

    /** Draws a button and registers it if enabled; returns whether the cursor is over it. */
    private boolean button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String label,
                           boolean enabled, boolean primary, Runnable action, String tooltip) {
        boolean hover = hits.hovered(x, y, w, h, mx, my);
        Theme.button(g, Minecraft.getInstance().font, x, y, w, h, label, hover, enabled, primary);
        if (enabled) hits.add(x, y, w, h, action, tooltip);
        return hover;
    }

    /** Button that is only clickable (and drawn) while inside the visible content band. */
    private void rowButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String label,
                           boolean enabled, boolean primary, Runnable action, String tip, int top, int bottom) {
        if (!visible(y, h, top, bottom)) return;
        boolean inside = y >= top && y + h <= bottom;
        button(g, mx, my, x, y, w, h, label, enabled && inside, primary, action, tip);
    }

    private void toggleRow(GuiGraphicsExtractor g, int mx, int my, Font font, int x, int y, int w, String label,
                           String hint, boolean value, Runnable flip, int top, int bottom) {
        if (!visible(y, 18, top, bottom)) return;
        boolean hover = hits.hovered(x, y, w, 16, mx, my);
        if (hover) g.fill(x, y, x + w, y + 16, 0x22FFFFFF);
        g.text(font, label, x + 4, y + 4, Theme.TEXT);
        Theme.toggle(g, x + w - 28, y + 2, value, hover);
        if (y >= top && y + 16 <= bottom) hits.add(x, y, w, 16, flip, hint);
    }

    private static void flipConfig(Runnable change) {
        change.run();
        VprngConfig.save();
    }

    // ------------------------------------------------------------------ tab: control

    private int drawControl(GuiGraphicsExtractor g, Minecraft mc, Font font, int mx, int my, int x, int y, int w,
                            int top, int bottom) {
        PoolController c = VprngClient.controller();
        PoolController.State s = c.state();
        boolean idle = s == PoolController.State.IDLE;
        boolean paused = s == PoolController.State.PAUSED;
        boolean found = s == PoolController.State.FOUND;
        int bw = (w - 18) / 4;

        Theme.chip(g, font, x, y, HudPanels.stateLabel(s), HudPanels.stateColor(s), 1f);
        g.text(font, Theme.fit(font, c.status(), w - 150), x + font.width(HudPanels.stateLabel(s)) + 24, y + 2, Theme.MUTED);
        y += 18;

        rowButton(g, mx, my, x, y, bw, 20, I18n.tr("btn.start"), idle || found, true, () -> {
            mc.gui.setScreen(null);
            VprngClient.later(2, () -> VprngClient.chat(mc, c.start(mc)));
        }, I18n.tr("tip.start"), top, bottom);
        rowButton(g, mx, my, x + (bw + 6), y, bw, 20, I18n.tr("btn.pause"), c.running(), false,
                () -> c.pause(mc, I18n.tr("pause.byWindow")), null, top, bottom);
        rowButton(g, mx, my, x + 2 * (bw + 6), y, bw, 20, I18n.tr("btn.resume"), paused, true, () -> {
            mc.gui.setScreen(null);
            VprngClient.later(2, () -> VprngClient.chat(mc, c.resume(mc)));
        }, null, top, bottom);
        rowButton(g, mx, my, x + 3 * (bw + 6), y, bw, 20, I18n.tr("btn.stop"), !idle, false,
                () -> c.stop(mc, I18n.tr("ctl.stopped")), null, top, bottom);
        y += 28;

        // stat cards, two columns
        int half = (w - 6) / 2;
        int ok = 0;
        for (Cell cell : c.cells()) {
            if (CellStatus.ofCell(mc, cell).ok()) ok++;
        }
        int total = c.cells().size();
        String prediction = c.forecast().hit().isPresent()
                ? I18n.tr("win.rounds", c.roundsToBurn()) + (c.etaSeconds() >= 0 ? " · " + eta(c.etaSeconds()) : "")
                : I18n.tr("win.noPrediction");
        String vtp = I18n.tr("win.vtpSummary", VtpBridge.apiOk() ? I18n.tr("win.apiOk") : I18n.tr("win.apiBad"),
                VtpBridge.seed().isPresent() ? I18n.tr("win.seedOk") : I18n.tr("win.seedMissing"), VtpBridge.targets().size());
        Object[][] cards = {
                {I18n.tr("tab.blocks"), I18n.tr("win.valid", ok, total), total == 0 ? Theme.MUTED : ok == total ? Theme.GOOD : Theme.BAD},
                {I18n.tr("card.recovery"), c.containerPos() != null ? I18n.tr("win.chestAt", c.containerPos().toShortString()) : I18n.tr("win.floor"), Theme.TEXT},
                {I18n.tr("card.cyclesOpens"), c.cycles() + " · " + c.opens(), Theme.TEXT},
                {I18n.tr("card.rate"), I18n.tr("win.rate", c.opensPerMinute()), Theme.TEXT},
                {I18n.tr("card.prediction"), prediction, c.forecast().hit().isPresent() ? Theme.accent() : Theme.WARN},
                {I18n.tr("card.sync"), c.sync().synced() ? I18n.tr("win.inSync") : I18n.tr("win.unverified")
                        + (c.sync().drifts() > 0 ? I18n.tr("win.driftsSuffix", c.sync().drifts()) : ""),
                        c.sync().synced() ? Theme.GOOD : Theme.WARN},
                {"VTP", vtp, VtpBridge.apiOk() ? Theme.TEXT : Theme.BAD},
                {"Villager Job Fix", VprngConfig.get().useVillagerJobFix ? VjfSupport.result().describe() : I18n.tr("win.off"),
                        VjfSupport.result().usable() ? Theme.GOOD : Theme.MUTED}
        };
        for (int i = 0; i < cards.length; i++) {
            int cxp = x + (i % 2) * (half + 6);
            int cyp = y + (i / 2) * 32;
            if (!visible(cyp, 28, top, bottom)) continue;
            Theme.slot(g, cxp, cyp, half, 28);
            g.text(font, (String) cards[i][0], cxp + 6, cyp + 4, Theme.MUTED);
            g.text(font, Theme.fit(font, (String) cards[i][1], half - 12), cxp + 6, cyp + 15, (Integer) cards[i][2]);
        }
        y += 4 * 32 + 4;
        var predicted = c.forecast().hit();
        if (predicted.isPresent() && !predicted.get().offers().isEmpty()) {
            var offers = predicted.get().offers();
            int boxH = 18 + offers.size() * 18;
            if (visible(y, boxH, top, bottom)) {
                Theme.slot(g, x, y, w, boxH);
                g.text(font, I18n.tr("win.predictedRound", c.roundsToBurn()), x + 6, y + 5, Theme.MUTED);
                int oy = y + 16;
                for (var offer : offers) {
                    if (!offer.icon().isEmpty()) g.item(offer.icon(), x + 6, oy);
                    int color = offer.match() ? Theme.accent() : Theme.TEXT;
                    int tagWidth = offer.price() >= 0 ? Theme.price(g, font, x + w - 4, oy, offer.price(), color, 1f) + 4 : 0;
                    g.text(font, Theme.fit(font, offer.text(), w - 40 - tagWidth), x + 28, oy + 5, color);
                    if (offer.match()) g.fill(x + 1, oy + 1, x + 3, oy + 15, Theme.accent());
                    oy += 18;
                }
            }
            y += boxH + 4;
        }
        if (!c.foundText().isEmpty()) {
            if (visible(y, 20, top, bottom)) {
                Theme.slot(g, x, y, w, 20);
                g.text(font, I18n.tr("st.found", c.foundText()), x + 6, y + 6, Theme.accent());
            }
            y += 24;
        }
        return y;
    }

    private static String eta(double seconds) {
        long s = Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ tab: blocks

    private int drawBlocks(GuiGraphicsExtractor g, Minecraft mc, Font font, int mx, int my, int x, int y, int w,
                           int top, int bottom) {
        PoolController c = VprngClient.controller();
        VprngConfig cfg = VprngConfig.get();
        List<Cell> cells = c.cells();
        int bw = (w - 12) / 3;

        toggleRow(g, mx, my, font, x, y, w, I18n.tr("win.hlAll"),
                I18n.tr("win.hlAllTip"), cfg.showHighlights,
                () -> flipConfig(() -> cfg.showHighlights = !cfg.showHighlights), top, bottom);
        y += 20;

        rowButton(g, mx, my, x, y, bw, 18, I18n.tr("win.btn.looked"), true, true,
                () -> VprngClient.withCrosshair(PoolActions::addLookedAt, Tab.BLOCKS),
                I18n.tr("win.tip.looked"), top, bottom);
        rowButton(g, mx, my, x + bw + 6, y, bw, 18, I18n.tr("win.btn.scan"), true, false, () -> {
            say(PoolActions.scan(mc));
        }, I18n.tr("win.tip.scan"), top, bottom);
        boolean confirming = System.currentTimeMillis() < clearConfirmUntil;
        rowButton(g, mx, my, x + 2 * (bw + 6), y, bw, 18, confirming ? I18n.tr("win.btn.confirm") : I18n.tr("win.btn.clearAll"), !cells.isEmpty(), false,
                () -> {
                    if (System.currentTimeMillis() < clearConfirmUntil) {
                        c.clearPool(mc);
                        clearConfirmUntil = 0;
                        say(I18n.tr("cmd.poolCleared"));
                    } else {
                        clearConfirmUntil = System.currentTimeMillis() + 3000;
                    }
                }, I18n.tr("win.tip.clearAll"), top, bottom);
        y += 24;

        // container card
        BlockPos container = c.containerPos();
        if (visible(y, 56, top, bottom)) {
            Theme.slot(g, x, y, w, 56);
            Item item = Items.CHEST;
            if (container != null && mc.level != null) {
                Item real = mc.level.getBlockState(container).getBlock().asItem();
                if (real != Items.AIR) item = real;
            }
            g.item(new ItemStack(item), x + 6, y + 4);
            g.text(font, I18n.tr("win.container.title"), x + 28, y + 5, Theme.TEXT);
            if (container == null) {
                g.text(font, I18n.tr("win.container.notSet"), x + 28, y + 17, Theme.MUTED);
            } else {
                CellStatus st = CellStatus.ofContainer(mc, container);
                int cw = Theme.chip(g, font, x + 28, y + 16, st.ok() ? I18n.tr("win.ok") : I18n.tr("win.invalid"), st.ok() ? Theme.GOOD : Theme.BAD, 1f);
                g.text(font, Theme.fit(font, container.toShortString() + " · " + st.reason(), w - 28 - cw - 14),
                        x + 28 + cw + 6, y + 18, Theme.MUTED);
            }
            int by = y + 36; // own row below the info line so the buttons never cover the text
            if (container != null) {
                boolean lit = cfg.isHighlighted(container);
                rowButton(g, mx, my, x + w - 238, by, 86, 16, lit ? I18n.tr("win.hlOn") : I18n.tr("win.hlOff"), true, lit,
                        () -> {
                            cfg.setHighlighted(container, !cfg.isHighlighted(container));
                            VprngConfig.save();
                        }, I18n.tr("win.tip.hlContainer"), top, bottom);
            }
            rowButton(g, mx, my, x + w - 148, by, 56, 16, I18n.tr("win.btn.set"), true, true,
                    () -> VprngClient.withCrosshair(PoolActions::setContainerLookedAt, Tab.BLOCKS),
                    I18n.tr("win.tip.setContainer"), top, bottom);
            rowButton(g, mx, my, x + w - 88, by, 38, 16, I18n.tr("win.btn.view"), container != null, false,
                    () -> {
                        Highlighter.focus(container, I18n.tr("win.label.container"));
                        mc.gui.setScreen(null);
                    }, I18n.tr("win.tip.viewContainer"), top, bottom);
            rowButton(g, mx, my, x + w - 46, by, 42, 16, I18n.tr("win.btn.remove"), container != null, false, () -> {
                c.setContainer(mc, null);
                say(I18n.tr("win.containerRemoved"));
            }, null, top, bottom);
        }
        y += 62;

        g.text(font, I18n.tr("win.workstations", cells.size()), x + 2, y + 2, Theme.accent());
        y += 14;
        if (cells.isEmpty()) {
            g.text(font, I18n.tr("win.noBlocks"), x + 2, y + 2, Theme.MUTED);
            g.text(font, I18n.tr("win.noBlocksHint"), x + 2, y + 14, Theme.MUTED);
            return y + 30;
        }
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            int index = i + 1;
            if (visible(y, 30, top, bottom)) {
                CellStatus st = CellStatus.ofCell(mc, cell);
                Theme.slot(g, x, y, w, 30);
                g.fill(x + 1, y + 1, x + 3, y + 29, st.ok() ? Theme.GOOD : Theme.BAD);
                g.item(new ItemStack(Items.LECTERN), x + 8, y + 7);
                BlockPos p = cell.pos();
                g.text(font, "#" + index + "   " + p.getX() + ", " + p.getY() + ", " + p.getZ(), x + 30, y + 5, Theme.TEXT);
                int cw = Theme.chip(g, font, x + 30, y + 16, st.ok() ? I18n.tr("win.ok") : I18n.tr("win.invalid"), st.ok() ? Theme.GOOD : Theme.BAD, 1f);
                g.text(font, Theme.fit(font, st.reason(), w - 30 - cw - 120), x + 36 + cw, y + 18, Theme.MUTED);
                final int idx = index;
                boolean lit = cfg.isHighlighted(p);
                rowButton(g, mx, my, x + w - 190, y + 7, 86, 16, lit ? I18n.tr("win.hlOn") : I18n.tr("win.hlOff"), true, lit,
                        () -> {
                            cfg.setHighlighted(p, !cfg.isHighlighted(p));
                            VprngConfig.save();
                        }, I18n.tr("win.tip.hlBlock"), top, bottom);
                rowButton(g, mx, my, x + w - 100, y + 7, 40, 16, I18n.tr("win.btn.view"), true, false, () -> {
                    Highlighter.focus(p, I18n.tr("win.blockN", idx));
                    mc.gui.setScreen(null);
                }, I18n.tr("win.tip.viewBlock"), top, bottom);
                rowButton(g, mx, my, x + w - 56, y + 7, 52, 16, I18n.tr("win.btn.delete"), true, false, () -> {
                    c.removeCell(mc, p);
                    say(I18n.tr("win.blockRemoved", p.toShortString()));
                }, I18n.tr("win.tip.deleteBlock"), top, bottom);
            }
            y += 34;
        }
        return y;
    }

    // ------------------------------------------------------------------ tab: settings

    private int drawSettings(GuiGraphicsExtractor g, Minecraft mc, Font font, int mx, int my, int x, int y, int w,
                             int top, int bottom) {
        VprngConfig cfg = VprngConfig.get();

        g.text(font, I18n.tr("win.sec.language"), x + 2, y + 2, Theme.accent());
        y += 14;
        if (visible(y, 18, top, bottom)) g.text(font, I18n.tr("win.language.label"), x + 4, y + 5, Theme.TEXT);
        drawLanguageBox(g, font, realMouseX, realMouseY, x + w - LANGUAGE_BOX_W - 4, y, top, bottom);
        y += 26;

        g.text(font, "Villager Job Fix", x + 2, y + 2, Theme.accent());
        y += 14;
        toggleRow(g, mx, my, font, x, y, w, I18n.tr("win.vjf.use"),
                I18n.tr("win.vjf.useTip"), cfg.useVillagerJobFix,
                () -> {
                    flipConfig(() -> cfg.useVillagerJobFix = !cfg.useVillagerJobFix);
                    VjfSupport.invalidate();
                    if (cfg.useVillagerJobFix) VjfSupport.check(mc, r -> say(I18n.tr("win.vjf.say", r.describe())));
                }, top, bottom);
        y += 18;
        if (visible(y, 18, top, bottom)) {
            g.text(font, I18n.tr("win.mode", cfg.vjfMode), x + 4, y + 5, Theme.TEXT);
            String line = VjfSupport.result().describe();
            g.text(font, Theme.fit(font, line, w - 190), x + 90, y + 5,
                    VjfSupport.result().usable() ? Theme.GOOD : Theme.MUTED);
        }
        rowButton(g, mx, my, x + w - 96, y, 48, 16, I18n.tr("win.btn.change"), true, false, () -> flipConfig(() -> {
            VprngConfig.VjfMode[] modes = VprngConfig.VjfMode.values();
            cfg.vjfMode = modes[(cfg.vjfMode.ordinal() + 1) % modes.length];
        }), I18n.tr("win.tip.mode"), top, bottom);
        rowButton(g, mx, my, x + w - 44, y, 44, 16, I18n.tr("win.btn.check"), true, false,
                () -> VjfSupport.check(mc, r -> say(I18n.tr("win.vjf.say", r.describe()))), I18n.tr("win.tip.check"), top, bottom);
        y += 20;
        int tb = (w - 6) / 2;
        rowButton(g, mx, my, x, y, tb, 16, I18n.tr("win.btn.sendSingle"), true, false, () -> {
            VjfSupport.trigger(mc, false);
            say(I18n.tr("cmd.vjfSent", "vjf_single"));
        }, I18n.tr("win.tip.testOnce"), top, bottom);
        rowButton(g, mx, my, x + tb + 6, y, tb, 16, I18n.tr("win.btn.sendMultiple"), true, false, () -> {
            VjfSupport.trigger(mc, true);
            say(I18n.tr("cmd.vjfSent", "vjf_multiple"));
        }, I18n.tr("win.tip.testOnce"), top, bottom);
        y += 26;

        g.text(font, I18n.tr("win.sec.behaviour"), x + 2, y + 2, Theme.accent());
        y += 14;
        Object[][] toggles = {
                {I18n.tr("win.opt.calibrate"), I18n.tr("win.opt.calibrateTip"), cfg.autoCalibrate,
                        (Runnable) () -> flipConfig(() -> cfg.autoCalibrate = !cfg.autoCalibrate)},
                {I18n.tr("win.opt.collect"), I18n.tr("win.opt.collectTip"), cfg.autoCollect,
                        (Runnable) () -> flipConfig(() -> cfg.autoCollect = !cfg.autoCollect)},
                {I18n.tr("win.opt.sneak"), I18n.tr("win.opt.sneakTip"), cfg.sneakWhenPlacing,
                        (Runnable) () -> flipConfig(() -> cfg.sneakWhenPlacing = !cfg.sneakWhenPlacing)},
                {I18n.tr("win.opt.unfocused"), I18n.tr("win.opt.unfocusedTip"), cfg.runWhenUnfocused,
                        (Runnable) () -> flipConfig(() -> cfg.runWhenUnfocused = !cfg.runWhenUnfocused)},
                {I18n.tr("win.opt.damage"), null, cfg.stopOnDamage,
                        (Runnable) () -> flipConfig(() -> cfg.stopOnDamage = !cfg.stopOnDamage)},
                {I18n.tr("win.opt.sound"), null, cfg.foundSound,
                        (Runnable) () -> flipConfig(() -> cfg.foundSound = !cfg.foundSound)},
                {I18n.tr("win.opt.chatLogs"), I18n.tr("win.opt.chatLogsTip"), cfg.chatLogs,
                        (Runnable) () -> flipConfig(() -> cfg.chatLogs = !cfg.chatLogs)},
        };
        for (Object[] t : toggles) {
            toggleRow(g, mx, my, font, x, y, w, (String) t[0], (String) t[1], (Boolean) t[2], (Runnable) t[3], top, bottom);
            y += 18;
        }
        if (visible(y, 18, top, bottom)) g.text(font, I18n.tr("win.dayGuard", cfg.dayGuard), x + 4, y + 5, Theme.TEXT);
        rowButton(g, mx, my, x + w - 48, y, 44, 16, I18n.tr("win.btn.change"), true, false, () -> flipConfig(() -> {
            VprngConfig.DayGuard[] guards = VprngConfig.DayGuard.values();
            cfg.dayGuard = guards[(cfg.dayGuard.ordinal() + 1) % guards.length];
        }), I18n.tr("win.tip.dayGuard"), top, bottom);
        y += 26;

        g.text(font, I18n.tr("win.sec.visuals"), x + 2, y + 2, Theme.accent());
        y += 14;
        toggleRow(g, mx, my, font, x, y, w, I18n.tr("win.opt.highlight"),
                I18n.tr("win.opt.highlightTip"), cfg.showHighlights,
                () -> flipConfig(() -> cfg.showHighlights = !cfg.showHighlights), top, bottom);
        y += 18;
        toggleRow(g, mx, my, font, x, y, w, I18n.tr("win.opt.hud"), null, cfg.hudEnabled,
                () -> flipConfig(() -> cfg.hudEnabled = !cfg.hudEnabled), top, bottom);
        y += 22;
        rowButton(g, mx, my, x, y, tb, 18, I18n.tr("win.btn.editHud"), true, true,
                () -> mc.gui.setScreen(new HudEditScreen(this)), I18n.tr("win.tip.editHud"), top, bottom);
        rowButton(g, mx, my, x + tb + 6, y, tb, 18, I18n.tr("win.btn.reload"), true, false, () -> {
            VprngConfig.load();
            say(I18n.tr("cmd.reloaded"));
        }, I18n.tr("win.tip.reload"), top, bottom);
        y += 26;
        if (languageOpen) drawLanguagePopup(g, font, realMouseX, realMouseY, x - 4, w + 8, top, bottom);
        return y;
    }

    // ------------------------------------------------------------------ language dropdown

    private static final int LANGUAGE_BOX_W = 150;
    private static final String[] LANGUAGE_CODES = {I18n.AUTO, "en", "pt", "es"};
    private boolean languageOpen;
    private int realMouseX;
    private int realMouseY;
    private int languageBoxX;
    private int languageBoxY;

    /** The closed dropdown: current choice plus a small arrow; click toggles the list. */
    private void drawLanguageBox(GuiGraphicsExtractor g, Font font, int mx, int my, int bx, int by, int top, int bottom) {
        languageBoxX = bx;
        languageBoxY = by;
        if (!visible(by, 16, top, bottom)) return;
        boolean hover = hits.hovered(bx, by, LANGUAGE_BOX_W, 16, mx, my);
        String name = Theme.fit(font, VprngCommands.languageName(VprngConfig.get().language), LANGUAGE_BOX_W - 24);
        Theme.button(g, font, bx, by, LANGUAGE_BOX_W, 16, "", hover, true, languageOpen);
        g.text(font, name, bx + 6, by + 5, languageOpen ? Theme.accent() : Theme.TEXT);
        int arrowX = bx + LANGUAGE_BOX_W - 12;
        int arrowY = by + (languageOpen ? 9 : 6);
        for (int i = 0; i < 4; i++) { // tiny triangle: pointing down when closed, up when open
            int row = languageOpen ? arrowY - i : arrowY + i;
            g.fill(arrowX + i, row, arrowX + 8 - i, row + 1, languageOpen ? Theme.accent() : Theme.MUTED);
        }
        if (by >= top && by + 16 <= bottom) {
            hits.add(bx, by, LANGUAGE_BOX_W, 16, () -> languageOpen = !languageOpen, I18n.tr("win.tip.language"));
        }
    }

    /**
     * The open list, drawn last so it sits above the other rows. A transparent region covering the content is
     * registered first (so clicking anywhere else just closes the list), then one hit per option on top of it.
     */
    private void drawLanguagePopup(GuiGraphicsExtractor g, Font font, int mx, int my, int cx, int cw, int top, int bottom) {
        hits.add(cx, top, cw, bottom - top, () -> languageOpen = false, null);
        int rowH = 17;
        int px = languageBoxX;
        int py = languageBoxY + 18;
        int ph = LANGUAGE_CODES.length * rowH + 4;
        Theme.panel(g, px - 1, py, LANGUAGE_BOX_W + 2, ph, 1f);
        VprngConfig cfg = VprngConfig.get();
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            String code = LANGUAGE_CODES[i];
            int ry = py + 2 + i * rowH;
            boolean selected = code.equals(cfg.language);
            boolean hover = hits.hovered(px + 1, ry, LANGUAGE_BOX_W - 2, rowH, mx, my);
            if (hover) g.fill(px + 1, ry, px + LANGUAGE_BOX_W - 1, ry + rowH, 0x33FFFFFF);
            if (selected) g.fill(px + 1, ry + 1, px + 3, ry + rowH - 1, Theme.accent());
            g.text(font, Theme.fit(font, VprngCommands.languageName(code), LANGUAGE_BOX_W - 16), px + 8, ry + 5,
                    selected ? Theme.accent() : Theme.TEXT);
            hits.add(px + 1, ry, LANGUAGE_BOX_W - 2, rowH, () -> {
                cfg.language = code;
                VprngConfig.save();
                languageOpen = false;
                say(I18n.tr("cmd.language", VprngCommands.languageName(code)));
            }, null);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        dev.pedro.vprng.VprngClient.LOGGER.debug("[VPRNG gui] mouseClicked button={} at {},{}", event.button(),
                (int) event.x(), (int) event.y());
        if (isPrimary(event.button()) && !languageOpen) {
            int edge = edgeAt(event.x(), event.y());
            if (edge != 0 && !hits.hit(event.x(), event.y())) {
                resizing = edge;
                return true;
            }
        }
        if (isPrimary(event.button()) && hits.click(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * The primary click. GLFW's left button is 0, but this player's setup reports every click as 1 (seen in the
     * log), so both 0 and 1 count; the middle button (2) and others are ignored.
     */
    static boolean isPrimary(int button) {
        return button == 0 || button == 1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) (scrollY * 16));
        return true;
    }
}
