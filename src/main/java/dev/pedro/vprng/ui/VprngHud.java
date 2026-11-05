package dev.pedro.vprng.ui;

import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.PoolController;
import dev.pedro.vprng.ui.hud.HudLayout;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * In-game status HUD: up to three small VTP-styled panels (pool, prediction, activity), each draggable in the HUD
 * editor. Visible only while the reroller is not idle; the editor shows sample values when nothing is running.
 */
public final class VprngHud implements HudElement {
    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VprngClient.MOD_ID, "status"), new VprngHud());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        PoolController c = VprngClient.controller();
        if (!VprngConfig.get().hudEnabled || c.state() == PoolController.State.IDLE) return;
        if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) return;
        for (HudLayout.Placed p : HudLayout.compute(mc.font, g.guiWidth(), g.guiHeight(), c, false, false)) {
            p.panel().draw(g, mc.font, p.x(), p.y(), p.width(), p.height(), p.rows(), 1f);
        }
    }
}
