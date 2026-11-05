package dev.pedro.vprng.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.pedro.vprng.VprngClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/** User settings, stored as JSON in the Fabric config dir. All timings are game ticks (20 = 1 s). */
public final class VprngConfig {
    public enum DayGuard {
        /** Do nothing special. */
        OFF,
        /** Warn when starting during the villager work window (default). */
        WARN,
        /** Refuse to run / pause during the work window. */
        BLOCK
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Hold lecterns in the off hand (no hotbar switching, fewer packets) instead of the main hand. */
    public boolean lecternInOffhand = true;
    /** Minimum ticks between two world actions; raise on laggy servers. */
    public int actionDelayTicks = 1;
    /** Look at the target one tick before acting so the server has already seen the rotation. */
    public boolean aimFirst = true;
    /** Radius (blocks) around the player scanned for burner villagers. */
    public double scanRadius = 6.0;

    public int claimTimeoutTicks = 400;
    /** After the first villager claimed, how long to keep waiting for the rest before resetting the cycle. */
    public int claimGraceTicks = 60;
    public int offersTimeoutTicks = 60;
    public int placeConfirmTicks = 20;
    public int breakTimeoutTicks = 400;
    public int resetTimeoutTicks = 200;
    public int pickupTimeoutTicks = 100;
    /** Ticks between attempts to open the lectern container while the lecterns travel down the hoppers. */
    public int containerRetryTicks = 20;
    /** Give up (pause) if no lecterns arrived in the container within this many ticks. */
    public int containerTimeoutTicks = 400;
    /** Sneak while placing so that clicking a hopper/chest/door as support places the block instead of using it. */
    public boolean sneakWhenPlacing = true;
    /** How long to wait for an entity (e.g. a villager) to leave a cell before pausing. */
    public int placeBlockedTimeoutTicks = 200;

    /** Walk onto dropped lecterns that vanilla did not pick up, then walk back to the starting spot. */
    public boolean autoCollect = true;
    /** Only dropped lecterns within this many blocks of the player and of the starting spot are chased. */
    public double collectRadius = 6.0;
    /** Give up (and pause) if a collect/return walk takes longer than this. */
    public int walkTimeoutTicks = 120;

    /**
     * Multiplayer: when VTP's sequence position drifts, calibrate it automatically from the offers of consecutive
     * opens (the same search as {@code /vtp calibrate}), applied only when exactly one position matches.
     */
    public boolean autoCalibrate = true;

    public enum VjfMode {
        /** Single trigger for a one-cell pool, multiple trigger for bigger pools. */
        AUTO,
        /** {@code /trigger vjf_single}: the closest villager within 20 blocks. */
        SINGLE,
        /** {@code /trigger vjf_multiple}: the 16 closest villagers within 20 blocks. */
        MULTIPLE
    }

    /**
     * Use the Villager Job Fix data pack to make villagers take the placed lecterns immediately instead of waiting
     * for them to walk there. OFF by default: it sends {@code /trigger} commands to the server and the pack also
     * affects other trade-unlocked villagers within 20 blocks. Toggle with {@code /vprng vjf on|off}.
     */
    public boolean useVillagerJobFix = false;
    public VjfMode vjfMode = VjfMode.AUTO;
    /** Ticks between two trigger commands while waiting for villagers to take their lecterns. */
    public int vjfRetryTicks = 20;
    /** Safety cap on trigger commands per reroll cycle. */
    public int vjfMaxTriggersPerCycle = 5;

    /** 0 = unlimited. */
    public int maxCycles = 0;
    public boolean stopOnDamage = true;
    public DayGuard dayGuard = DayGuard.WARN;
    public boolean foundSound = true;
    /**
     * Progress and diagnostic messages in chat (pauses, calibration, warnings...). Off by default; the same information
     * is on the HUD and in the window. Direct answers to your commands, key presses and the FOUND notice always show.
     */
    public boolean chatLogs = false;

    /** Language of this mod only: "en" (default), "pt", "es", or "auto" to follow the game's language. */
    public String language = "en";

    /**
     * Keep running when the window loses focus (alt-tab): while the reroller runs, vanilla's "pause on lost focus"
     * is switched off in memory and your own setting is restored when it stops or pauses.
     */
    public boolean runWhenUnfocused = true;

    /** Outline the registered lecterns in the world: green when usable, red when not (see CellStatus). */
    public boolean showHighlights = true;

    /** Where one HUD panel sits once the player has dragged it; panels never dragged stack from hudX/hudY. */
    public static final class PanelPos {
        public int x;
        public int y;
        public boolean enabled = true;
        /** True once dragged: use x/y instead of the automatic stack. */
        public boolean placed;
        /** Size set in the HUD editor; 0 = automatic (default width, height fitted to the content). */
        public int width;
        public int height;
    }

    /** "x,y,z" of blocks whose outline the player switched off individually (cells and the container). */
    public java.util.Set<String> highlightOff = new java.util.LinkedHashSet<>();

