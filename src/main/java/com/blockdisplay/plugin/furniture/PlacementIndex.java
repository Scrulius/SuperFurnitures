package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight metadata index of every placed furniture instance (placements.json).
 * Powers the per-player/per-chunk limits and /furniture list. The world itself is the
 * source of truth for behavior (entities + PDC persist with chunks); if this index ever
 * drifts (e.g. an admin /kills entities by hand), furniture keeps working — only the
 * counts are affected.
 */
public class PlacementIndex {

    public record Placement(String type, String owner, String world, double x, double y, double z) {}

    private final BlockDisplayPlugin plugin;
    private final File file;
    private final Gson gson = new Gson();
    private final Map<String, Placement> byInstance = new ConcurrentHashMap<>();

    public PlacementIndex(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "placements.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Map<String, Placement> data = gson.fromJson(reader,
                    new TypeToken<Map<String, Placement>>() {}.getType());
            if (data != null) {
                byInstance.putAll(data);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read placements.json: " + e.getMessage());
        }
    }

    private void saveAsync() {
        Map<String, Placement> snapshot = new HashMap<>(byInstance);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(snapshot, writer);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not save placements.json: " + e.getMessage());
            }
        });
    }

    public void add(String instanceId, Placement placement) {
        byInstance.put(instanceId, placement);
        saveAsync();
    }

    public void remove(String instanceId) {
        if (byInstance.remove(instanceId) != null) {
            saveAsync();
        }
    }

    public int countByOwner(String ownerUuid) {
        int count = 0;
        for (Placement p : byInstance.values()) {
            if (p.owner().equals(ownerUuid)) count++;
        }
        return count;
    }

    public int countInChunk(String world, int chunkX, int chunkZ) {
        int count = 0;
        for (Placement p : byInstance.values()) {
            if (p.world().equals(world)
                    && ((int) Math.floor(p.x())) >> 4 == chunkX
                    && ((int) Math.floor(p.z())) >> 4 == chunkZ) {
                count++;
            }
        }
        return count;
    }

    public List<Placement> byOwner(String ownerUuid) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : byInstance.values()) {
            if (p.owner().equals(ownerUuid)) out.add(p);
        }
        return out;
    }
}
