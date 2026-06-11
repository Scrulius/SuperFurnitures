package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FurnitureListener implements Listener {

    private final BlockDisplayPlugin plugin;
    private final FurnitureManager manager;
    private final Map<UUID, Long> hintCooldown = new HashMap<>();

    public FurnitureListener(BlockDisplayPlugin plugin, FurnitureManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ==================== PLACE ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || e.getClickedBlock() == null) return;
        if (!manager.getMythic().isAvailable()) return;

        String mythicType = manager.getMythic().getMythicType(item);
        if (mythicType == null) return;
        FurnitureType type = manager.getRegistry().byMythicItem(mythicType);
        if (type == null) return;

        // It IS a furniture item: never let it act as a vanilla block/item (e.g. player heads place)
        e.setCancelled(true);

        boolean placed = manager.place(type, e.getPlayer(), e.getClickedBlock(), e.getBlockFace(), item);
        if (placed && e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    // ==================== PICKUP / INTERACT ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = e.getRightClicked();
        String instance = clicked.getPersistentDataContainer()
                .get(manager.keyInstance, PersistentDataType.STRING);
        if (instance == null) return;

        e.setCancelled(true);
        Player player = e.getPlayer();

        Interaction anchor = resolveAnchor(clicked, instance);
        if (anchor == null) {
            return;
        }

        boolean emptyHand = player.getInventory().getItemInMainHand().getType() == Material.AIR;
        if (player.isSneaking() && emptyHand) {
            manager.pickup(player, anchor);
        } else {
            manager.interact(player, anchor);
        }
    }

    /** The clicked entity may be a model-authored hitbox part; find the metadata-carrying anchor. */
    private Interaction resolveAnchor(Entity clicked, String instance) {
        if (clicked instanceof Interaction i
                && i.getPersistentDataContainer().has(manager.keyType, PersistentDataType.STRING)) {
            return i;
        }
        for (Entity near : clicked.getWorld().getNearbyEntities(clicked.getLocation(), 4, 4, 4)) {
            if (near instanceof Interaction i
                    && instance.equals(i.getPersistentDataContainer().get(manager.keyInstance, PersistentDataType.STRING))
                    && i.getPersistentDataContainer().has(manager.keyType, PersistentDataType.STRING)) {
                return i;
            }
        }
        return null;
    }

    // ==================== PROTECTION ====================

    @EventHandler
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Entity attacked = e.getAttacked();
        if (!attacked.getPersistentDataContainer().has(manager.keyInstance, PersistentDataType.STRING)) return;
        e.setCancelled(true);

        // Gentle hint, throttled per player
        long now = System.currentTimeMillis();
        UUID id = e.getPlayer().getUniqueId();
        Long last = hintCooldown.get(id);
        if (last == null || now - last > 3000) {
            hintCooldown.put(id, now);
            manager.bar(e.getPlayer(), "Agáchate + clic derecho con la mano vacía para recoger el mueble.", NamedTextColor.GRAY);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(manager.keyInstance, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    // ==================== SEATS ====================

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        Entity vehicle = e.getDismounted();
        if (vehicle.getPersistentDataContainer().has(manager.keySeat, PersistentDataType.BYTE)) {
            manager.releaseSeat(vehicle);
            // Vanilla decides the landing spot AFTER this event; check it next tick
            if (e.getEntity() instanceof Player player) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) manager.rescueFromSuffocation(player);
                });
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Entity vehicle = e.getPlayer().getVehicle();
        if (vehicle != null
                && vehicle.getPersistentDataContainer().has(manager.keySeat, PersistentDataType.BYTE)) {
            vehicle.eject();
            manager.releaseSeat(vehicle);
            // The position that saves with the quit is the ejected one — fix it NOW (no next tick)
            manager.rescueFromSuffocation(e.getPlayer());
        }
    }

    // ==================== ANIMATION RE-BINDING (chunk lifecycle) ====================

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Set<String> anchorsPresent = new HashSet<>();
        for (Entity entity : e.getEntities()) {
            if (entity instanceof Interaction anchor
                    && anchor.getPersistentDataContainer().has(manager.keyType, PersistentDataType.STRING)) {
                String instance = anchor.getPersistentDataContainer()
                        .get(manager.keyInstance, PersistentDataType.STRING);
                if (instance != null) {
                    anchorsPresent.add(instance);
                    healIndexEntry(anchor, instance);
                }
                manager.bindAnimation(anchor);
            }
        }

        // Self-heal: index entries expecting an anchor in this chunk that no longer exists
        // (an admin /kill'ed the entities by hand) only pollute the counts — prune them.
        String world = e.getWorld().getName();
        Map<String, PlacementIndex.Placement> expected = manager.getIndex()
                .entriesInChunk(world, e.getChunk().getX(), e.getChunk().getZ());
        for (String instance : expected.keySet()) {
            if (!anchorsPresent.contains(instance)) {
                manager.getIndex().remove(instance);
                plugin.getLogger().info("[Muebles] Entrada huérfana podada del índice (anchor desaparecido): "
                        + instance + " en " + world);
            }
        }
    }

    /** A live anchor the index doesn't know (lost/corrupted placements.json) gets re-adopted. */
    private void healIndexEntry(Interaction anchor, String instance) {
        if (manager.getIndex().contains(instance)) return;
        String type = anchor.getPersistentDataContainer().get(manager.keyType, PersistentDataType.STRING);
        String owner = anchor.getPersistentDataContainer().get(manager.keyOwner, PersistentDataType.STRING);
        if (type == null || owner == null) return;
        manager.getIndex().add(instance, new PlacementIndex.Placement(
                type, owner, anchor.getWorld().getName(),
                anchor.getLocation().getX(), anchor.getLocation().getY(), anchor.getLocation().getZ()));
        plugin.getLogger().info("[Muebles] Anchor vivo re-adoptado en el índice: " + type + " (" + instance + ")");
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (entity instanceof Interaction anchor) {
                String instance = anchor.getPersistentDataContainer()
                        .get(manager.keyInstance, PersistentDataType.STRING);
                if (instance != null
                        && anchor.getPersistentDataContainer().has(manager.keyType, PersistentDataType.STRING)) {
                    try {
                        manager.unbindAnimation(UUID.fromString(instance));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }
}
