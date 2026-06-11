package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import com.blockdisplay.plugin.ModelData;
import com.blockdisplay.plugin.ModelGroup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime core of the furniture system: placement, pickup, interactions (seat/menu/commands),
 * barriers, and animation binding. Furniture instances are vanilla-persistent entities — they
 * live and save with their chunk; all metadata travels in the PDC of an anchor Interaction
 * entity (type, owner, yaw, part UUIDs, barrier positions). No central registry to respawn.
 */
public class FurnitureManager {

    private final BlockDisplayPlugin plugin;
    private final FurnitureRegistry registry;
    private final PlacementIndex index;
    private final MythicHook mythic;

    // PDC keys
    public final NamespacedKey keyInstance;
    public final NamespacedKey keyType;
    public final NamespacedKey keyOwner;
    public final NamespacedKey keyParts;
    public final NamespacedKey keyBarriers;
    public final NamespacedKey keyYaw;
    public final NamespacedKey keySeat;

    /** Seat armor stands spawned this session (cleaned on disable; they are non-persistent). */
    private final Set<UUID> activeSeats = new HashSet<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();
    /** Menu/command furniture runs arbitrary commands per click — throttled per player. */
    private final Map<UUID, Long> interactCooldown = new HashMap<>();
    private static final long INTERACT_COOLDOWN_MS = 1000;

    public FurnitureManager(BlockDisplayPlugin plugin, FurnitureRegistry registry, PlacementIndex index, MythicHook mythic) {
        this.plugin = plugin;
        this.registry = registry;
        this.index = index;
        this.mythic = mythic;
        this.keyInstance = new NamespacedKey(plugin, "furniture_instance");
        this.keyType = new NamespacedKey(plugin, "furniture_type");
        this.keyOwner = new NamespacedKey(plugin, "furniture_owner");
        this.keyParts = new NamespacedKey(plugin, "furniture_parts");
        this.keyBarriers = new NamespacedKey(plugin, "furniture_barriers");
        this.keyYaw = new NamespacedKey(plugin, "furniture_yaw");
        this.keySeat = new NamespacedKey(plugin, "furniture_seat");
    }

    public FurnitureRegistry getRegistry() { return registry; }
    public PlacementIndex getIndex() { return index; }
    public MythicHook getMythic() { return mythic; }

    // ==================== PLACE ====================

