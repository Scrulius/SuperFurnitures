package com.blockdisplay.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelGroup {
    private final UUID groupId;
    private final String modelId;
    private final String displayName;
    // Parts are tracked by UUID, not Entity reference: Entity objects go stale when their chunk
    // unloads/reloads, while a UUID always resolves to the live instance via Bukkit.getEntity().
    private final List<UUID> partIds = new ArrayList<>();
    // Author-assigned scoreboard tags (bde_0, bde_1, ...) -> part UUIDs. This is what lets the
    // animation engine target parts directly through the API instead of @e[tag=...] selectors.
    private final Map<String, List<UUID>> partsByTag = new HashMap<>();
    private Location origin;
    private float yawOffset = 0;
    private ModelData modelData;
    private boolean animating = false;
    private boolean ready = false;
    private boolean loopAnim = true;
    private float animSpeed = 1.0f; // Animation speed multiplier (0.25x to 4x)
    private String currentAnim; // Which named animation plays; initialized to the model's default at spawn

    private static final Pattern SPLIT_PATTERN = Pattern.compile("(?<=\\}),(?=\\{id:\"minecraft:)");
    private static final Pattern ID_PATTERN = Pattern.compile("^\\{id:\"minecraft:([^\"]+)\"");

    public ModelGroup(Location origin, String modelId, String displayName) {
        this(origin, UUID.randomUUID(), modelId, displayName);
    }

    public ModelGroup(Location origin, UUID groupId, String modelId, String displayName) {
        this.groupId = groupId;
        this.modelId = modelId;
        this.displayName = displayName;
        this.origin = origin.clone();
    }

    /**
     * A scoreboard tag unique to this group, added to every spawned entity. Any leftover command
     * dispatched through the fallback path is scoped with this tag so a model only ever touches
     * its own parts - models authored with the same shared {@code bde_N} tags no longer fight over
     * each other's entities when placed close together.
     */
    public String getAnimTag() {
        return "bdeg_" + groupId.toString().replace("-", "").substring(0, 12);
    }

    /**
     * Register a freshly spawned entity: PDC group id (cleanup sweep + nearest lookup), the unique
     * group tag (fallback command scoping), and its author tags (bde_N) for the animation engine.
     */
    private void tagPart(Entity e, NamespacedKey groupKey) {
        e.getPersistentDataContainer().set(groupKey, PersistentDataType.STRING, groupId.toString());
        e.addScoreboardTag(getAnimTag());
        partIds.add(e.getUniqueId());
        for (String tag : e.getScoreboardTags()) {
            if (!tag.equals(getAnimTag())) {
                partsByTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(e.getUniqueId());
            }
        }
        // Late-registered parts (next-tick retry) must catch up with a rotation that was applied
        // while they weren't tracked yet - otherwise a rotated model loads with one part askew.
        if (yawOffset != 0) {
            Location loc = e.getLocation();
            if (loc.getYaw() != yawOffset) {
                loc.setYaw(yawOffset);
                e.teleport(loc);
            }
        }
    }

    /** Run an action on every part that currently resolves to a live entity. */
    public void forEachPart(Consumer<Entity> action) {
        for (UUID id : partIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) {
                action.accept(e);
            }
        }
    }

    public void reconnectOrSpawn(ModelData modelData, BlockDisplayPlugin plugin) {
        this.modelData = modelData;
        NamespacedKey groupKey = new NamespacedKey(plugin, "group_id");
        World world = origin.getWorld();
        if (world == null) return;

        // Remove any stale entities left over from a previous session (e.g. after crash).
        // All parts are summoned exactly at the origin, so the sweep only finds them if the
        // origin chunk is actually loaded - force-load it first so crash recovery doesn't duplicate.
        if (!world.isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
            world.getChunkAt(origin);
        }
        int r = plugin.getCleanupRadius();
        for (Entity e : world.getNearbyEntities(origin, r, r, r)) {
            String idStr = e.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
            if (idStr != null && idStr.equals(groupId.toString())) {
                e.remove();
            }
        }

        // Always spawn fresh
        spawn(modelData, plugin);
    }

    public void spawn(ModelData modelData, BlockDisplayPlugin plugin) {
        this.modelData = modelData;
        if (currentAnim == null) {
            currentAnim = modelData.defaultAnimName();
        }
        if (!modelData.hasPassengers()) return;
        World world = origin.getWorld();
        if (world == null) return;

        NamespacedKey groupKey = new NamespacedKey(plugin, "group_id");
        String dimension = world.getKey().toString();
        SilentCommandSender silentSender = plugin.getSilentSender();

        // Suppress feedback to OP players in-game during spawning
        Boolean originalFeedback = world.getGameRuleValue(GameRule.SEND_COMMAND_FEEDBACK);
        Boolean originalLog = world.getGameRuleValue(GameRule.LOG_ADMIN_COMMANDS);
        world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
        world.setGameRule(GameRule.LOG_ADMIN_COMMANDS, false);

        for (String rawSnbt : modelData.content.passengers) {
            String[] individualParts = SPLIT_PATTERN.split(rawSnbt);

            for (String snbt : individualParts) {
                String entityType = "item_display";
                Matcher idMatcher = ID_PATTERN.matcher(snbt);
                if (idMatcher.find()) {
                    entityType = idMatcher.group(1);
                }

                UUID partUuid = UUID.randomUUID();
                long msb = partUuid.getMostSignificantBits();
                long lsb = partUuid.getLeastSignificantBits();
                String uuidSnbt = String.format(Locale.US, "UUID:[I;%d,%d,%d,%d]",
                        (int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb);

                String nbt = snbt.replaceFirst("\\{id:\"minecraft:[^\"]+\",?", "{");
                nbt = nbt.replaceFirst("\\{", "{" + uuidSnbt + ",");

                String cmd = String.format(Locale.US,
                        "execute in %s positioned %f %f %f run summon minecraft:%s ~ ~ ~ %s",
                        dimension, origin.getX(), origin.getY(), origin.getZ(), entityType, nbt);

                try {
                    Bukkit.dispatchCommand(silentSender, cmd);
                    Entity spawnedPart = plugin.getServer().getEntity(partUuid);
                    if (spawnedPart != null) {
                        tagPart(spawnedPart, groupKey);
                    } else {
                        // Entity may not be registered yet; schedule a retry on the next tick
                        final UUID retryUuid = partUuid;
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            Entity retryPart = plugin.getServer().getEntity(retryUuid);
                            if (retryPart != null) {
                                tagPart(retryPart, groupKey);
                            } else {
                                plugin.getLogger().warning("Spawned part not found after retry: " + retryUuid);
                            }
                        }, 1L);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to summon part: " + e.getMessage());
                }
            }
        }

        if (modelData.hasHitbox()) {
            for (String hitboxCmd : modelData.content.hitbox) {
                UUID hitboxUuid = UUID.randomUUID();
                long msb = hitboxUuid.getMostSignificantBits();
                long lsb = hitboxUuid.getLeastSignificantBits();
                String uuidSnbt = String.format(Locale.US, "UUID:[I;%d,%d,%d,%d]",
                        (int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb);

                String modifiedHitboxCmd = hitboxCmd;
                if (!modifiedHitboxCmd.contains("{")) {
                    modifiedHitboxCmd += "{}";
                }
                modifiedHitboxCmd = modifiedHitboxCmd.replaceFirst("\\{", "{" + uuidSnbt + ",");

                String cmd = String.format(Locale.US,
                        "execute in %s positioned %f %f %f run %s",
                        dimension, origin.getX(), origin.getY(), origin.getZ(), modifiedHitboxCmd);
                try {
                    Bukkit.dispatchCommand(silentSender, cmd);
                    Entity spawnedHitbox = plugin.getServer().getEntity(hitboxUuid);
                    if (spawnedHitbox != null) {
                        tagPart(spawnedHitbox, groupKey);
                    } else {
                        final UUID retryUuid = hitboxUuid;
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            Entity retryPart = plugin.getServer().getEntity(retryUuid);
                            if (retryPart != null) {
                                tagPart(retryPart, groupKey);
                            }
                        }, 1L);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to summon hitbox: " + e.getMessage());
                }
            }
        }

        // Restore original gamerule values
        world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, Boolean.TRUE.equals(originalFeedback));
        world.setGameRule(GameRule.LOG_ADMIN_COMMANDS, Boolean.TRUE.equals(originalLog));

        // Mark as ready after a short delay so all entities are fully registered
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> this.ready = true, 3L);

        plugin.getLogger().info("Model '" + displayName + "' (" + modelId + ") spawned with " + partIds.size() + " parts.");
    }

    public void remove(BlockDisplayPlugin plugin) {
        forEachPart(Entity::remove);
        partIds.clear();
        partsByTag.clear();

        // Sweep for any untracked entities with our group_id tag
        World world = origin.getWorld();
        if (world != null) {
            NamespacedKey groupKey = new NamespacedKey(plugin, "group_id");
            int r = plugin.getCleanupRadius();
            for (Entity e : world.getNearbyEntities(origin, r, r, r)) {
                String idStr = e.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
                if (idStr != null && idStr.equals(groupId.toString())) {
                    e.remove();
                }
            }
        }
    }

    public void setYaw(float yaw) {
        this.yawOffset = yaw;
        forEachPart(part -> {
            Location loc = part.getLocation();
            loc.setYaw(yaw);
            part.teleport(loc);
        });
    }

    /**
     * Shift the whole model by a precise offset. Transformation matrices are relative to each
     * entity's own position, so a running animation is completely unaffected by the move.
     */
    public void move(double dx, double dy, double dz) {
        origin.add(dx, dy, dz);
        forEachPart(part -> part.teleport(part.getLocation().add(dx, dy, dz)));
    }

    // Getters and setters
    public UUID getGroupId() { return groupId; }
    public Location getOrigin() { return origin; }
    public String getModelId() { return modelId; }
    public String getDisplayName() { return displayName; }
    public ModelData getModelData() { return modelData; }
    public Map<String, List<UUID>> getPartsByTag() { return partsByTag; }
    public int getPartCount() { return partIds.size(); }
    public boolean isReady() { return ready; }
    public boolean isAnimating() { return animating; }
    public void setAnimating(boolean animating) { this.animating = animating; }
    public float getYawOffset() { return yawOffset; }
    public float getAnimSpeed() { return animSpeed; }
    public void setAnimSpeed(float animSpeed) { this.animSpeed = animSpeed; }
    public boolean isLoopAnim() { return loopAnim; }
    public void setLoopAnim(boolean loopAnim) { this.loopAnim = loopAnim; }
    public String getCurrentAnim() { return currentAnim; }
    public void setCurrentAnim(String currentAnim) { this.currentAnim = currentAnim; }
}