    private static String key(net.minecraft.core.BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public boolean isHighlighted(net.minecraft.core.BlockPos pos) {
        return highlightOff == null || !highlightOff.contains(key(pos));
    }

    public void setHighlighted(net.minecraft.core.BlockPos pos, boolean on) {
        if (highlightOff == null) highlightOff = new java.util.LinkedHashSet<>();
        if (on) highlightOff.remove(key(pos));
        else highlightOff.add(key(pos));
    }

    public java.util.Map<String, PanelPos> hudPanels = new java.util.LinkedHashMap<>();

    public PanelPos panel(String id) {
        if (hudPanels == null) hudPanels = new java.util.LinkedHashMap<>();
        return hudPanels.computeIfAbsent(id, k -> new PanelPos());
    }

    /** Size of the settings window, set by dragging its edges; 0 = default size. */
    public int windowW;
    public int windowH;
    public boolean hudEnabled = true;
    public int hudX = 6;
    /** Below VTP's own panel (top left); change with /vprng hud <x> <y>. */
    public int hudY = 140;

    private static VprngConfig instance = new VprngConfig();

    public static VprngConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("villager-trading-reroller.json");
    }

    /** The mod used to be called villager-prng-manipulator: carry its config and data files over once. */
    private static void migrateLegacyFiles() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        for (String suffix : new String[] {".json", "-pools.json", "-containers.json"}) {
            Path old = dir.resolve("villager-prng-manipulator" + suffix);
            Path next = dir.resolve("villager-trading-reroller" + suffix);
            if (!Files.exists(old) || Files.exists(next)) continue;
            try {
                Files.move(old, next);
                VprngClient.LOGGER.info("Migrated {} to {}", old.getFileName(), next.getFileName());
            } catch (IOException e) {
                VprngClient.LOGGER.warn("Could not migrate {}: {}", old.getFileName(), e.toString());
            }
        }
    }

    public static void load() {
        migrateLegacyFiles();
        Path file = file();
        if (!Files.exists(file)) {
            instance = new VprngConfig();
            save();
            return;
        }
        try {
            VprngConfig loaded = GSON.fromJson(Files.readString(file), VprngConfig.class);
            instance = loaded == null ? new VprngConfig() : loaded.sanitized();
        } catch (IOException | RuntimeException e) {
            VprngClient.LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
            instance = new VprngConfig();
        }
    }

    public static void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(instance));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            VprngClient.LOGGER.warn("Could not write {}: {}", file, e.toString());
        }
    }

    /** Clamp hand-edited values to sane ranges so a typo cannot freeze or spam the game. */
    private VprngConfig sanitized() {
        actionDelayTicks = Math.clamp(actionDelayTicks, 0, 40);
        scanRadius = Math.clamp(scanRadius, 1.0, 16.0);
        claimTimeoutTicks = Math.clamp(claimTimeoutTicks, 20, 6000);
        claimGraceTicks = Math.clamp(claimGraceTicks, 0, 1200);
        offersTimeoutTicks = Math.clamp(offersTimeoutTicks, 10, 600);
        placeConfirmTicks = Math.clamp(placeConfirmTicks, 5, 200);
        breakTimeoutTicks = Math.clamp(breakTimeoutTicks, 20, 6000);
        resetTimeoutTicks = Math.clamp(resetTimeoutTicks, 20, 6000);
        pickupTimeoutTicks = Math.clamp(pickupTimeoutTicks, 20, 1200);
        containerRetryTicks = Math.clamp(containerRetryTicks, 5, 400);
        containerTimeoutTicks = Math.clamp(containerTimeoutTicks, 40, 6000);
        if (language == null || !dev.pedro.vprng.i18n.I18n.validSetting(language)) language = "en";
        placeBlockedTimeoutTicks = Math.clamp(placeBlockedTimeoutTicks, 20, 2400);
        vjfRetryTicks = Math.clamp(vjfRetryTicks, 5, 400);
        vjfMaxTriggersPerCycle = Math.clamp(vjfMaxTriggersPerCycle, 1, 100);
        if (vjfMode == null) vjfMode = VjfMode.AUTO;
        // Old default sat on top of VTP's HUD: move an untouched old value down with the new default.
        if (hudX == 6 && hudY == 40) hudY = 140;
        windowW = windowW <= 0 ? 0 : Math.clamp(windowW, 300, 1600);
        windowH = windowH <= 0 ? 0 : Math.clamp(windowH, 200, 1200);
        hudX = Math.clamp(hudX, 0, 4000);
        hudY = Math.clamp(hudY, 0, 4000);
        collectRadius = Math.clamp(collectRadius, 1.0, 12.0);
        walkTimeoutTicks = Math.clamp(walkTimeoutTicks, 20, 1200);
        maxCycles = Math.max(0, maxCycles);
        if (dayGuard == null) dayGuard = DayGuard.WARN;
        return this;
    }
}