    /** @return true if the furniture was placed (caller consumes the item). */
    public boolean place(FurnitureType type, Player player, Block clicked, BlockFace face, ItemStack inHand) {
        World world = clicked.getWorld();

        if (registry.isWorldDisabled(world.getName())) {
            bar(player, "No se pueden colocar muebles en este mundo.", NamedTextColor.RED);
            return false;
        }
        if (!player.hasPermission("superfurnitures.place")
                || (!type.permission.isBlank() && !player.hasPermission(type.permission))) {
            bar(player, "No tienes permiso para colocar este mueble.", NamedTextColor.RED);
            return false;
        }

        // Anchor face check
        boolean faceOk = switch (type.anchor) {
            case FLOOR -> face == BlockFace.UP;
            case CEILING -> face == BlockFace.DOWN;
            case WALL -> face == BlockFace.NORTH || face == BlockFace.SOUTH
                    || face == BlockFace.EAST || face == BlockFace.WEST;
        };
        if (!faceOk) {
            String where = switch (type.anchor) {
                case FLOOR -> "en el suelo";
                case WALL -> "en una pared";
                case CEILING -> "en el techo";
            };
            bar(player, "Este mueble se coloca " + where + ".", NamedTextColor.YELLOW);
            return false;
        }

        Block target = clicked.getRelative(face);

        // Space check: the target column must be replaceable up to the hitbox height (floor only)
        int heightBlocks = (type.anchor == FurnitureType.Anchor.FLOOR)
                ? Math.max(1, (int) Math.ceil(type.hitboxHeight)) : 1;
        for (int dy = 0; dy < heightBlocks; dy++) {
            if (!target.getRelative(0, dy, 0).isReplaceable()) {
                bar(player, "No hay espacio suficiente aquí.", NamedTextColor.YELLOW);
                return false;
            }
        }

        // Protection probe: a synthetic BlockPlaceEvent lets WorldGuard/Towny/any protection veto
        // the placement without compiling against any of them.
        BlockPlaceEvent probe = new BlockPlaceEvent(target, target.getState(), clicked,
                inHand, player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(probe);
        if (probe.isCancelled() || !probe.canBuild()) {
            bar(player, "No puedes construir aquí.", NamedTextColor.RED);
            return false;
        }

        // Limits
        String ownerStr = player.getUniqueId().toString();
        int playerLimit = limitFor(player);
        if (playerLimit >= 0 && index.countByOwner(ownerStr) >= playerLimit) {
            bar(player, "Límite de muebles alcanzado (" + playerLimit + "). Recoge alguno primero.", NamedTextColor.RED);
            return false;
        }
        int chunkLimit = registry.getPerChunk();
        if (chunkLimit > 0 && index.countInChunk(world.getName(),
                target.getX() >> 4, target.getZ() >> 4) >= chunkLimit) {
            bar(player, "Este chunk ya tiene demasiados muebles (" + chunkLimit + ").", NamedTextColor.RED);
            return false;
        }

        // Anti-spam cooldown
        long now = System.currentTimeMillis();
        Long last = placeCooldown.get(player.getUniqueId());
        if (last != null && now - last < 500) {
            return false;
        }
        placeCooldown.put(player.getUniqueId(), now);

        ModelData modelData = registry.modelData(type.id);
        if (modelData == null) {
            bar(player, "Este mueble está mal configurado (avisa a un admin).", NamedTextColor.RED);
            return false;
        }

        // Origin + rotation
        Location origin = switch (type.anchor) {
            case FLOOR, WALL -> target.getLocation().add(0.5, 0.0, 0.5);
            case CEILING -> target.getLocation().add(0.5, 1.0, 0.5);
        };
        float yaw = (type.anchor == FurnitureType.Anchor.WALL) ? faceYaw(face) : snap90(player.getLocation().getYaw());

        // Spawn the model parts, tagging each with the instance id
        String instanceStr = UUID.randomUUID().toString();
        List<UUID> partIds = ModelGroup.summonModelParts(modelData, origin, plugin, ent -> {
            ent.getPersistentDataContainer().set(keyInstance, PersistentDataType.STRING, instanceStr);
            Location l = ent.getLocation();
            l.setYaw(yaw);
            ent.teleport(l);
        });

        // Barriers (solid furniture)
        String barrierCsv = type.solid ? placeBarriers(type, target, yaw, heightBlocks) : "";

        // Anchor Interaction: the clickable body + metadata carrier
        Interaction anchor = world.spawn(origin, Interaction.class, i -> {
            i.setInteractionWidth(type.hitboxWidth);
            i.setInteractionHeight(type.hitboxHeight);
            i.setResponsive(true);
            PersistentDataContainer pdc = i.getPersistentDataContainer();
            pdc.set(keyInstance, PersistentDataType.STRING, instanceStr);
            pdc.set(keyType, PersistentDataType.STRING, type.id);
            pdc.set(keyOwner, PersistentDataType.STRING, ownerStr);
            pdc.set(keyYaw, PersistentDataType.FLOAT, yaw);
            pdc.set(keyParts, PersistentDataType.STRING, joinUuids(partIds));
            if (!barrierCsv.isEmpty()) {
                pdc.set(keyBarriers, PersistentDataType.STRING, barrierCsv);
            }
        });

        index.add(instanceStr, new PlacementIndex.Placement(
                type.id, ownerStr, world.getName(), origin.getX(), origin.getY(), origin.getZ()));

        // Animation: parts register over 1-3 ticks; bind once they are all live
        if (type.animated && modelData.hasAnimations()) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> bindAnimation(anchor), 4L);
        }

