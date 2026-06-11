package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-game footprint editor (/sf footprint): paint the solid shell of a furniture by punching
 * blocks instead of hand-writing local offsets. Punched cells toggle in/out of the working
 * set, which glows orange (particles only the editing admin sees) until saved. Saving writes
 * `footprint` (+ `solid: true`) to furniture.yml, reloads the catalog and re-shells the
 * edited instance on the spot so the result is immediately walkable. Other already-placed
 * units keep their old shell until re-placed (the shell is baked at place time).
 */
public class FootprintEditor {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "SF" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
    private static final double FIND_RADIUS = 5.0;
    /** Cells this far (in blocks) from the furniture are almost surely misclicks. */
    private static final int MAX_REACH = 8;
    private static final Particle.DustOptions WORKING_DUST = new Particle.DustOptions(Color.ORANGE, 0.7f);

    private final BlockDisplayPlugin plugin;
    private final FurnitureManager manager;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static class Session {
        UUID anchorId;
        String typeId;
        float yaw;
        final Set<String> cells = new LinkedHashSet<>(); // "x,y,z" local offsets
        BukkitRunnable drawTask;
    }

    public FootprintEditor(BlockDisplayPlugin plugin, FurnitureManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ==================== SESSION LIFECYCLE ====================

    public void start(Player player) {
        if (hasSession(player)) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Ya estás editando una huella. /sf footprint save o cancel primero.");
            return;
        }
        Interaction anchor = manager.findNearestAnchor(player, FIND_RADIUS);
        if (anchor == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No hay ningún mueble a menos de " + (int) FIND_RADIUS + " bloques.");
            return;
        }
        String typeId = anchor.getPersistentDataContainer().get(manager.keyType, PersistentDataType.STRING);
        FurnitureType type = manager.getRegistry().byId(typeId);
        if (type == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "Ese mueble ya no está en el catálogo; no se puede editar.");
            return;
        }
        Float yawObj = anchor.getPersistentDataContainer().get(manager.keyYaw, PersistentDataType.FLOAT);

        Session s = new Session();
        s.anchorId = anchor.getUniqueId();
        s.typeId = type.id;
        s.yaw = yawObj != null ? yawObj : 0f;
        if (!type.footprint.isEmpty()) {
            for (int[] off : type.footprint) {
                s.cells.add(off[0] + "," + off[1] + "," + off[2]);
            }
        } else if (type.solid) {
            // The implicit default shell: a column up to the hitbox height
            int heightBlocks = Math.max(1, (int) Math.ceil(type.hitboxHeight));
            for (int dy = 0; dy < heightBlocks; dy++) {
                s.cells.add("0," + dy + ",0");
            }
        }
        sessions.put(player.getUniqueId(), s);
        startDrawTask(player, s);

