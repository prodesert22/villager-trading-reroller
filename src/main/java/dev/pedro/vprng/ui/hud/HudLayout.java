package dev.pedro.vprng.ui.hud;

import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.PoolController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Works out where each enabled panel goes. Panels the player never dragged stack downwards from
 * {@code hudX/hudY}; dragged ones use their own saved position. Shared by the in-game HUD and the editor so a
 * panel is always drawn exactly where the editor shows it.
 */
public final class HudLayout {
    private static final int GAP = 4;

    public record Placed(HudPanels panel, int x, int y, int width, int height, List<HudPanels.Row> rows) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    private HudLayout() {}

    public static List<Placed> compute(Font font, int guiWidth, int guiHeight, PoolController c, boolean sample,
                                       boolean includeHidden) {
        VprngConfig cfg = VprngConfig.get();
        List<Placed> result = new ArrayList<>();
        int stackY = cfg.hudY;
        for (HudPanels panel : HudPanels.values()) {
            VprngConfig.PanelPos pos = cfg.panel(panel.id);
            if (!pos.enabled && !includeHidden) continue;
            List<HudPanels.Row> rows = panel.rows(c, sample);
            if (rows.isEmpty() && !includeHidden) continue;
            int natural = panel.height(rows);
            int w = pos.width > 0 ? Math.clamp(pos.width, HudPanels.MIN_WIDTH, HudPanels.MAX_WIDTH) : HudPanels.WIDTH;
            int h = pos.height > 0 ? Math.max(natural, Math.min(pos.height, HudPanels.MAX_HEIGHT)) : natural;
            int x;
            int y;
            if (pos.placed) {
                x = pos.x;
                y = pos.y;
            } else {
                x = cfg.hudX;
                y = stackY;
                stackY += h + GAP;
            }
            x = Math.clamp(x, 0, Math.max(0, guiWidth - w));
            y = Math.clamp(y, 0, Math.max(0, guiHeight - h));
            result.add(new Placed(panel, x, y, w, h, rows));
        }
        return result;
    }
}
