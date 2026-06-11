package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import com.google.gson.Gson;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Structured audit trail of furniture operations (furniture-log.jsonl): one JSON object per
 * line — who placed/picked/rotated/purged what, where and when. The entry is built on the main
 * thread (it reads live state); the file append runs async, serialized by a lock so concurrent
 * writes can't interleave. Size-capped: at ~2 MB the file rotates to .1 (one generation kept).
 */
public class FurnitureAudit {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("dd/MM HH:mm");

    /** One audited operation. action: PLACE / PLACE_SERVER / PICKUP / ROTATE / PURGE. */
    record Entry(long ts, String action, String actor, String actorUuid,
                 String type, String instance, String world, int x, int y, int z) {}

    private final BlockDisplayPlugin plugin;
    private final File file;
    private final Gson gson = new Gson();
    private final Object fileLock = new Object();

    public FurnitureAudit(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "furniture-log.jsonl");
    }

    public void log(String action, Player actor, String typeId, String instance, Location loc) {
        log(action, actor.getName(), actor.getUniqueId().toString(), typeId, instance, loc);
    }

    public void log(String action, String actorName, String actorUuid,
                    String typeId, String instance, Location loc) {
        Entry entry = new Entry(System.currentTimeMillis(), action, actorName, actorUuid,
                typeId, instance, loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String line = gson.toJson(entry);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (fileLock) {
                try {
                    if (file.length() > MAX_BYTES) {
                        File old = new File(file.getParentFile(), file.getName() + ".1");
                        Files.deleteIfExists(old.toPath());
                        Files.move(file.toPath(), old.toPath());
                    }
                    try (FileWriter writer = new FileWriter(file, true)) {
                        writer.write(line);
                        writer.write(System.lineSeparator());
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("No se pudo escribir furniture-log.jsonl: " + e.getMessage());
                }
            }
        });
    }

    /**
     * The most recent entries, newest first, optionally filtered by actor name (case-insensitive).
     * Reads the whole file (size-capped at ~2 MB) — admin command, not a hot path.
     */
    public List<Entry> readRecent(String actorFilter, int limit) {
        List<Entry> out = new ArrayList<>();
        synchronized (fileLock) {
            if (!file.exists()) return out;
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                    String line = lines.get(i).trim();
                    if (line.isEmpty()) continue;
                    try {
                        Entry entry = gson.fromJson(line, Entry.class);
                        if (entry == null) continue;
                        if (actorFilter != null && !actorFilter.equalsIgnoreCase(entry.actor())) continue;
                        out.add(entry);
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("No se pudo leer furniture-log.jsonl: " + e.getMessage());
            }
        }
        return out;
    }

    /** "11/06 19:42 PICKUP silla por Pepe @ 120, 64, -35 (world)" */
    public static String format(Entry e) {
        return String.format(Locale.ROOT, "%s %s %s por %s @ %d, %d, %d (%s)",
                FORMAT.format(new Date(e.ts())), e.action(), e.type(), e.actor(),
                e.x(), e.y(), e.z(), e.world());
    }
}
