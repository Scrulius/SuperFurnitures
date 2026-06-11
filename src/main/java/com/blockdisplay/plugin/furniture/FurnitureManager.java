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
import java.util.Comparator;
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

    /** Sentinel owner of admin-placed server furniture: never matches a player UUID, so only
     *  admins with the bypass permission can pick it up or rotate it. */
    public static final String SERVER_OWNER = "server";

    private final BlockDisplayPlugin plugin;
    private final FurnitureRegistry registry;
    private final PlacementIndex index;
    private final MythicHook mythic;
    private final FurnitureItems items;
    private final FurnitureAudit audit;

    // PDC keys
    public final NamespacedKey keyInstance;
    public final NamespacedKey keyType;
    public final NamespacedKey keyOwner;
    public final NamespacedKey keyParts;
    public final NamespacedKey keyBarriers;
    public final NamespacedKey keyYaw;
    public final NamespacedKey keySeat;
    public final NamespacedKey keyPreview;

    /** Seat armor stands spawned this session (cleaned on disable; they are non-persistent). */
    private final Set<UUID> activeSeats = new HashSet<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();
    /** Menu/command furniture runs arbitrary commands per click — throttled per player. */
    private final Map<UUID, Long> interactCooldown = new HashMap<>();
    private static final long INTERACT_COOLDOWN_MS = 1000;
    private final Map<UUID, Long> rotateCooldown = new HashMap<>();

    public FurnitureManager(BlockDisplayPlugin plugin, FurnitureRegistry registry, PlacementIndex index,
                            MythicHook mythic, FurnitureItems items, FurnitureAudit audit) {
        this.plugin = plugin;
        this.registry = registry;
        this.index = index;
        this.mythic = mythic;
        this.items = items;
        this.audit = audit;
        this.keyInstance = new NamespacedKey(plugin, "furniture_instance");
        this.keyType = new NamespacedKey(plugin, "furniture_type");
        this.keyOwner = new NamespacedKey(plugin, "furniture_owner");
        this.keyParts = new NamespacedKey(plugin, "furniture_parts");
        this.keyBarriers = new NamespacedKey(plugin, "furniture_barriers");
        this.keyYaw = new NamespacedKey(plugin, "furniture_yaw");
        this.keySeat = new NamespacedKey(plugin, "furniture_seat");
        this.keyPreview = new NamespacedKey(plugin, "furniture_preview");
        startSeatWatchdog();
    }

    /**
     * Failsafe of last resort for seat stands: an empty (rider-less) seat must never outlive
     * its rider by more than ~2s, no matter which dismount/removal path leaked it (cancelled
     * dismount events, failed addPassenger, plugin interference...). The set is tiny — only
     * stands spawned this session — so the sweep is effectively free.
     */
    private void startSeatWatchdog() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (activeSeats.isEmpty()) return;
            for (UUID id : new ArrayList<>(activeSeats)) {
                Entity stand = Bukkit.getEntity(id);
                if (stand == null) {
                    activeSeats.remove(id);
                } else if (stand.getPassengers().isEmpty()) {
                    stand.remove();
                    activeSeats.remove(id);
                }
            }
        }, 40L, 40L);
    }

    public FurnitureRegistry getRegistry() { return registry; }
    public PlacementIndex getIndex() { return index; }
    public MythicHook getMythic() { return mythic; }
    public FurnitureItems getItems() { return items; }
    public FurnitureAudit getAudit() { return audit; }

    /**
     * The placeable item of a furniture type: native (built by the plugin) when the type defines
     * an item spec, the MythicMobs item otherwise. Null only if the MM item no longer resolves.
     */
    public ItemStack itemFor(FurnitureType type) {
        if (type == null) return null;
        if (type.item != null) return items.build(type);
        return mythic.getItem(type.mythicItem);
    }

    // ==================== PLACE ====================

    /** @return true if the furniture was placed (caller consumes the item). */
    public boolean place(FurnitureType type, Player player, Block clicked, BlockFace face) {
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
        int heightBlocks = (type.anchor == FurnitureType.Anchor.FLOOR)
                ? Math.max(1, (int) Math.ceil(type.hitboxHeight)) : 1;
        float yaw = (type.anchor == FurnitureType.Anchor.WALL) ? faceYaw(face) : snap90(player.getLocation().getYaw());

        // Space check: solid furniture must fit its WHOLE shell (a custom footprint can be an
        // L or a 2-wide sofa); non-solid (or default shell) checks the origin column.
        List<Block> shellCells = type.solid ? shellBlocks(type, target, yaw, heightBlocks) : null;
        if (shellCells != null && !type.footprint.isEmpty()) {
            for (Block cell : shellCells) {
                if (!cell.isReplaceable()) {
                    bar(player, "No hay espacio suficiente aquí.", NamedTextColor.YELLOW);
                    return false;
                }
            }
        } else {
            for (int dy = 0; dy < heightBlocks; dy++) {
                if (!target.getRelative(0, dy, 0).isReplaceable()) {
                    bar(player, "No hay espacio suficiente aquí.", NamedTextColor.YELLOW);
                    return false;
                }
            }
        }

        // Protection probe: a synthetic BlockPlaceEvent lets WorldGuard/Towny/any protection veto
        // the placement without compiling against any of them. Every shell cell is probed — a
        // footprint reaching into someone else's region must be vetoed cell by cell, not only
        // at the clicked block.
        if (!canBuildAt(player, target, clicked)) {
            bar(player, "No puedes construir aquí.", NamedTextColor.RED);
            return false;
        }
        if (shellCells != null) {
            for (Block cell : shellCells) {
                if (!cell.equals(target) && !canBuildAt(player, cell, clicked)) {
                    bar(player, "El mueble invade una zona donde no puedes construir.", NamedTextColor.RED);
                    return false;
                }
            }
        }

        // Limits
        String ownerStr = player.getUniqueId().toString();
        int playerLimit = limitFor(player);
        if (playerLimit >= 0 && index.countByOwner(ownerStr) >= playerLimit) {
            bar(player, "Límite de muebles alcanzado (" + playerLimit + "). Recoge alguno primero.", NamedTextColor.RED);
            return false;
        }
        if (type.maxPerPlayer >= 0 && index.countByOwnerAndType(ownerStr, type.id) >= type.maxPerPlayer) {
            bar(player, "Ya tienes el máximo de este mueble (" + type.maxPerPlayer + ").", NamedTextColor.RED);
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

        String instanceStr = spawnAt(type, target, yaw, ownerStr);
        if (instanceStr == null) {
            bar(player, "Este mueble está mal configurado (avisa a un admin).", NamedTextColor.RED);
            return false;
        }
        audit.log("PLACE", player, type.id, instanceStr, target.getLocation());
        String quota = (playerLimit < 0) ? "" : " (" + index.countByOwner(ownerStr) + "/" + playerLimit + ")";
        bar(player, "Mueble colocado." + quota, NamedTextColor.GREEN);
        return true;
    }

    /**
     * Admin: place SERVER furniture — owner sentinel "server", so it counts against no player
     * limit and only bypass admins can pick it up or rotate it, while interactions (seats,
     * menus, commands) work for everyone. Skips permission/protection/limit checks (it's the
     * admin shaping the server), keeps the physical ones (anchor face + space).
     */
    public boolean placeServer(FurnitureType type, Player admin, Block clicked, BlockFace face) {
        boolean faceOk = switch (type.anchor) {
            case FLOOR -> face == BlockFace.UP;
            case CEILING -> face == BlockFace.DOWN;
            case WALL -> face == BlockFace.NORTH || face == BlockFace.SOUTH
                    || face == BlockFace.EAST || face == BlockFace.WEST;
        };
        if (!faceOk) {
            String where = switch (type.anchor) {
                case FLOOR -> "el suelo";
                case WALL -> "una pared";
                case CEILING -> "el techo";
            };
            bar(admin, "Este mueble ancla en " + where + ": apunta a esa cara.", NamedTextColor.YELLOW);
            return false;
        }
        Block target = clicked.getRelative(face);
        int heightBlocks = (type.anchor == FurnitureType.Anchor.FLOOR)
                ? Math.max(1, (int) Math.ceil(type.hitboxHeight)) : 1;
        float yaw = (type.anchor == FurnitureType.Anchor.WALL) ? faceYaw(face) : snap90(admin.getLocation().getYaw());
        List<Block> shellCells = type.solid ? shellBlocks(type, target, yaw, heightBlocks) : null;
        List<Block> toCheck = (shellCells != null && !type.footprint.isEmpty()) ? shellCells : null;
        if (toCheck != null) {
            for (Block cell : toCheck) {
                if (!cell.isReplaceable()) {
                    bar(admin, "No hay espacio suficiente aquí.", NamedTextColor.YELLOW);
                    return false;
                }
            }
        } else {
            for (int dy = 0; dy < heightBlocks; dy++) {
                if (!target.getRelative(0, dy, 0).isReplaceable()) {
                    bar(admin, "No hay espacio suficiente aquí.", NamedTextColor.YELLOW);
                    return false;
                }
            }
        }

        String instanceStr = spawnAt(type, target, yaw, SERVER_OWNER);
        if (instanceStr == null) {
            bar(admin, "Este mueble está mal configurado (modelo ausente).", NamedTextColor.RED);
            return false;
        }
        audit.log("PLACE_SERVER", admin, type.id, instanceStr, target.getLocation());
        bar(admin, "Mueble de SERVIDOR colocado (solo un admin puede recogerlo).", NamedTextColor.GREEN);
        return true;
    }

    /**
     * Shared spawn body: model parts, barriers, anchor Interaction with all the PDC metadata,
     * index entry, animation binding and place sound. All validation is on the callers.
     *
     * @return the new instance id, or null if the type's model is missing.
     */
    private String spawnAt(FurnitureType type, Block target, float yaw, String ownerStr) {
        World world = target.getWorld();
        ModelData modelData = registry.modelData(type.id);
        if (modelData == null) {
            return null;
        }
        int heightBlocks = (type.anchor == FurnitureType.Anchor.FLOOR)
                ? Math.max(1, (int) Math.ceil(type.hitboxHeight)) : 1;

        Location origin = switch (type.anchor) {
            case FLOOR, WALL -> target.getLocation().add(0.5, 0.0, 0.5);
            case CEILING -> target.getLocation().add(0.5, 1.0, 0.5);
        };

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
        return instanceStr;
    }

    /** The blocks a solid furniture's shell occupies: its footprint (yaw-rotated) or the origin column. */
    private static List<Block> shellBlocks(FurnitureType type, Block target, float yaw, int heightBlocks) {
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
        return blocks;
    }

    private String placeBarriers(FurnitureType type, Block target, float yaw, int heightBlocks) {
        StringBuilder csv = new StringBuilder();
        for (Block b : shellBlocks(type, target, yaw, heightBlocks)) {
            if (b.isReplaceable()) {
                b.setType(Material.BARRIER);
                if (csv.length() > 0) csv.append(';');
                csv.append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ());
            }
        }
        return csv.toString();
    }

    /** Synthetic BlockPlaceEvent probe: any protection plugin can veto without a compile dep. */
    private static boolean canBuildAt(Player player, Block cell, Block clicked) {
        // The probe must NOT carry the real furniture item: MythicCrucible cancels any
        // BlockPlaceEvent whose in-hand item is a mythic item without Options.Placeable,
        // which would veto every placement. Protection plugins judge player + location,
        // not the item, so a neutral stone keeps the probe honest.
        BlockPlaceEvent probe = new BlockPlaceEvent(cell, cell.getState(), clicked,
                new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(probe);
        return !probe.isCancelled() && probe.canBuild();
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
        audit.log("PICKUP", player, typeId != null ? typeId : "?", instanceStr, loc);

        if (type == null) {
            bar(player, "Este mueble fue eliminado del catálogo: se retira sin devolver item.", NamedTextColor.YELLOW);
        } else {
            ItemStack item = itemFor(type);
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
        // Safety sweep: anything else carrying this instance id near the anchor (seats, hitboxes),
        // plus session debris that sits ON the furniture but is only tracked in memory — seat
        // editor previews (keyPreview: mannequin + its stand) and untagged/stale seat stands.
        // Without this, picking up a chair mid-edit left a killable mannequin floating there.
        for (Entity near : world.getNearbyEntities(loc, 4, 4, 4)) {
            if (near == anchor) continue;
            PersistentDataContainer nearPdc = near.getPersistentDataContainer();
            String nearInstance = nearPdc.get(keyInstance, PersistentDataType.STRING);
            boolean debris = nearPdc.has(keyPreview, PersistentDataType.BYTE)
                    || (nearPdc.has(keySeat, PersistentDataType.BYTE) && nearInstance == null);
            if (instanceStr.equals(nearInstance) || debris) {
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

        // Radius-independent seat teardown: every tracked seat of this instance dies with the
        // furniture, wherever it ended up (the 4-block sweep above could miss a stand that
        // drifted or whose chunk geometry got weird).
        for (UUID id : new ArrayList<>(activeSeats)) {
            Entity stand = Bukkit.getEntity(id);
            if (stand == null) {
                activeSeats.remove(id);
            } else if (instanceStr.equals(stand.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING))) {
                stand.eject();
                stand.remove();
                activeSeats.remove(id);
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
     * Remote pickup (furniture GUI): resolves the anchor by instance id — loading the chunk on
     * demand, same as purgeOwner — and reuses pickup() (ownership check, item back, sounds).
     * Chunk entities stream in ASYNC after the chunk loads: if they aren't there yet, a chunk
     * ticket holds the chunk and the lookup retries for up to 3s before concluding the anchor
     * is really gone. Without the wait, a far-away live furniture would be pruned from the
     * index as "missing" and left standing as an orphan.
     *
     * @param onChanged runs (on the main thread) after the index changed — GUI refresh hook.
     */
    public void pickupRemote(Player player, String instance, Runnable onChanged) {
        PlacementIndex.Placement p = index.get(instance);
        if (p == null) return;
        if (!p.owner().equals(player.getUniqueId().toString())
                && !player.hasPermission("superfurnitures.admin.bypass")) {
            bar(player, "Este mueble no es tuyo.", NamedTextColor.RED);
            return;
        }
        World world = Bukkit.getWorld(p.world());
        if (world == null) {
            index.remove(instance);
            bar(player, "El mundo de ese mueble ya no existe; entrada retirada.", NamedTextColor.YELLOW);
            onChanged.run();
            return;
        }
        org.bukkit.Chunk chunk = world.getChunkAt(((int) Math.floor(p.x())) >> 4, ((int) Math.floor(p.z())) >> 4);
        chunk.addPluginChunkTicket(plugin);
        resolveAndPickup(player, instance, chunk, 0, onChanged);
    }

    private void resolveAndPickup(Player player, String instance, org.bukkit.Chunk chunk,
                                  int attempt, Runnable onChanged) {
        if (!player.isOnline()) {
            chunk.removePluginChunkTicket(plugin);
            return;
        }
        if (chunk.isEntitiesLoaded()) {
            try {
                for (Entity ent : chunk.getEntities()) {
                    if (ent instanceof Interaction i
                            && instance.equals(i.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING))) {
                        pickup(player, i);
                        onChanged.run();
                        return;
                    }
                }
                // Entities ARE loaded and the anchor really isn't among them: stale entry.
                index.remove(instance);
                bar(player, "Ese mueble ya no existe; entrada retirada del listado.", NamedTextColor.YELLOW);
                onChanged.run();
            } finally {
                chunk.removePluginChunkTicket(plugin);
            }
            return;
        }
        if (attempt >= 12) { // ~3s: give up without touching the index (never prune blind)
            chunk.removePluginChunkTicket(plugin);
            bar(player, "El mueble está demasiado lejos y su zona no carga; inténtalo de nuevo.", NamedTextColor.YELLOW);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> resolveAndPickup(player, instance, chunk, attempt + 1, onChanged), 5L);
    }

    /**
     * Admin: teleport to a placed furniture (admin GUI). teleportAsync loads the destination
     * chunk off the main thread; if the spot is inside the furniture's solid shell, the usual
     * anti-suffocation rescue lifts the admin to the first free spot.
     */
    public void teleportTo(Player player, String instance) {
        PlacementIndex.Placement p = index.get(instance);
        if (p == null) {
            bar(player, "Ese mueble ya no está en el índice.", NamedTextColor.YELLOW);
            return;
        }
        World world = Bukkit.getWorld(p.world());
        if (world == null) {
            bar(player, "El mundo '" + p.world() + "' no está cargado.", NamedTextColor.RED);
            return;
        }
        Location dest = new Location(world, p.x(), p.y(), p.z(), player.getLocation().getYaw(), 0f);
        player.teleportAsync(dest).thenAccept(ok -> {
            if (ok && player.isOnline()) {
                rescueFromSuffocation(player);
                bar(player, "Teletransportado al mueble (" + p.type() + ").", NamedTextColor.GREEN);
            }
        });
    }

    /**
     * Admin: remove ALL furniture of a player, loading chunks as needed. Entries whose anchor
     * is gone (killed by hand) are pruned from the index.
     *
     * @return [furniture removed, stale index entries pruned]
     */
    public int[] purgeOwner(String ownerUuid, String actorName) {
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
            org.bukkit.Chunk chunk = world.getChunkAt(((int) Math.floor(p.x())) >> 4, ((int) Math.floor(p.z())) >> 4);
            if (!chunk.isEntitiesLoaded()) {
                // Entities stream in async after the chunk loads; pruning now could orphan a
                // LIVE furniture. Leave the entry; a re-run (or chunk-load self-heal) gets it.
                continue;
            }
            Interaction anchor = null;
            for (Entity ent : chunk.getEntities()) {
                if (ent instanceof Interaction i
                        && instance.equals(i.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING))) {
                    anchor = i;
                    break;
                }
            }
            if (anchor != null) {
                String typeId = anchor.getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
                Location loc = anchor.getLocation();
                removeFurniture(anchor);
                audit.log("PURGE", actorName, ownerUuid, typeId != null ? typeId : "?", instance, loc);
                removed++;
            } else {
                index.remove(instance);
                pruned++;
            }
        }
        return new int[]{removed, pruned};
    }

    // ==================== ROTATE IN PLACE ====================

    /**
     * Rotate a placed furniture clockwise without picking it up (owner: sneak + punch).
     * Non-solid furniture turns in 45° steps (diagonal chairs around a round table); solid
     * furniture keeps 90° steps — its barrier shell must stay grid-aligned. Model parts all
     * live at the anchor origin with their shape in transformation matrices, so rotating is
     * mostly per-entity yaw; authored hitbox parts CAN sit at an offset and get orbited around
     * the origin. The solid shell is re-laid for the new yaw, with a fit check BEFORE touching
     * anything.
     */
    public void rotateFurniture(Player player, Interaction anchor) {
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        String instanceStr = pdc.get(keyInstance, PersistentDataType.STRING);
        String ownerStr = pdc.get(keyOwner, PersistentDataType.STRING);
        if (instanceStr == null) return;

        if (ownerStr != null && !ownerStr.equals(player.getUniqueId().toString())
                && !player.hasPermission("superfurnitures.admin.bypass")) {
            bar(player, "Este mueble no es tuyo.", NamedTextColor.RED);
            return;
        }
        FurnitureType type = registry.byId(pdc.get(keyType, PersistentDataType.STRING));
        if (type == null) {
            bar(player, "Este mueble ya no está en el catálogo; recógelo.", NamedTextColor.YELLOW);
            return;
        }
        if (type.anchor == FurnitureType.Anchor.WALL) {
            bar(player, "Los muebles de pared no se giran: recógelo y colócalo en otra cara.", NamedTextColor.YELLOW);
            return;
        }

        long now = System.currentTimeMillis();
        Long last = rotateCooldown.get(player.getUniqueId());
        if (last != null && now - last < 400) return;
        rotateCooldown.put(player.getUniqueId(), now);

        Float yawObj = pdc.get(keyYaw, PersistentDataType.FLOAT);
        float oldYaw = yawObj != null ? yawObj : 0f;
        float step = type.solid ? 90f : 45f;
        float newYaw = (oldYaw + step) % 360f;
        World world = anchor.getWorld();
        Location origin = anchor.getLocation();

        // Fit check: the rotated shell must land on replaceable blocks or on our own barriers
        if (type.solid) {
            Set<String> ours = new HashSet<>();
            String barrierCsv = pdc.get(keyBarriers, PersistentDataType.STRING);
            if (barrierCsv != null && !barrierCsv.isEmpty()) {
                ours.addAll(List.of(barrierCsv.split(";")));
            }
            int heightBlocks = (type.anchor == FurnitureType.Anchor.FLOOR)
                    ? Math.max(1, (int) Math.ceil(type.hitboxHeight)) : 1;
            for (Block cell : shellBlocks(type, shellTarget(anchor, type), newYaw, heightBlocks)) {
                String pos = cell.getX() + "," + cell.getY() + "," + cell.getZ();
                if (!cell.isReplaceable() && !ours.contains(pos)) {
                    bar(player, "No hay espacio para girarlo hacia ahí.", NamedTextColor.YELLOW);
                    return;
                }
            }
        }

        // Spin the entities: seats are ejected (they re-spawn rotated on next sit), parts get
        // +90° yaw, off-origin parts (authored hitboxes) also orbit around the anchor axis.
        for (Entity near : world.getNearbyEntities(origin, 8, 8, 8)) {
            if (near == anchor
                    || !instanceStr.equals(near.getPersistentDataContainer().get(keyInstance, PersistentDataType.STRING))) {
                continue;
            }
            if (near.getPersistentDataContainer().has(keySeat, PersistentDataType.BYTE)) {
                near.eject();
                near.remove();
                activeSeats.remove(near.getUniqueId());
                continue;
            }
            Location l = near.getLocation();
            double offX = l.getX() - origin.getX();
            double offZ = l.getZ() - origin.getZ();
            double[] r = rotate(offX, offZ, step);
            Location moved = new Location(world,
                    origin.getX() + r[0], l.getY(), origin.getZ() + r[1],
                    l.getYaw() + step, l.getPitch());
            near.teleport(moved);
        }

        pdc.set(keyYaw, PersistentDataType.FLOAT, newYaw);
        reshell(anchor);
        playSound(world, origin, type.placeSound);
        audit.log("ROTATE", player, type.id, instanceStr, origin);
        bar(player, "Mueble girado (" + Math.round(newYaw) + "°).", NamedTextColor.GREEN);
    }

    // ==================== TYPE RE-SYNC (admin tuning tools) ====================

    /** The block the furniture was placed INTO (the shell origin), recovered from the anchor. */
    static Block shellTarget(Interaction anchor, FurnitureType type) {
        Location loc = anchor.getLocation();
        // CEILING anchors spawn at target + (0.5, 1.0, 0.5): the origin block is one below
        if (type.anchor == FurnitureType.Anchor.CEILING) {
            return anchor.getWorld().getBlockAt(loc.getBlockX(), (int) Math.floor(loc.getY()) - 1, loc.getBlockZ());
        }
        return loc.getBlock();
    }

    /**
     * Re-apply the solid shell of a placed furniture after its type's footprint/hitbox changed
     * in furniture.yml: old barriers back to air, new ones placed, PDC updated.
     */
    public void reshell(Interaction anchor) {
        PersistentDataContainer pdc = anchor.getPersistentDataContainer();
        FurnitureType type = registry.byId(pdc.get(keyType, PersistentDataType.STRING));
        if (type == null) return;
        World world = anchor.getWorld();

        String old = pdc.get(keyBarriers, PersistentDataType.STRING);
        if (old != null && !old.isEmpty()) {
            for (String pos : old.split(";")) {
                String[] xyz = pos.split(",");
                Block b = world.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                if (b.getType() == Material.BARRIER) {
                    b.setType(Material.AIR);
                }
            }
        }
        pdc.remove(keyBarriers);
        if (!type.solid) return;

        Float yawObj = pdc.get(keyYaw, PersistentDataType.FLOAT);
        float yaw = yawObj != null ? yawObj : 0f;
        int heightBlocks = (type.anchor == FurnitureType.Anchor.FLOOR)
                ? Math.max(1, (int) Math.ceil(type.hitboxHeight)) : 1;
        String csv = placeBarriers(type, shellTarget(anchor, type), yaw, heightBlocks);
        if (!csv.isEmpty()) {
            pdc.set(keyBarriers, PersistentDataType.STRING, csv);
        }
    }

    /** Align a loaded anchor's clickable size with its type (after /sf hitbox). @return changed */
    public boolean syncHitbox(Interaction anchor, FurnitureType type) {
        if (anchor.getInteractionWidth() == type.hitboxWidth
                && anchor.getInteractionHeight() == type.hitboxHeight) {
            return false;
        }
        anchor.setInteractionWidth(type.hitboxWidth);
        anchor.setInteractionHeight(type.hitboxHeight);
        return true;
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
            case NONE -> {
                // Decorative furniture: right-click answers "whose is this?" instead of nothing.
                String owner = pdc.get(keyOwner, PersistentDataType.STRING);
                bar(player, prettyName(type.id) + " — " + ownerLabel(owner), NamedTextColor.GRAY);
            }
        }
    }

    /** "de Pepe" / "del servidor" / "sin dueño" for an owner string from the anchor PDC. */
    static String ownerLabel(String ownerStr) {
        if (ownerStr == null) return "sin dueño";
        if (SERVER_OWNER.equals(ownerStr)) return "del servidor";
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(ownerStr)).getName();
            return name != null ? "de " + name : "de " + ownerStr;
        } catch (IllegalArgumentException e) {
            return "de " + ownerStr;
        }
    }

    private static String prettyName(String id) {
        String clean = id.replace('_', ' ').replace('-', ' ');
        return clean.isEmpty() ? id : Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
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

        // Multi-seat furniture: try the seat NEAREST to the player first (you sit where you
        // stand by the sofa, not always in seat #1).
        List<double[]> ordered = new ArrayList<>(type.seats);
        Location me = player.getLocation();
        ordered.sort(Comparator.comparingDouble(offset -> {
            double[] r = rotate(offset[0], offset[2], yaw);
            double dx = base.getX() + r[0] - me.getX();
            double dy = base.getY() + offset[1] - me.getY();
            double dz = base.getZ() + r[1] - me.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));

        for (double[] offset : ordered) {
            double[] rotated = rotate(offset[0], offset[2], yaw);
            Location seatLoc = base.clone().add(rotated[0], offset[1], rotated[1]);
            seatLoc.setYaw(seatYaw(yaw, offset));

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
                as.setInvulnerable(true);
                as.setCollidable(false);
                PersistentDataContainer sp = as.getPersistentDataContainer();
                sp.set(keySeat, PersistentDataType.BYTE, (byte) 1);
                if (instanceStr != null) {
                    sp.set(keyInstance, PersistentDataType.STRING, instanceStr);
                }
            });
            if (!stand.addPassenger(player)) {
                // Mounting can be vetoed (another plugin, dead player...): never leave the
                // empty stand behind.
                stand.remove();
                return;
            }
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
    public int limitFor(Player player) {
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

    /** Nearest furniture anchor around the player (admin tools: /sf seats|show|info). */
    public Interaction findNearestAnchor(Player player, double radius) {
        Interaction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity near : player.getNearbyEntities(radius, radius, radius)) {
            if (near instanceof Interaction i
                    && i.getPersistentDataContainer().has(keyType, PersistentDataType.STRING)) {
                double d = i.getLocation().distanceSquared(player.getLocation());
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
        }
        return best;
    }

    /** Rotate a local-space (x, z) offset (+z = model facing) by the furniture yaw. */
    static double[] rotate(double lx, double lz, float yaw) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{lx * cos - lz * sin, lx * sin + lz * cos};
    }

    private static float snap90(float yaw) {
        return Math.floorMod(Math.round(yaw / 90f) * 90, 360);
    }

    /** Facing of a seat: the furniture yaw plus the seat's optional 4th component (relative yaw). */
    static float seatYaw(float furnitureYaw, double[] seatOffset) {
        float yaw = furnitureYaw + (seatOffset.length > 3 ? (float) seatOffset[3] : 0f);
        return ((yaw % 360f) + 360f) % 360f;
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
