package dev.pedro.vprng.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.roll.PoolController;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class VprngKeys {
    private static KeyMapping toggleKey;
    private static KeyMapping guiKey;

    private VprngKeys() {}

    public static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(VprngClient.MOD_ID, "category"));
        // G is unused by vanilla and by VTP (which uses V); rebind in Options > Controls if it clashes.
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.villager-trading-reroller.toggle", InputConstants.KEY_G, category));
        guiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.villager-trading-reroller.gui", InputConstants.KEY_K, category));
    }

    /** The key currently bound to the window, as shown in Controls (follows the player's own binding). */
    public static String guiKeyName() {
        return guiKey == null ? "?" : guiKey.getTranslatedKeyMessage().getString();
    }

    /** The key currently bound to start/pause/stop. */
    public static String toggleKeyName() {
        return toggleKey == null ? "?" : toggleKey.getTranslatedKeyMessage().getString();
    }

    /** Start / pause-resume / stop on one key. ESC while a trade screen is open also pauses the run. */
    public static void poll(Minecraft mc, PoolController controller) {
        while (toggleKey.consumeClick()) {
            if (mc.player != null) controller.toggle(mc);
        }
        while (guiKey.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                mc.gui.setScreen(new dev.pedro.vprng.ui.screen.VprngScreen(dev.pedro.vprng.ui.screen.VprngScreen.Tab.CONTROL));
            }
        }
    }
}
