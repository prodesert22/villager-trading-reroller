package dev.pedro.vprng.ui;

import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.Cell;
import dev.pedro.vprng.roll.CellStatus;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Outlines the registered workstation cells in the world, through walls: green when the cell is usable, red when it
 * is not (with the reason floating above it), and cyan for the lectern container. "View" in the GUI makes one block
 * pulse with a beam and a label for a few seconds. Uses Minecraft's gizmo renderer inside Fabric's
 * {@code BEFORE_GIZMOS} hook, so no custom render pipeline is needed.
 */
public final class Highlighter {
    private static final int GREEN = 0xFF3DDC4A;
    private static final int RED = 0xFFFF4040;
    private static final int CYAN = 0xFF4FC3F7;
    private static final long FOCUS_MILLIS = 5000;

    private static BlockPos focus;
    private static String focusLabel = "";
    private static long focusUntil;
    private static boolean failed;

    private Highlighter() {}

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
    }

    /** Makes {@code pos} pulse with a beam and label for a few seconds (the GUI's "View"). */
    public static void focus(BlockPos pos, String label) {
        focus = pos.immutable();
        focusLabel = label;
        focusUntil = System.currentTimeMillis() + FOCUS_MILLIS;
    }

    private static void render() {
        if (failed) return;
        VprngConfig cfg = VprngConfig.get();
        long now = System.currentTimeMillis();
        if (focus != null && now >= focusUntil) {
            focus = null; // the "View" highlight is temporary: forget it once the 5 seconds are over
            focusLabel = "";
        }
        boolean focusing = focus != null;
        if (!cfg.showHighlights && !focusing) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        try {
            if (cfg.showHighlights) {
                List<Cell> cells = VprngClient.controller().cells();
                for (Cell cell : cells) {
                    if (!cfg.isHighlighted(cell.pos())) continue;
                    CellStatus status = CellStatus.ofCell(mc, cell);
                    box(cell.pos(), status.ok() ? GREEN : RED, 0.0);
                    if (!status.ok()) {
                        Gizmos.billboardTextOverBlock(status.reason(), cell.pos(), 0, RED, 0.9f);
                    }
                }
                BlockPos container = VprngClient.controller().containerPos();
                if (container != null && cfg.isHighlighted(container)) {
                    CellStatus status = CellStatus.ofContainer(mc, container);
                    box(container, status.ok() ? CYAN : RED, 0.0);
                    if (!status.ok()) Gizmos.billboardTextOverBlock(status.reason(), container, 0, RED, 0.9f);
                }
            }
            if (focusing) {
                double pulse = 0.5 + 0.5 * Math.sin(now / 160.0);
                box(focus, 0xFFFFFFFF, 0.08 + 0.1 * pulse);
                Vec3 base = Vec3.atCenterOf(focus);
                Gizmos.line(base, base.add(0, 4.0, 0), 0xFFFFFFFF, 3.0f).setAlwaysOnTop();
                if (!focusLabel.isEmpty()) {
                    Gizmos.billboardTextOverBlock(focusLabel, focus.above(4), 0, 0xFFFFFFFF, 1.3f);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true; // never spam the log every frame
            VprngClient.LOGGER.warn("Block highlighting disabled after an error", e);
        }
    }

    private static void box(BlockPos pos, int strokeColor, double grow) {
        int fill = (strokeColor & 0x00FFFFFF) | 0x33000000;
        AABB bounds = new AABB(pos).inflate(0.004 + grow);
        Gizmos.cuboid(bounds, GizmoStyle.strokeAndFill(strokeColor, 3.0f, fill)).setAlwaysOnTop();
    }
}
