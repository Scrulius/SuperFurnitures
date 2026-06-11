package com.blockdisplay.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class PersistenceManager {
    private final BlockDisplayPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    public PersistenceManager(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawned.yml");
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create spawned.yml");
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void saveGroup(ModelGroup group) {
        String path = "groups." + group.getGroupId().toString();
        config.set(path + ".model", group.getModelId());
        config.set(path + ".name", group.getDisplayName());
        config.set(path + ".world", group.getOrigin().getWorld().getName());
        config.set(path + ".x", group.getOrigin().getX());
        config.set(path + ".y", group.getOrigin().getY());
        config.set(path + ".z", group.getOrigin().getZ());
        config.set(path + ".yaw", group.getYawOffset());
        config.set(path + ".animating", group.isAnimating());
        config.set(path + ".loopAnim", group.isLoopAnim());
        config.set(path + ".animSpeed", group.getAnimSpeed());
        config.set(path + ".anim", group.getCurrentAnim());
        save();
    }

    public void removeGroup(UUID groupId) {
        config.set("groups." + groupId.toString(), null);
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save spawned.yml");
        }
    }

    /** Group ids currently present in spawned.yml (used to GC orphaned data/ snapshots at startup). */
    public java.util.Set<String> getSavedGroupIds() {
        ConfigurationSection section = config.getConfigurationSection("groups");
        return (section == null) ? java.util.Set.of() : section.getKeys(false);
    }

    public void loadSavedGroups() {
        ConfigurationSection section = config.getConfigurationSection("groups");
        if (section == null) return;

        int loadedCount = 0;
        for (String uuidStr : section.getKeys(false)) {
            try {
                if (loadOne(uuidStr)) {
                    loadedCount++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping malformed saved model '" + uuidStr + "': " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loading " + loadedCount + " saved model(s)...");
    }

    /**
     * @return true if the entry was scheduled to load, false if it was skipped (e.g. world missing).
     */
    private boolean loadOne(String uuidStr) {
        UUID groupId = UUID.fromString(uuidStr);
        String path = "groups." + uuidStr;
        String modelId = config.getString(path + ".model");
        String displayName = config.getString(path + ".name", "unnamed_" + uuidStr.substring(0, 6));
        String worldName = config.getString(path + ".world");
        World world = (worldName != null) ? plugin.getServer().getWorld(worldName) : null;

        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' not found. Skipping model " + displayName);
            return false;
        }

        double x = config.getDouble(path + ".x");
        double y = config.getDouble(path + ".y");
        double z = config.getDouble(path + ".z");
        float yaw = (float) config.getDouble(path + ".yaw");
        boolean animating = config.getBoolean(path + ".animating", false);
        boolean loopAnim = config.getBoolean(path + ".loopAnim", true);
        float animSpeed = (float) config.getDouble(path + ".animSpeed", 1.0);
        String animName = config.getString(path + ".anim", null);

        Location loc = new Location(world, x, y, z, yaw, 0);

        // 1) Authoritative: a local snapshot of the model data. No API, no name lookups.
        ModelData snapshot = plugin.getModelManager().loadSpawnedData(groupId);
        if (snapshot != null) {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    spawnLoaded(groupId, modelId, displayName, loc, yaw, animating, loopAnim, animSpeed, animName, snapshot, false));
            return true;
        }

        // 2) Legacy / migration fallback: no snapshot yet (saved by an older version).
        //    Resolve from library name or API cache, then write a snapshot so the next restart is clean.
        plugin.getModelManager().resolveModelData(modelId).thenAccept(modelData ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (modelData != null) {
                        spawnLoaded(groupId, modelId, displayName, loc, yaw, animating, loopAnim, animSpeed, animName, modelData, true);
                    } else {
                        plugin.getLogger().warning("Could not reload model '" + displayName + "' (" + modelId
                                + ") - no local snapshot, and not found in library or API.");
                    }
                }));
        return true;
    }

    private void spawnLoaded(UUID groupId, String modelId, String displayName, Location loc, float yaw,
                             boolean animating, boolean loopAnim, float animSpeed, String animName,
                             ModelData modelData, boolean snapshotAfter) {
        // Names must be unique among active models (commands resolve by name); old saves may
        // contain duplicates, so de-dup on load and persist the corrected name.
        String finalName = displayName;
        int n = 2;
        while (plugin.findGroupByName(finalName) != null) {
            finalName = displayName + "_" + n++;
        }
        if (!finalName.equals(displayName)) {
            plugin.getLogger().warning("Duplicate model name '" + displayName + "' in spawned.yml; renamed to '" + finalName + "'.");
        }

        ModelGroup group = new ModelGroup(loc, groupId, modelId, finalName);
        group.reconnectOrSpawn(modelData, plugin);
        group.setYaw(yaw);
        group.setLoopAnim(loopAnim);
        group.setAnimSpeed(animSpeed);
        // Restore the chosen named animation; fall back to the model's default if it vanished.
        if (animName != null && modelData.hasAnimations()) {
            if (modelData.getAnimationNames().contains(animName)) {
                group.setCurrentAnim(animName);
            } else {
                plugin.getLogger().warning("Animation '" + animName + "' no longer exists in model '" + finalName
                        + "'; using '" + modelData.defaultAnimName() + "'.");
            }
        }
        // Auto-start animation if it was animating before and has animations
        if (animating && modelData.hasAnimations()) {
            group.setAnimating(true);
        }

        plugin.getActiveGroups().put(groupId, group);
        if (snapshotAfter) {
            plugin.getModelManager().saveSpawnedData(groupId, modelData);
        }
        if (!finalName.equals(displayName)) {
            saveGroup(group);
        }
        plugin.getLogger().info("Loaded model '" + finalName + "' (" + modelId + ")");
    }
}
