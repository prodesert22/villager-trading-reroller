package dev.pedro.vprng.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.roll.Cell;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Remembers which lectern cells belong to the pool, per server / singleplayer world and dimension, so a
 * restart does not force re-registering them. Only the cell positions are stored, never any VTP data.
 */
public final class PoolStore {
    private record CellData(String dimension, int x, int y, int z, String face) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, List<CellData>>>() {}.getType();

    private PoolStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("villager-trading-reroller-pools.json");
    }

    /** Stable identity of the current world: server address, or singleplayer level name. */
    public static String worldKey(Minecraft mc) {
        if (mc.getCurrentServer() != null) return "mp:" + mc.getCurrentServer().ip;
        if (mc.getSingleplayerServer() != null) {
            return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "unknown";
    }

    public static String dimension(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    public static List<Cell> load(Minecraft mc) {
        List<Cell> cells = new ArrayList<>();
        Map<String, List<CellData>> all = readAll();
        String dimension = dimension(mc);
        for (CellData d : all.getOrDefault(worldKey(mc), List.of())) {
            if (!d.dimension().equals(dimension)) continue;
            Direction face = Direction.byName(d.face());
            if (face != null) cells.add(new Cell(new BlockPos(d.x(), d.y(), d.z()), face));
        }
        return cells;
    }

    public static void save(Minecraft mc, List<Cell> cells) {
        Map<String, List<CellData>> all = readAll();
        String key = worldKey(mc);
        String dimension = dimension(mc);
        List<CellData> merged = new ArrayList<>();
        // keep entries of other dimensions of the same world untouched
        for (CellData d : all.getOrDefault(key, List.of())) {
            if (!d.dimension().equals(dimension)) merged.add(d);
        }
        for (Cell c : cells) {
            merged.add(new CellData(dimension, c.pos().getX(), c.pos().getY(), c.pos().getZ(), c.face().getName()));
        }
        if (merged.isEmpty()) all.remove(key);
        else all.put(key, merged);
        writeAll(all);
    }

    // ---- container (where broken lecterns end up), one per world+dimension

    private record ContainerData(String dimension, int x, int y, int z) {}

    private static final Type CONTAINER_TYPE = new TypeToken<LinkedHashMap<String, List<ContainerData>>>() {}.getType();

    private static Path containerFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("villager-trading-reroller-containers.json");
    }

    /** @return the registered container position for this world and dimension, or null. */
    public static BlockPos loadContainer(Minecraft mc) {
        String dimension = dimension(mc);
        for (ContainerData d : readContainers().getOrDefault(worldKey(mc), List.of())) {
            if (d.dimension().equals(dimension)) return new BlockPos(d.x(), d.y(), d.z());
        }
        return null;
    }

    /** Registers (or with {@code null} removes) the container for this world and dimension. */
    public static void saveContainer(Minecraft mc, BlockPos pos) {
        Map<String, List<ContainerData>> all = readContainers();
        String key = worldKey(mc);
        String dimension = dimension(mc);
        List<ContainerData> merged = new ArrayList<>();
        for (ContainerData d : all.getOrDefault(key, List.of())) {
            if (!d.dimension().equals(dimension)) merged.add(d);
        }
        if (pos != null) merged.add(new ContainerData(dimension, pos.getX(), pos.getY(), pos.getZ()));
        if (merged.isEmpty()) all.remove(key);
        else all.put(key, merged);
        Path file = containerFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(all, CONTAINER_TYPE));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            VprngClient.LOGGER.warn("Could not write container file {}: {}", file, e.toString());
        }
    }

    private static Map<String, List<ContainerData>> readContainers() {
        Path file = containerFile();
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            Map<String, List<ContainerData>> parsed = GSON.fromJson(Files.readString(file), CONTAINER_TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (IOException | RuntimeException e) {
            VprngClient.LOGGER.warn("Could not read container file {}: {}", file, e.toString());
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, List<CellData>> readAll() {
        Path file = file();
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            Map<String, List<CellData>> parsed = GSON.fromJson(Files.readString(file), TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (IOException | RuntimeException e) {
            VprngClient.LOGGER.warn("Could not read pool file {}: {}", file, e.toString());
            return new LinkedHashMap<>();
        }
    }

    private static void writeAll(Map<String, List<CellData>> all) {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(all, TYPE));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            VprngClient.LOGGER.warn("Could not write pool file {}: {}", file, e.toString());
        }
    }
}
