package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * In-game seat editor (/sf seats): WYSIWYG previews of where players would actually sit.
 * Each working seat offset spawns a marker armor stand with a MANNEQUIN mounted on it —
 * the client renders the mannequin in the seated pose, exactly like a real player would
 * look. The admin stands where they want a seat, adds it, nudges it while watching the
 * mannequin, and saves: offsets are written to furniture.yml (interaction.seats) and the
 * registry reloads, so every placed unit of that type picks the new seats up immediately.
 *
 * MANNEQUIN is resolved at runtime by name (the server runs a newer MC than the compile
 * API); if the runtime doesn't have it, an armor stand with arms is the stand-in.
 */
public class SeatEditor {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "SF" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
    /** Sitting drops a player about this far below where they stand; "add where I stand" feels right with it. */
    private static final double SIT_DROP = 0.45;
    private static final double FIND_RADIUS = 5.0;

    private final BlockDisplayPlugin plugin;
    private final FurnitureManager manager;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static class Session {
        UUID anchorId;
        String typeId;
        float yaw;
        final List<double[]> seats = new ArrayList<>();
        final List<UUID> previewIds = new ArrayList<>();
    }

    public SeatEditor(BlockDisplayPlugin plugin, FurnitureManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean hasSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ==================== SESSION LIFECYCLE ====================

    public void start(Player player) {
        if (hasSession(player)) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Ya estás editando asientos. /sf seats save o /sf seats cancel primero.");
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
            player.sendMessage(PREFIX + ChatColor.RED + "Ese mueble ya no está en el catálogo (furniture.yml); no se puede editar.");
            return;
        }
        Float yawObj = anchor.getPersistentDataContainer().get(manager.keyYaw, PersistentDataType.FLOAT);

        Session s = new Session();
        s.anchorId = anchor.getUniqueId();
        s.typeId = type.id;
        s.yaw = yawObj != null ? yawObj : 0f;
        for (double[] seat : type.seats) {
            s.seats.add(seat.clone());
        }
        sessions.put(player.getUniqueId(), s);
        respawnPreviews(s);
        FurnitureVisualizer.show(plugin, manager, player, anchor);

        player.sendMessage(PREFIX + ChatColor.GOLD + "Editando asientos de '" + type.id + "' ("
                + s.seats.size() + " actual(es), maniquís de preview).");
        player.sendMessage(ChatColor.GRAY + " Ponte de pie donde quieras el asiento y usa "
                + ChatColor.YELLOW + "/sf seats add");
        player.sendMessage(ChatColor.GRAY + " Ajusta con " + ChatColor.YELLOW + "/sf seats move <n> <x|y|z> <delta>"
                + ChatColor.GRAY + ", borra con " + ChatColor.YELLOW + "/sf seats remove <n>");
        player.sendMessage(ChatColor.GRAY + " Termina con " + ChatColor.YELLOW + "/sf seats save"
                + ChatColor.GRAY + " o " + ChatColor.YELLOW + "/sf seats cancel");
    }