        player.sendMessage(PREFIX + ChatColor.GOLD + "Editando huella sólida de '" + type.id + "' ("
                + s.cells.size() + " celda(s), en naranja).");
        player.sendMessage(ChatColor.GRAY + " " + ChatColor.YELLOW + "Golpea bloques" + ChatColor.GRAY
                + " para añadir/quitar celdas (también las barreras existentes).");
        player.sendMessage(ChatColor.GRAY + " " + ChatColor.YELLOW + "/sf footprint add" + ChatColor.GRAY
                + " marca el bloque bajo tus pies (para celdas en el aire).");
        player.sendMessage(ChatColor.GRAY + " Termina con " + ChatColor.YELLOW + "/sf footprint save"
                + ChatColor.GRAY + " o " + ChatColor.YELLOW + "/sf footprint cancel");
    }

    public void cancel(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "No estás editando ninguna huella.");
            return;
        }
        endDrawTask(s);
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Edición de huella cancelada, sin cambios.");
    }

    /** Silent cleanup (quit/disable). */
    public void discard(UUID playerId) {
        Session s = sessions.remove(playerId);
        if (s != null) endDrawTask(s);
    }

    public void discardAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            endDrawTask(s);
        }
        sessions.clear();
    }

    // ==================== EDIT OPERATIONS ====================

    /** @return true when the punch was consumed by an editing session (cancel the event). */
    public boolean handlePunch(Player player, Block block) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return false;
        toggle(player, s, block);
        return true;
    }

    /** Marks the block at the admin's feet (cells with no block to punch: air seats, gaps). */
    public void add(Player player) {
        Session s = activeSession(player);
        if (s == null) return;
        toggle(player, s, player.getLocation().getBlock());
    }

    public void clear(Player player) {
        Session s = activeSession(player);
        if (s == null) return;
        s.cells.clear();
        player.sendMessage(PREFIX + ChatColor.GREEN + "Huella vaciada (al guardar quedará la columna por defecto del hitbox).");
    }

    private void toggle(Player player, Session s, Block block) {
        Interaction anchor = liveAnchor(player, s);
        if (anchor == null) return;
        FurnitureType type = manager.getRegistry().byId(s.typeId);
        if (type == null) return;

        Block target = FurnitureManager.shellTarget(anchor, type);
        int dx = block.getX() - target.getX();
        int dy = block.getY() - target.getY();
        int dz = block.getZ() - target.getZ();
        if (Math.abs(dx) > MAX_REACH || Math.abs(dy) > MAX_REACH || Math.abs(dz) > MAX_REACH) {
            player.sendMessage(PREFIX + ChatColor.RED + "Ese bloque está demasiado lejos del mueble.");
            return;
        }
        // World-space delta back to local space: rotate by -yaw
        double[] local = FurnitureManager.rotate(dx, dz, -s.yaw);
        String cell = Math.round(local[0]) + "," + dy + "," + Math.round(local[1]);

        if (s.cells.remove(cell)) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Celda " + cell + " quitada ("
                    + s.cells.size() + " en la huella).");
        } else {
            s.cells.add(cell);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Celda " + cell + " añadida ("
                    + s.cells.size() + " en la huella).");
        }
    }

    public void save(Player player) {
        Session s = activeSession(player);
        if (s == null) return;
        Interaction anchor = liveAnchor(player, s);
        if (anchor == null) return;

        File file = new File(plugin.getDataFolder(), "furniture.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String basePath = "furniture." + s.typeId;
        if (!yml.isConfigurationSection(basePath)) {
            player.sendMessage(PREFIX + ChatColor.RED + "'" + s.typeId + "' ya no está en furniture.yml; no se puede guardar.");
            return;
        }

        List<String> out = new ArrayList<>(s.cells);
        yml.set(basePath + ".footprint", out);
        yml.set(basePath + ".solid", true);
        try {
            yml.save(file);
        } catch (Exception e) {
            player.sendMessage(PREFIX + ChatColor.RED + "No se pudo escribir furniture.yml: " + e.getMessage());
            return;
        }

        sessions.remove(player.getUniqueId());
        endDrawTask(s);
        manager.getRegistry().load();
        manager.reshell(anchor);

        player.sendMessage(PREFIX + ChatColor.GREEN + "Guardado: huella de " + out.size()
                + " celda(s) en '" + s.typeId + "' y caparazón re-aplicado a ESTE mueble. "
                + ChatColor.GRAY + "Los demás ya colocados conservan su caparazón hasta recolocarse.");
    }

    // ==================== PREVIEW ====================

    private void startDrawTask(Player player, Session s) {
        s.drawTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !sessions.containsValue(s)) {
                    cancel();
                    return;
                }
                Entity anchorEnt = Bukkit.getEntity(s.anchorId);
                if (!(anchorEnt instanceof Interaction anchor) || !anchor.isValid()) {
                    cancel();
                    return;
                }
                FurnitureType type = manager.getRegistry().byId(s.typeId);
                if (type == null) {
                    cancel();
                    return;
                }
                Block target = FurnitureManager.shellTarget(anchor, type);
                for (String cell : s.cells) {
                    String[] xyz = cell.split(",");
                    double[] rotated = FurnitureManager.rotate(
                            Integer.parseInt(xyz[0]), Integer.parseInt(xyz[2]), s.yaw);
                    int bx = target.getX() + (int) Math.round(rotated[0]);
                    int by = target.getY() + Integer.parseInt(xyz[1]);
                    int bz = target.getZ() + (int) Math.round(rotated[1]);
                    FurnitureVisualizer.drawBox(player, anchor.getWorld(),
                            bx, by, bz, bx + 1, by + 1, bz + 1, WORKING_DUST);
                }
            }
        };
        s.drawTask.runTaskTimer(plugin, 0L, 10L);
    }

    private void endDrawTask(Session s) {
        if (s.drawTask != null) {
            s.drawTask.cancel();
            s.drawTask = null;
        }
    }

    // ==================== HELPERS ====================

    private Session activeSession(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No estás editando una huella. Acércate a un mueble y usa /sf footprint");
        }
        return s;
    }

    private Interaction liveAnchor(Player player, Session s) {
        Entity e = Bukkit.getEntity(s.anchorId);
        if (e instanceof Interaction anchor && anchor.isValid()) {
            return anchor;
        }
        sessions.remove(player.getUniqueId());
        endDrawTask(s);
        player.sendMessage(PREFIX + ChatColor.RED + "El mueble que editabas ya no existe; edición cancelada.");
        return null;
    }
}
