package dev.pedro.vprng.ui;

import dev.pedro.vprng.bridge.VtpBridge;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing helpers in the look of Villager Trading Predictor's HUD: dark pixel-cornered panels, a thin grey edge,
 * white/grey text and the accent colour the player picked in VTP (green by default). Original code; only the
 * colour values follow VTP so the two mods look like one.
 */
public final class Theme {
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFFA0A0A0;
    public static final int EDGE = 0xFF383838;
    public static final int EDGE_DARK = 0xFF242424;
    public static final int BG = 0xFF131313;
    public static final int SLOT = 0xFF0C0C0C;
    public static final int GOOD = 0xFF55FF55;
    public static final int BAD = 0xFFFF5555;
    public static final int WARN = 0xFFFFCC66;
    public static final int INFO = 0xFF55CCFF;

    private Theme() {}

    public static int accent() {
        return VtpBridge.accent();
    }

    public static int alpha(int color, float alpha) {
        return (Math.clamp(Math.round((color >>> 24) * alpha), 0, 255) << 24) | (color & 0xFFFFFF);
    }

    /** Mixes {@code color} towards black/white; handy for hover and pressed shades. */
    public static int shade(int color, float factor) {
        int r = Math.clamp(Math.round(((color >> 16) & 0xFF) * factor), 0, 255);
        int g = Math.clamp(Math.round(((color >> 8) & 0xFF) * factor), 0, 255);
        int b = Math.clamp(Math.round((color & 0xFF) * factor), 0, 255);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }

    /** Pixel-cornered dark panel with a lighter top/left and darker bottom/right edge, like VTP's tooltips. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, float opacity) {
        int bg = alpha(BG, opacity);
        g.fill(x + 2, y + 1, x + w - 2, y + h - 1, bg);
        g.fill(x + 1, y + 2, x + 2, y + h - 2, bg);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, bg);
        int top = alpha(EDGE, opacity);
        int bottom = alpha(EDGE_DARK, opacity);
        g.fill(x + 2, y, x + w - 2, y + 1, top);
        g.fill(x, y + 2, x + 1, y + h - 2, top);
        g.fill(x + 1, y + 1, x + 2, y + 2, top);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, top);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, bottom);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bottom);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, bottom);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, bottom);
    }

    /** Inset dark slot (list rows, item frames). */
    public static void slot(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT);
        g.fill(x, y, x + w, y + 1, EDGE);
        g.fill(x, y, x + 1, y + h, EDGE);
        g.fill(x, y + h - 1, x + w, y + h, EDGE_DARK);
        g.fill(x + w - 1, y, x + w, y + h, EDGE_DARK);
    }

    /**
     * Price tag: the number followed by an emerald icon, right-aligned so it ends at {@code rightX}.
     * Returns the width used (so the caller can shorten the text next to it).
     */
    public static int price(GuiGraphicsExtractor g, Font font, int rightX, int y, int price, int color, float opacity) {
        String number = String.valueOf(price);
        int iconX = rightX - 16;
        int textX = iconX - 1 - font.width(number);
        g.text(font, number, textX, y + 5, alpha(color, opacity));
        g.item(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD), iconX, y);
        return rightX - textX;
    }

    /** Small status pill with a coloured dot. Returns its width. */
    public static int chip(GuiGraphicsExtractor g, Font font, int x, int y, String text, int color, float opacity) {
        int width = font.width(text) + 16;
        g.fill(x, y, x + width, y + 12, alpha(0xFF0C0C0C, opacity));
        g.fill(x, y, x + width, y + 1, alpha(color, 0.55f * opacity));
        g.fill(x, y + 11, x + width, y + 12, alpha(color, 0.25f * opacity));
        g.fill(x + 4, y + 4, x + 8, y + 8, alpha(color, opacity));
        g.text(font, text, x + 11, y + 2, alpha(color, opacity));
        return width;
    }

    /** Horizontal progress bar. */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float fraction, int color, float opacity) {
        g.fill(x, y, x + w, y + h, alpha(SLOT, opacity));
        g.fill(x, y, x + w, y + 1, alpha(EDGE, opacity));
        int fill = Math.clamp(Math.round((w - 2) * fraction), 0, w - 2);
        if (fill > 0) {
            g.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, alpha(color, opacity));
            g.fill(x + 1, y + 1, x + 1 + fill, y + 2, alpha(0xFFFFFFFF, 0.25f * opacity));
        }
    }

    /** Flat button in the panel style. {@code primary} uses the accent colour. */
    public static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label,
                              boolean hovered, boolean enabled, boolean primary) {
        int accent = accent();
        int base = primary ? shade(accent, 0.32f) : 0xFF202020;
        int fill = !enabled ? 0xFF161616 : hovered ? shade(base, 1.35f) : base;
        g.fill(x, y, x + w, y + h, fill);
        int edgeTop = primary && enabled ? accent : EDGE;
        g.fill(x, y, x + w, y + 1, !enabled ? EDGE_DARK : edgeTop);
        g.fill(x, y, x + 1, y + h, !enabled ? EDGE_DARK : edgeTop);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF0A0A0A);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF0A0A0A);
        int color = !enabled ? 0xFF606060 : primary ? alpha(accent, 1f) : TEXT;
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, color);
    }

    /** On/off switch drawn as a small track; returns nothing, hit testing is the caller's job. */
    public static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hovered) {
        int accent = accent();
        g.fill(x, y, x + 24, y + 12, on ? shade(accent, 0.45f) : 0xFF1A1A1A);
        g.outline(x, y, 24, 12, hovered ? TEXT : on ? accent : EDGE);
        int knob = on ? x + 13 : x + 2;
        g.fill(knob, y + 2, knob + 9, y + 10, on ? accent : MUTED);
    }
}