        playSound(world, origin, type.placeSound);
        bar(player, "Mueble colocado.", NamedTextColor.GREEN);
        return true;
    }

    private String placeBarriers(FurnitureType type, Block target, float yaw, int heightBlocks) {
        List<Block> blocks = new ArrayList<>();
        if (type.footprint.isEmpty()) {
            for (int dy = 0; dy < heightBlocks; dy++) {
                blocks.add(target.getRelative(0, dy, 0));
            }
        } else {
            for (int[] off : type.footprint) {
                double[] rotated = rotate(off[0], off[2], yaw);
                blocks.add(target.getRelative(
                        (int) Math.round(rotated[0]), off[1], (int) Math.round(rotated[1])));
            }
        }
        StringBuilder csv = new StringBuilder();
        for (Block b : blocks) {
            if (b.isReplaceable()) {
                b.setType(Material.BARRIER);
                if (csv.length() > 0) csv.append(';');
                csv.append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ());
            }
        }
        return csv.toString();
    }

    // ==================== PICKUP ====================

    public void pickup(Player player, Interaction anchor) {
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        String instanceStr = pdc.get(keyInstance, PersistentDataType.STRING);
        String ownerStr = pdc.get(keyOwner, PersistentDataType.STRING);
        if (instanceStr == null) return;

        if (ownerStr != null && !ownerStr.equals(player.getUniqueId().toString())
                && !player.hasPermission("superfurnitures.admin.bypass")) {
            bar(player, "Este mueble no es tuyo.", NamedTextColor.RED);
            return;
        }

        World world = anchor.getWorld();
        Location loc = anchor.getLocation();
        String typeId = removeFurniture(anchor);
        FurnitureType type = registry.byId(typeId);

        if (type == null) {
            bar(player, "Este mueble fue eliminado del catálogo: se retira sin devolver item.", NamedTextColor.YELLOW);
        } else {
            ItemStack item = mythic.getItem(type.mythicItem);
            if (item != null) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                for (ItemStack rest : leftover.values()) {
                    world.dropItemNaturally(player.getLocation(), rest);
                }
            } else {
                bar(player, "El item de este mueble ya no existe en MythicMobs (avisa a un admin).", NamedTextColor.YELLOW);
            }
            playSound(world, loc, type.pickupSound);
            bar(player, "Mueble recogido.", NamedTextColor.GREEN);
        }
    }

    /**
     * Tear a furniture instance down completely: parts, seats, barriers, animation binding,
     * index entry and the anchor itself. No ownership checks, no item back — callers decide that.
     *
     * @return the furniture type id the anchor carried (may be unregistered/orphan).
     */
    public String removeFurniture(Interaction anchor) {
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        String instanceStr = pdc.get(keyInstance, PersistentDataType.STRING);
        String typeId = pdc.get(keyType, PersistentDataType.STRING);
        if (instanceStr == null) {
            anchor.remove();
            return typeId;
        }

        World world = anchor.getWorld();
        Location loc = anchor.getLocation();

        // Remove tracked parts
        String partsCsv = pdc.get(keyParts, PersistentDataType.STRING);
        if (partsCsv != null && !partsCsv.isEmpty()) {
            for (String idStr : partsCsv.split(",")) {
                try {
                    Entity part = Bukkit.getEntity(UUID.fromString(idStr));
                    if (part != null) {
                        part.eject();
                        part.remove();
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        // Safety sweep: anything else carrying this instance id near the anchor (seats, hitboxes)
        for (Entity near : world.getNearbyEntities(loc, 4, 4, 4)) {
            String nearInstance = near.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING);
            if (instanceStr.equals(nearInstance) && near != anchor) {
                near.eject();
                near.remove();
                activeSeats.remove(near.getUniqueId());
            }
        }

        // Barriers back to air (only if still barriers)
        String barrierCsv = pdc.get(keyBarriers, PersistentDataType.STRING);
        if (barrierCsv != null && !barrierCsv.isEmpty()) {
            for (String pos : barrierCsv.split(";")) {
                String[] xyz = pos.split(",");
                Block b = world.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                if (b.getType() == Material.BARRIER) {
                    b.setType(Material.AIR);
                }
            }
        }

        // Unbind animation
        try {
            unbindAnimation(UUID.fromString(instanceStr));
        } catch (IllegalArgumentException ignored) {
        }

        index.remove(instanceStr);
        anchor.eject();
        anchor.remove();
        return typeId;
    }

    /**
     * Admin: remove ALL furniture of a player, loading chunks as needed. Entries whose anchor
     * is gone (killed by hand) are pruned from the index.
     *
     * @return [furniture removed, stale index entries pruned]
     */
    public int[] purgeOwner(String ownerUuid) {
        int removed = 0;
        int pruned = 0;
        for (Map.Entry<String, PlacementIndex.Placement> entry : index.byOwnerWithIds(ownerUuid).entrySet()) {
            String instance = entry.getKey();
            PlacementIndex.Placement p = entry.getValue();
            World world = Bukkit.getWorld(p.world());
            if (world == null) {
                index.remove(instance);
                pruned++;
                continue;
            }
            // Paper loads chunk entities on demand for getEntities()
            org.bukkit.Chunk chunk = world.getChunkAt(((int) Math.floor(p.x())) >> 4, ((int) Math.floor(p.z())) >> 4);
            Interaction anchor = null;
            for (Entity ent : chunk.getEntities()) {
                if (ent instanceof Interaction i
                        && instance.equals(i.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING))) {
                    anchor = i;
                    break;
                }
            }
            if (anchor != null) {
                removeFurniture(anchor);
                removed++;
            } else {
                index.remove(instance);
                pruned++;
            }
        }
        return new int[]{removed, pruned};
    }

    // ==================== INTERACT ====================

    public void interact(Player player, Interaction anchor) {
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        String typeId = pdc.get(keyType, PersistentDataType.STRING);
        FurnitureType type = registry.byId(typeId);

        if (type == null) {
            bar(player, "Este mueble fue eliminado del catálogo. Agáchate + clic derecho con la mano vacía para retirarlo.", NamedTextColor.YELLOW);
            return;
        }

        switch (type.interactionType) {
            case SEAT -> seat(player, anchor, type);
            case MENU -> {
                if (interactThrottled(player)) return;
                if (!type.menu.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "dm open " + type.menu + " " + player.getName());
                }
            }
            case COMMANDS -> {
                if (interactThrottled(player)) return;
                for (String entry : type.commands) {
                    String cmd = entry;
                    boolean console = false;
                    if (cmd.regionMatches(true, 0, "console:", 0, 8)) {
                        console = true;
                        cmd = cmd.substring(8).trim();
                    } else if (cmd.regionMatches(true, 0, "player:", 0, 7)) {
                        cmd = cmd.substring(7).trim();
                    }
                    cmd = cmd.replace("{player}", player.getName());
                    Bukkit.dispatchCommand(console ? Bukkit.getConsoleSender() : player, cmd);
                }
            }
            case NONE -> { }
        }
    }

    /** @return true (and swallow the click) if the player clicked a command/menu furniture too fast. */
    private boolean interactThrottled(Player player) {
        long now = System.currentTimeMillis();
        Long last = interactCooldown.get(player.getUniqueId());
        if (last != null && now - last < INTERACT_COOLDOWN_MS) {
            return true;
        }
        interactCooldown.put(player.getUniqueId(), now);
        return false;
    }

    private void seat(Player player, Interaction anchor, FurnitureType type) {
        if (player.getVehicle() != null) return;
        Float yawObj = anchor.getPersistentDataContainer().get(keyYaw, PersistentDataType.FLOAT);
        float yaw = yawObj != null ? yawObj : 0f;
        String instanceStr = anchor.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING);
        Location base = anchor.getLocation();

        for (double[] offset : type.seats) {
            double[] rotated = rotate(offset[0], offset[2], yaw);
            Location seatLoc = base.clone().add(rotated[0], offset[1], rotated[1]);
            seatLoc.setYaw(yaw);

            // Occupied? an existing seat stand within 0.3 blocks with a passenger
            boolean occupied = false;
            for (Entity near : base.getWorld().getNearbyEntities(seatLoc, 0.3, 0.3, 0.3)) {
                if (near.getPersistentDataContainer().has(keySeat, PersistentDataType.BYTE)) {
                    if (!near.getPassengers().isEmpty()) {
                        occupied = true;
                    } else {
                        near.remove();
                        activeSeats.remove(near.getUniqueId());
                    }
                    break;
                }
            }
            if (occupied) continue;

            ArmorStand stand = base.getWorld().spawn(seatLoc, ArmorStand.class, as -> {
                as.setInvisible(true);
                as.setMarker(true);
                as.setGravity(false);
                as.setSmall(true);
                as.setPersistent(false); // never saved with the chunk; crash leaves nothing behind
                PersistentDataContainer sp = as.getPersistentDataContainer();
                sp.set(keySeat, PersistentDataType.BYTE, (byte) 1);
                if (instanceStr != null) {
                    sp.set(keyInstance, PersistentDataType.STRING, instanceStr);
                }
            });
            stand.addPassenger(player);
            activeSeats.add(stand.getUniqueId());
            return;
        }
        bar(player, "No quedan sitios libres.", NamedTextColor.YELLOW);
    }

    /**
     * Vanilla's dismount placement doesn't know about our barrier shells: getting off a seat
     * inside a solid furniture can leave the player suffocating inside a barrier. If the spot
     * the player landed on isn't free, lift them to the first open spot above (furniture is
     * at most a few blocks tall).
     */
    public void rescueFromSuffocation(Player player) {
        Location loc = player.getLocation();
        if (isFreeStandingSpot(loc)) return;
        for (int dy = 1; dy <= 4; dy++) {
            Location up = loc.clone().add(0, dy, 0);
            if (isFreeStandingSpot(up)) {
                player.teleport(up);
                return;
            }
        }
    }

    private static boolean isFreeStandingSpot(Location loc) {
        Block feet = loc.getBlock();
        return feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable();
    }

    /** Called on dismount/quit: remove the now-empty seat stand. */
    public void releaseSeat(Entity stand) {
        activeSeats.remove(stand.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (stand.isValid()) stand.remove();
        });
    }

    public void removeAllSeats() {
        for (UUID id : new ArrayList<>(activeSeats)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.eject();
                e.remove();
            }
        }
        activeSeats.clear();
    }

    // ==================== ANIMATION BINDING ====================

    /**
     * Adopt the live entities of an animated furniture instance into a lightweight ModelGroup
     * and register it with the animation engine. Used right after placing and on chunk load.
     */
    public void bindAnimation(Interaction anchor) {
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        String instanceStr = pdc.get(keyInstance, PersistentDataType.STRING);
        String typeId = pdc.get(keyType, PersistentDataType.STRING);
        if (instanceStr == null || typeId == null) return;

        FurnitureType type = registry.byId(typeId);
        if (type == null || !type.animated) return;
        ModelData modelData = registry.modelData(typeId);
        if (modelData == null || !modelData.hasAnimations()) return;

        UUID instance;
        try {
            instance = UUID.fromString(instanceStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (plugin.getFurnitureAnimGroups().containsKey(instance)) return;

        List<Entity> members = new ArrayList<>();
        for (Entity near : anchor.getWorld().getNearbyEntities(anchor.getLocation(), 4, 4, 4)) {
            if (instanceStr.equals(near.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING))) {
                members.add(near);
            }
        }
        if (members.isEmpty()) return;

        ModelGroup group = new ModelGroup(anchor.getLocation(), instance, type.model, "mueble:" + typeId);
        group.setAdminManaged(false);
        group.adopt(modelData, members);
        group.setAnimating(true);
        group.setLoopAnim(true);
        plugin.getFurnitureAnimGroups().put(instance, group);
    }

    public void unbindAnimation(UUID instance) {
        if (plugin.getFurnitureAnimGroups().remove(instance) != null) {
            plugin.getAnimationManager().removeGroup(instance);
        }
    }

    // ==================== HELPERS ====================

    /** Highest superfurnitures.limit.N the player carries, or the config default. -1 = unlimited. */
    private int limitFor(Player player) {
        int limit = registry.getPerPlayerDefault();
        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission();
            if (pai.getValue() && perm.startsWith("superfurnitures.limit.")) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring("superfurnitures.limit.".length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return limit;
    }

    /** Rotate a local-space (x, z) offset (+z = model facing) by the furniture yaw. */
    private static double[] rotate(double lx, double lz, float yaw) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{lx * cos - lz * sin, lx * sin + lz * cos};
    }

    private static float snap90(float yaw) {
        return Math.floorMod(Math.round(yaw / 90f) * 90, 360);
    }

    private static float faceYaw(BlockFace face) {
        return switch (face) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f; // SOUTH
        };
    }

    private static String joinUuids(List<UUID> ids) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private void playSound(World world, Location loc, String sound) {
        if (sound == null || sound.isBlank()) return;
        try {
            world.playSound(loc, sound, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    void bar(Player player, String message, NamedTextColor color) {
        player.sendActionBar(Component.text(message, color));
    }
}
