package dev.pedro.vprng.ui.screen;

import dev.pedro.vprng.i18n.I18n;
import com.mojang.blaze3d.platform.InputConstants;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.PoolController;
import dev.pedro.vprng.ui.Theme;
import dev.pedro.vprng.ui.hud.HudLayout;
import dev.pedro.vprng.ui.hud.HudPanels;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * HUD editor: every panel is drawn where it really sits (with sample values when nothing is running) and can be
 * dragged with the mouse, nudged with the arrow keys (Shift = 8 px), hidden with its small × button, or reset.
 */
public final class HudEditScreen extends Screen {
    private final Screen parent;
    private final HitMap hits = new HitMap();
    /** What a drag started on a panel does. */
    private enum Mode { MOVE, RESIZE_W, RESIZE_H, RESIZE_BOTH }

    private static final int EDGE_ZONE = 5;
    private static final int CORNER_ZONE = 11;

    private HudPanels dragging;
    private Mode mode = Mode.MOVE;
    private HudPanels selected;
    private int grabX;
    private int grabY;
    private int startWidth;
    private int startHeight;
    private int startMouseX;
    private int startMouseY;
    private boolean dirty;

    public HudEditScreen(Screen parent) {
        super(Component.literal(I18n.tr("hud.edit.title")));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x55000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        hits.clear();
        Minecraft mc = Minecraft.getInstance();
        PoolController c = VprngClient.controller();
        boolean sample = c.state() == PoolController.State.IDLE;
        int accent = Theme.accent();

        // light alignment guides while dragging
        if (dragging != null) {
            g.fill(width / 2, 0, width / 2 + 1, height, Theme.alpha(accent, 0.18f));
            g.fill(0, height / 2, width, height / 2 + 1, Theme.alpha(accent, 0.18f));
        }

        List<HudLayout.Placed> placed = HudLayout.compute(mc.font, width, height, c, sample, true);
        for (HudLayout.Placed p : placed) {
            VprngConfig.PanelPos pos = VprngConfig.get().panel(p.panel().id);
            boolean hover = p.contains(mouseX, mouseY) || p.panel() == dragging;
            p.panel().draw(g, mc.font, p.x(), p.y(), p.width(), p.height(), p.rows(), pos.enabled ? 1f : 0.35f);
            int edge = p.panel() == dragging ? accent : hover || p.panel() == selected ? Theme.alpha(accent, 0.8f) : 0x55FFFFFF;
            g.outline(p.x() - 1, p.y() - 1, p.width() + 2, p.height() + 2, edge);

            // resize grips: right edge, bottom edge and the corner; the one under the cursor (or being dragged) lights up
            Mode active = p.panel() == dragging ? mode : p.contains(mouseX, mouseY) ? zone(p, mouseX, mouseY) : Mode.MOVE;
            int rx = p.x() + p.width() - 1;
            int bottomY = p.y() + p.height() - 1;
            int gripHot = Theme.alpha(accent, 0.95f);
            int gripIdle = Theme.alpha(accent, hover ? 0.45f : 0.2f);
            g.fill(rx - 1, p.y() + 16, rx + 2, bottomY - CORNER_ZONE, active == Mode.RESIZE_W ? gripHot : gripIdle);
            g.fill(p.x() + 8, bottomY - 1, rx - CORNER_ZONE, bottomY + 2, active == Mode.RESIZE_H ? gripHot : gripIdle);
            int cornerColor = active == Mode.RESIZE_BOTH ? gripHot : gripIdle;
            for (int i = 0; i < 3; i++) { // three diagonal ticks in the corner
                int o = 3 + i * 3;
                g.fill(rx - o, bottomY - 1, rx - o + 1, bottomY, cornerColor);
                g.fill(rx - 1, bottomY - o, rx, bottomY - o + 1, cornerColor);
            }

            // visibility button in the top right corner of the panel
            int bx = p.x() + p.width() - 15;
            int by = p.y() + 4;
            boolean overButton = hits.hovered(bx, by, 11, 11, mouseX, mouseY);
            g.fill(bx, by, bx + 11, by + 11, overButton ? 0xFF3A3A3A : 0xFF202020);
            g.outline(bx, by, 11, 11, pos.enabled ? Theme.EDGE : Theme.BAD);
            g.text(mc.font, pos.enabled ? "×" : "+", bx + 3, by + 2, pos.enabled ? Theme.TEXT : Theme.BAD);
            HudPanels panel = p.panel();
            hits.add(bx, by, 11, 11, () -> {
                VprngConfig.PanelPos target = VprngConfig.get().panel(panel.id);
                target.enabled = !target.enabled;
                VprngConfig.save();
            }, pos.enabled ? I18n.tr("hud.edit.hide") : I18n.tr("hud.edit.show"));
        }

        // header + footer
        g.centeredText(mc.font, I18n.tr("hud.edit.header"), width / 2, 10, Theme.TEXT);
        g.centeredText(mc.font, I18n.tr("hud.edit.help"),
                width / 2, 22, Theme.MUTED);
        int bw = 120;
        int by = height - 30;
        int doneX = width / 2 - bw - 4;
        int resetX = width / 2 + 4;
        boolean hDone = hits.hovered(doneX, by, bw, 20, mouseX, mouseY);
        boolean hReset = hits.hovered(resetX, by, bw, 20, mouseX, mouseY);
        Theme.button(g, mc.font, doneX, by, bw, 20, I18n.tr("hud.edit.done"), hDone, true, true);
        Theme.button(g, mc.font, resetX, by, bw, 20, I18n.tr("hud.edit.reset"), hReset, true, false);
        hits.add(doneX, by, bw, 20, this::onClose, null);
        hits.add(resetX, by, bw, 20, this::resetLayout, I18n.tr("hud.edit.resetTip"));

        hits.commit();
        String tip = hits.tooltipAt(mouseX, mouseY);
        if (tip != null) g.setTooltipForNextFrame(Component.literal(tip), mouseX, mouseY);
    }

