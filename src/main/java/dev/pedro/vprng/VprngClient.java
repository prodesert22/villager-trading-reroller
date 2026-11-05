package dev.pedro.vprng;

import dev.pedro.vprng.bridge.VtpBridge;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.PoolController;
import dev.pedro.vprng.ui.Highlighter;
import dev.pedro.vprng.ui.VprngCommands;
import dev.pedro.vprng.ui.VprngHud;
import dev.pedro.vprng.ui.VprngKeys;
import dev.pedro.vprng.ui.screen.VprngScreen;
import dev.pedro.vprng.vjf.VjfSupport;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VprngClient implements ClientModInitializer {
    public static final String MOD_ID = "villager-trading-reroller";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final PoolController CONTROLLER = new PoolController();

    public static PoolController controller() {
        return CONTROLLER;
    }

    // ---- small tick scheduler: lets a screen close first, then act (crosshair actions, starting a run, reopening)

    private record Delayed(int[] ticks, Runnable action) {}

    private static final java.util.List<Delayed> DELAYED = new java.util.ArrayList<>();

    /** Runs {@code action} on the client thread after {@code ticks} client ticks. */
    public static void later(int ticks, Runnable action) {
        DELAYED.add(new Delayed(new int[] {Math.max(1, ticks)}, action));
    }

    private static void runDelayed() {
        for (int i = 0; i < DELAYED.size(); i++) {
            Delayed d = DELAYED.get(i);
            if (--d.ticks()[0] <= 0) {
                DELAYED.remove(i--);
                d.action().run();
            }
        }
    }

    /** Chat message in the same format the controller uses. */
    public static void chat(Minecraft mc, String message) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[VPRNG] " + message));
    }

    /** Opens the main window once any chat/other screen has closed. */
    public static void openGui(VprngScreen.Tab tab) {
        later(2, () -> Minecraft.getInstance().gui.setScreen(new VprngScreen(tab)));
    }

    /**
     * Closes the window, runs an action that reads the crosshair (add lectern, set container...) once the view is
     * clear, reports the result in chat and reopens the window on {@code tab}.
     */
    public static void withCrosshair(java.util.function.Function<Minecraft, String> action, VprngScreen.Tab tab) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(null);
        later(3, () -> {
            chat(mc, action.apply(mc));
            openGui(tab);
        });
    }

    @Override
    public void onInitializeClient() {
        VprngConfig.load();
        VprngCommands.register();
        VprngKeys.register();
        VprngHud.register();

        Highlighter.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            runDelayed();
            VprngKeys.poll(mc, CONTROLLER);
            CONTROLLER.tick(mc);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            VtpBridge.selfTest();
            VjfSupport.invalidate();
            CONTROLLER.loadPool(mc);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            VjfSupport.invalidate();
            CONTROLLER.stop(mc, dev.pedro.vprng.i18n.I18n.tr("st.disconnected"));
        });
        LOGGER.info("Villager Trading Reroller ready (needs Villager Trading Predictor)");
    }
}
