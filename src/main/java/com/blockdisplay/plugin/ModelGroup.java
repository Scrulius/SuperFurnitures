package com.blockdisplay.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelGroup {
    private final UUID groupId;
    private final String modelId;
    private final List<Entity> parts;
    private Location origin;
    private float yawOffset = 0;
    private ModelData modelData;
    private boolean animating = false;
    private boolean loopAnim = true;
    private float animSpeed = 1.0f; // Animation speed multiplier (0.25x to 4x)

    private static final Pattern SPLIT_PATTERN = Pattern.compile("(?<=\\}),(?=\\{id:\"minecraft:)");
    private static final Pattern ID_PATTERN = Pattern.compile("^\\{id:\"minecraft:([^\"]+)\"");

    public ModelGroup(Location origin, String modelId) {
        this(origin, UUID.randomUUID(), modelId);
    }

    public ModelGroup(Location origin, UUID groupId, String modelId) {
        this.groupId = groupId;
        this.modelId = modelId;
        this.parts = new ArrayList<>();
        this.origin = origin.clone();
    }

    public void reconnectOrSpawn(ModelData modelData, BlockDisplayPlugin plugin) {
        this.modelData = modelData;
        NamespacedKey groupKey = new NamespacedKey(plugin, "group_id");
        World world = origin.getWorld();
        if (world == null) return;

        for (Entity e : world.getNearbyEntities(origin, 15, 15, 15)) {
            String idStr = e.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
            if (idStr != null && idStr.equals(groupId.toString())) {
                parts.add(e);
            }
        }

        if (parts.isEmpty()) {
            spawn(modelData, plugin);
        }
    }

    public void spawn(ModelData modelData, BlockDisplayPlugin plugin) {
        this.modelData = modelData;
        if (!modelData.hasPassengers()) return;
        World world = origin.getWorld();
        if (world == null) return;

        NamespacedKey groupKey = new NamespacedKey(plugin, "group_id");
        String uniqueTag = "bde_group_" + groupId.toString().replace("-", "");
        String dimension = world.getKey().toString();

        Boolean originalFeedback = world.getGameRuleValue(GameRule.SEND_COMMAND_FEEDBACK);
        Boolean originalLog = world.getGameRuleValue(GameRule.LOG_ADMIN_COMMANDS);
        if (Boolean.TRUE.equals(originalFeedback)) world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
        if (Boolean.TRUE.equals(originalLog)) world.setGameRule(GameRule.LOG_ADMIN_COMMANDS, false);

        for (String rawSnbt : modelData.content.passengers) {
            String[] individualParts = SPLIT_PATTERN.split(rawSnbt);

            for (String snbt : individualParts) {
                String entityType = "item_display";
                Matcher idMatcher = ID_PATTERN.matcher(snbt);
                if (idMatcher.find()) {
                    entityType = idMatcher.group(1);
                }

                String nbt = snbt.replaceFirst("\\{id:\"minecraft:[^\"]+\",", "{");

                Matcher tagsMatcher = Pattern.compile("Tags:\\s*\\[").matcher(nbt);
                if (tagsMatcher.find()) {
                    nbt = tagsMatcher.replaceFirst("Tags:[\"" + uniqueTag + "\",");
                } else {
                    nbt = nbt.substring(0, nbt.length() - 1) + ",Tags:[\"" + uniqueTag + "\"]}";
                }

                String cmd = String.format(Locale.US,
                        "execute in %s positioned %f %f %f run summon minecraft:%s ~ ~ ~ %s",
                        dimension, origin.getX(), origin.getY(), origin.getZ(), entityType, nbt);

                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to summon part: " + e.getMessage());
                }
            }
        }

        if (modelData.hasHitbox()) {
            for (String hitboxCmd : modelData.content.hitbox) {
                String cmd = String.format(Locale.US,
                        "execute in %s positioned %f %f %f run %s",
                        dimension, origin.getX(), origin.getY(), origin.getZ(), hitboxCmd);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to summon hitbox: " + e.getMessage());
                }
            }
        }

        if (Boolean.TRUE.equals(originalFeedback)) world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, true);
        if (Boolean.TRUE.equals(originalLog)) world.setGameRule(GameRule.LOG_ADMIN_COMMANDS, true);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Entity e : world.getEntitiesByClass(org.bukkit.entity.Display.class)) {
                if (e.getLocation().distanceSquared(origin) <= 400) {
                    if (e.getScoreboardTags().contains(uniqueTag)) {
                        e.getPersistentDataContainer().set(groupKey, PersistentDataType.STRING, groupId.toString());
                        e.removeScoreboardTag(uniqueTag);
                        parts.add(e);
                    }
                }
            }
            plugin.getLogger().info("Model " + modelId + " spawned with " + parts.size() + " parts (group " + groupId.toString().substring(0, 8) + ")");
        }, 5L);
    }

    public void remove() {
        for (Entity part : parts) {
            if (part != null && part.isValid()) {
                part.remove();
            }
        }
        parts.clear();
    }

    public void setYaw(float yaw) {
        this.yawOffset = yaw;
        for (Entity part : parts) {
            if (part != null && part.isValid()) {
                Location loc = part.getLocation();
                loc.setYaw(yaw);
                part.teleport(loc);
            }
        }
    }

    // Getters and setters
    public UUID getGroupId() { return groupId; }
    public Location getOrigin() { return origin; }
    public String getModelId() { return modelId; }
    public ModelData getModelData() { return modelData; }
    public List<Entity> getParts() { return parts; }
    public int getPartCount() { return parts.size(); }
    public boolean isAnimating() { return animating; }
    public void setAnimating(boolean animating) { this.animating = animating; }
    public float getYawOffset() { return yawOffset; }
    public float getAnimSpeed() { return animSpeed; }
    public void setAnimSpeed(float animSpeed) { this.animSpeed = Math.max(0.25f, Math.min(4.0f, animSpeed)); }
    public boolean isLoopAnim() { return loopAnim; }
    public void setLoopAnim(boolean loopAnim) { this.loopAnim = loopAnim; }
}