    private void resetLayout() {
        VprngConfig cfg = VprngConfig.get();
        for (HudPanels panel : HudPanels.values()) {
            VprngConfig.PanelPos pos = cfg.panel(panel.id);
            pos.placed = false;
            pos.enabled = true;
        }
        for (HudPanels panel : HudPanels.values()) {
            VprngConfig.PanelPos pos = cfg.panel(panel.id);
            pos.width = 0;
            pos.height = 0;
        }
        cfg.hudX = 6;
        cfg.hudY = 140;
        VprngConfig.save();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!VprngScreen.isPrimary(event.button())) return super.mouseClicked(event, doubleClick);
        double mx = event.x();
        double my = event.y();
        if (hits.click(mx, my)) return true;
        Minecraft mc = Minecraft.getInstance();
        List<HudLayout.Placed> placed = HudLayout.compute(mc.font, width, height, VprngClient.controller(),
                VprngClient.controller().state() == PoolController.State.IDLE, true);
        for (int i = placed.size() - 1; i >= 0; i--) {
            HudLayout.Placed p = placed.get(i);
            if (!p.contains(mx, my)) continue;
            // Freeze every panel where it is now, so dragging one does not make the stacked ones jump.
            VprngConfig cfg = VprngConfig.get();
            for (HudLayout.Placed q : placed) {
                VprngConfig.PanelPos pos = cfg.panel(q.panel().id);
                pos.x = q.x();
                pos.y = q.y();
                pos.placed = true;
            }
            dragging = p.panel();
            selected = p.panel();
            mode = zone(p, mx, my);
            grabX = (int) mx - p.x();
            grabY = (int) my - p.y();
            startWidth = p.width();
            startHeight = p.height();
            startMouseX = (int) mx;
            startMouseY = (int) my;
            // record the size now so a resize starts from what is on screen
            VprngConfig.PanelPos start = cfg.panel(p.panel().id);
            start.width = p.width();
            start.height = p.height();
            dirty = true;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(event, dx, dy);
        if (mode == Mode.MOVE) {
            move(dragging, (int) event.x() - grabX, (int) event.y() - grabY);
        } else {
            int newW = mode == Mode.RESIZE_H ? startWidth : startWidth + (int) event.x() - startMouseX;
            int newH = mode == Mode.RESIZE_W ? startHeight : startHeight + (int) event.y() - startMouseY;
            resize(dragging, newW, newH);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (selected != null) {
            int step = (event.modifiers() & InputConstants.MOD_SHIFT) != 0 ? 8 : 1;
            boolean resizing = (event.modifiers() & InputConstants.MOD_CONTROL) != 0;
            VprngConfig.PanelPos pos = VprngConfig.get().panel(selected.id);
            int key = event.key();
            int dx = key == InputConstants.KEY_LEFT ? -step : key == InputConstants.KEY_RIGHT ? step : 0;
            int dy = key == InputConstants.KEY_UP ? -step : key == InputConstants.KEY_DOWN ? step : 0;
            if (dx != 0 || dy != 0) {
                if (resizing) {
                    // Ctrl + arrows resize: the current on-screen size is the starting point
                    HudLayout.Placed current = placedFor(selected);
                    if (current != null) resize(selected, current.width() + dx, current.height() + dy);
                } else {
                    pos.placed = true;
                    move(selected, pos.x + dx, pos.y + dy);
                }
                dirty = true;
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Which part of the panel the point is on: a resize grip near the right/bottom edge, otherwise "move". */
    private static Mode zone(HudLayout.Placed p, double mx, double my) {
        boolean right = mx >= p.x() + p.width() - EDGE_ZONE;
        boolean bottom = my >= p.y() + p.height() - EDGE_ZONE;
        boolean cornerX = mx >= p.x() + p.width() - CORNER_ZONE;
        boolean cornerY = my >= p.y() + p.height() - CORNER_ZONE;
        if ((right && cornerY) || (bottom && cornerX) || (cornerX && cornerY)) return Mode.RESIZE_BOTH;
        if (right) return Mode.RESIZE_W;
        if (bottom) return Mode.RESIZE_H;
        return Mode.MOVE;
    }

    private HudLayout.Placed placedFor(HudPanels panel) {
        Minecraft mc = Minecraft.getInstance();
        for (HudLayout.Placed p : HudLayout.compute(mc.font, width, height, VprngClient.controller(),
                VprngClient.controller().state() == PoolController.State.IDLE, true)) {
            if (p.panel() == panel) return p;
        }
        return null;
    }

    /** Sets the panel size, clamped: width within the allowed range, height never below what the content needs. */
    private void resize(HudPanels panel, int newWidth, int newHeight) {
        VprngConfig.PanelPos pos = VprngConfig.get().panel(panel.id);
        HudLayout.Placed current = placedFor(panel);
        int natural = current == null ? 0 : panel.height(current.rows());
        pos.placed = true;
        pos.width = Math.clamp(newWidth, HudPanels.MIN_WIDTH, HudPanels.MAX_WIDTH);
        pos.height = Math.clamp(newHeight, Math.max(natural, 1), HudPanels.MAX_HEIGHT);
        pos.x = Math.clamp(pos.x, 0, Math.max(0, width - pos.width));
    }

    private void move(HudPanels panel, int x, int y) {
        VprngConfig.PanelPos pos = VprngConfig.get().panel(panel.id);
        pos.placed = true;
        HudLayout.Placed current = placedFor(panel);
        int w = current == null ? HudPanels.WIDTH : current.width();
        pos.x = Math.clamp(x, 0, Math.max(0, width - w));
        pos.y = Math.clamp(y, 0, Math.max(0, height - 20));
    }

    private void save() {
        if (dirty) VprngConfig.save();
        dirty = false;
    }

    @Override
    public void onClose() {
        save();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
