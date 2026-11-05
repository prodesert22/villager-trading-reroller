package dev.pedro.vprng.ui.screen;

import dev.pedro.vprng.VprngClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Click regions for an immediate-mode screen. The screen registers every clickable rectangle while it draws into a
 * private "building" list and calls {@link #commit()} when the frame is complete; clicks and tooltips only ever read
 * the last COMPLETE frame. This matters because the frame is extracted separately from input handling: reading a
 * list that is being rebuilt (or was just cleared) would make clicks land on nothing.
 */
final class HitMap {
    private record Hit(int x, int y, int w, int h, Runnable action, String tooltip) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private volatile List<Hit> active = List.of();
    private List<Hit> building = new ArrayList<>();

    /** Starts a new frame. */
    void clear() {
        building = new ArrayList<>();
    }

    void add(int x, int y, int w, int h, Runnable action, String tooltip) {
        building.add(new Hit(x, y, w, h, action, tooltip));
    }

    /** Publishes the frame that was just drawn. */
    void commit() {
        active = List.copyOf(building);
    }

    boolean hovered(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Tooltip of the topmost region under the cursor, or null. */
    String tooltipAt(double mx, double my) {
        List<Hit> snapshot = active;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Hit hit = snapshot.get(i);
            if (hit.contains(mx, my)) return hit.tooltip();
        }
        return null;
    }

    /** Whether any registered region of the last complete frame is under the point (no action is run). */
    boolean hit(double mx, double my) {
        for (Hit hit : active) {
            if (hit.contains(mx, my)) return true;
        }
        return false;
    }

    boolean click(double mx, double my) {
        List<Hit> snapshot = active;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Hit hit = snapshot.get(i);
            if (hit.contains(mx, my)) {
                VprngClient.LOGGER.debug("[VPRNG gui] click at {},{} hit region {}x{}+{},{}", (int) mx, (int) my,
                        hit.w(), hit.h(), hit.x(), hit.y());
                try {
                    Minecraft.getInstance().getSoundManager()
                            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                } catch (RuntimeException e) {
                    VprngClient.LOGGER.warn("[VPRNG gui] click sound failed: {}", e.toString());
                }
                hit.action().run();
                return true;
            }
        }
        VprngClient.LOGGER.debug("[VPRNG gui] click at {},{} hit nothing ({} regions)", (int) mx, (int) my, snapshot.size());
        return false;
    }
}