    public void cancel(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "No estás editando asientos.");
            return;
        }
        clearPreviews(s);
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Edición cancelada, sin cambios.");
    }

    /** Silent cleanup (quit/disable). */
    public void discard(UUID playerId) {
        Session s = sessions.remove(playerId);
        if (s != null) clearPreviews(s);
    }

    public void discardAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            clearPreviews(s);
        }
        sessions.clear();
    }

    // ==================== EDIT OPERATIONS ====================

    public void add(Player player) {
        Session s = activeSession(player);
        if (s == null) return;
        Interaction anchor = liveAnchor(player, s);
        if (anchor == null) return;

        Location base = anchor.getLocation();
        Location at = player.getLocation();
        double dx = at.getX() - base.getX();
        double dy = at.getY() - base.getY() - SIT_DROP;
        double dz = at.getZ() - base.getZ();
        // World-space delta back to the furniture's local space: rotate by -yaw
        double[] local = FurnitureManager.rotate(dx, dz, -s.yaw);

        // The admin's OWN facing (snapped to 45°) becomes the seat's facing: look where the
        // seated player should look, then add. 0 = furniture facing → stored as 3 components.
        float relYaw = Math.round(normalize(at.getYaw() - s.yaw) / 45f) * 45f % 360f;
        double[] seat = (relYaw == 0f)
                ? new double[]{round2(local[0]), round2(dy), round2(local[1])}
                : new double[]{round2(local[0]), round2(dy), round2(local[1]), relYaw};

        s.seats.add(seat);
        respawnPreviews(s);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Asiento " + s.seats.size() + " añadido donde estás"
                + (relYaw != 0f ? " mirando a " + (int) relYaw + "° del mueble" : "") + ". "
                + ChatColor.GRAY + "Mira el maniquí y ajusta con /sf seats move " + s.seats.size() + " ...");
    }

    public void move(Player player, int n, String axis, double delta) {
        Session s = activeSession(player);
        if (s == null) return;
        if (liveAnchor(player, s) == null) return;
        if (n < 1 || n > s.seats.size()) {
            player.sendMessage(PREFIX + ChatColor.RED + "No existe el asiento " + n + " (hay " + s.seats.size() + ").");
            return;
        }
        int idx = switch (axis.toLowerCase(Locale.ROOT)) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            case "yaw" -> 3;
            default -> -1;
        };
        if (idx < 0) {
            player.sendMessage(PREFIX + ChatColor.RED + "Eje inválido: usa x, y, z (locales; +z = frente) o yaw (grados).");
            return;
        }
        double[] seat = s.seats.get(n - 1);
        if (idx == 3 && seat.length < 4) {
            seat = new double[]{seat[0], seat[1], seat[2], 0};
            s.seats.set(n - 1, seat);
        }
        seat[idx] = (idx == 3)
                ? (((seat[idx] + delta) % 360) + 360) % 360
                : round2(seat[idx] + delta);
        respawnPreviews(s);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Asiento " + n + " → " + describe(seat));
    }

    public void remove(Player player, int n) {
        Session s = activeSession(player);
        if (s == null) return;
        if (liveAnchor(player, s) == null) return;
        if (n < 1 || n > s.seats.size()) {
            player.sendMessage(PREFIX + ChatColor.RED + "No existe el asiento " + n + " (hay " + s.seats.size() + ").");
            return;
        }
        s.seats.remove(n - 1);
        respawnPreviews(s);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Asiento " + n + " eliminado (quedan " + s.seats.size() + ").");
    }

    public void list(Player player) {
        Session s = activeSession(player);
        if (s == null) return;
        if (s.seats.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Sin asientos (al guardar, el tipo dejará el asiento por defecto 0, 0.4, 0).");
            return;
        }
        player.sendMessage(PREFIX + ChatColor.GOLD + "Asientos de '" + s.typeId + "' (offsets locales, +z = frente):");
        for (int i = 0; i < s.seats.size(); i++) {
            player.sendMessage(ChatColor.WHITE + " " + (i + 1) + ChatColor.DARK_GRAY + ": " + ChatColor.GRAY
                    + describe(s.seats.get(i)));
        }
    }

    public void save(Player player) {
        Session s = activeSession(player);
        if (s == null) return;

        File file = new File(plugin.getDataFolder(), "furniture.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String basePath = "furniture." + s.typeId;
        if (!yml.isConfigurationSection(basePath)) {
            player.sendMessage(PREFIX + ChatColor.RED + "'" + s.typeId + "' ya no está en furniture.yml; no se puede guardar.");
            return;
        }

        List<String> out = new ArrayList<>();
        for (double[] seat : s.seats) {
            String line = fmt(seat[0]) + ", " + fmt(seat[1]) + ", " + fmt(seat[2]);
            if (seat.length > 3 && seat[3] != 0) {
                line += ", " + fmt(seat[3]);
            }
            out.add(line);
        }
        yml.set(basePath + ".interaction.seats", out);
        boolean typeChanged = false;
        if (!"seat".equalsIgnoreCase(yml.getString(basePath + ".interaction.type", "none"))) {
            yml.set(basePath + ".interaction.type", "seat");
            typeChanged = true;
        }
        try {
            yml.save(file);
        } catch (Exception e) {
            player.sendMessage(PREFIX + ChatColor.RED + "No se pudo escribir furniture.yml: " + e.getMessage());
            return;
        }

        sessions.remove(player.getUniqueId());
        clearPreviews(s);
        manager.getRegistry().load();

        player.sendMessage(PREFIX + ChatColor.GREEN + "Guardado: " + s.seats.size() + " asiento(s) en '" + s.typeId
                + "'" + (typeChanged ? " (interaction.type → seat)" : "")
                + ChatColor.GRAY + " — aplica ya a todos los colocados de ese tipo.");
    }

    // ==================== PREVIEWS ====================

    private void respawnPreviews(Session s) {
        clearPreviews(s);
        Entity anchorEnt = Bukkit.getEntity(s.anchorId);
        if (!(anchorEnt instanceof Interaction anchor)) return;
        World world = anchor.getWorld();
        Location base = anchor.getLocation();

        int n = 1;
        for (double[] seat : s.seats) {
            double[] rotated = FurnitureManager.rotate(seat[0], seat[2], s.yaw);
            Location seatLoc = base.clone().add(rotated[0], seat[1], rotated[1]);
            seatLoc.setYaw(FurnitureManager.seatYaw(s.yaw, seat));

            ArmorStand stand = world.spawn(seatLoc, ArmorStand.class, as -> {
                as.setInvisible(true);
                as.setMarker(true);
                as.setGravity(false);
                as.setSmall(true);
                as.setPersistent(false);
            });
            Entity dummy = spawnMannequin(world, seatLoc, n);
            stand.addPassenger(dummy);
            s.previewIds.add(stand.getUniqueId());
            s.previewIds.add(dummy.getUniqueId());
            n++;
        }
    }

    private Entity spawnMannequin(World world, Location loc, int n) {
        Entity dummy;
        try {
            // Runtime-only entity: the compile-time API predates MANNEQUIN
            dummy = world.spawnEntity(loc, EntityType.valueOf("MANNEQUIN"));
        } catch (IllegalArgumentException noMannequin) {
            dummy = world.spawn(loc, ArmorStand.class, as -> {
                as.setArms(true);
                as.setBasePlate(false);
            });
        }
        dummy.setInvulnerable(true);
        dummy.setSilent(true);
        dummy.setPersistent(false);
        dummy.setGravity(false);
        dummy.customName(Component.text("Asiento " + n, NamedTextColor.AQUA));
        dummy.setCustomNameVisible(true);
        return dummy;
    }

    private void clearPreviews(Session s) {
        for (UUID id : s.previewIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.eject();
                e.remove();
            }
        }
        s.previewIds.clear();
    }

    // ==================== HELPERS ====================

    private Session activeSession(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No estás editando asientos. Acércate a un mueble y usa /sf seats");
        }
        return s;
    }

    /** The anchor must still exist (the furniture could have been picked up mid-edit). */
    private Interaction liveAnchor(Player player, Session s) {
        Entity e = Bukkit.getEntity(s.anchorId);
        if (e instanceof Interaction anchor && anchor.isValid()) {
            return anchor;
        }
        sessions.remove(player.getUniqueId());
        clearPreviews(s);
        player.sendMessage(PREFIX + ChatColor.RED + "El mueble que editabas ya no existe; edición cancelada.");
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static float normalize(float yaw) {
        return ((yaw % 360f) + 360f) % 360f;
    }

    private static String describe(double[] seat) {
        String out = fmt(seat[0]) + ", " + fmt(seat[1]) + ", " + fmt(seat[2]);
        if (seat.length > 3 && seat[3] != 0) {
            out += "  (yaw +" + fmt(seat[3]) + "°)";
        }
        return out;
    }

    private static String fmt(double v) {
        String out = String.format(Locale.ROOT, "%.2f", v);
        return out.endsWith(".00") ? out.substring(0, out.length() - 3) : out;
    }
}
