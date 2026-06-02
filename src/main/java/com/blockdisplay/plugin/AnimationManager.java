package com.blockdisplay.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles animation playback for all active model groups.
 * Supports per-group speed control via a float accumulator.
 */
public class AnimationManager extends BukkitRunnable {

    private final BlockDisplayPlugin plugin;
    private final Map<UUID, Integer> tickCounters = new ConcurrentHashMap<>();
    private final Map<UUID, Float> accumulators = new ConcurrentHashMap<>();

    public AnimationManager(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Map.Entry<UUID, ModelGroup> entry : plugin.getActiveGroups().entrySet()) {
            ModelGroup group = entry.getValue();
            ModelData data = group.getModelData();

            if (!group.isAnimating()) continue;
            if (data == null || !data.hasAnimations()) continue;

            Map<String, List<String>> anim = data.content.datapack.anim_keyframes.get("default");
            if (anim == null) continue;

            int maxTick = 0;
            for (String key : anim.keySet()) {
                try {
                    int t = Integer.parseInt(key);
                    if (t > maxTick) maxTick = t;
                } catch (NumberFormatException ignored) {}
            }
            if (maxTick == 0) continue;

            UUID gid = group.getGroupId();
            float speed = group.getAnimSpeed();

            int tick = tickCounters.getOrDefault(gid, 0);
            float accum = accumulators.getOrDefault(gid, 0.0f);
            accum += speed;

            int framesToAdvance = (int) accum;
            accum -= framesToAdvance;
            accumulators.put(gid, accum);

            for (int i = 0; i < framesToAdvance; i++) {
                int currentAnimTick = tick % (maxTick + 1);
                
                // Stop if we reach the end and loop is disabled
                if (currentAnimTick == maxTick && !group.isLoopAnim()) {
                    group.setAnimating(false);
                    plugin.getPersistenceManager().saveGroup(group);
                    tickCounters.remove(gid);
                    accumulators.remove(gid);
                    break;
                }

                List<String> commands = anim.get(String.valueOf(currentAnimTick));

                if (commands != null) {
                    World world = group.getOrigin().getWorld();
                    if (world != null) {
                        String dimension = world.getKey().toString();
                        double x = group.getOrigin().getX();
                        double y = group.getOrigin().getY();
                        double z = group.getOrigin().getZ();

                        Boolean originalFeedback = world.getGameRuleValue(GameRule.SEND_COMMAND_FEEDBACK);
                        Boolean originalLog = world.getGameRuleValue(GameRule.LOG_ADMIN_COMMANDS);
                        
                        if (Boolean.TRUE.equals(originalFeedback)) world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
                        if (Boolean.TRUE.equals(originalLog)) world.setGameRule(GameRule.LOG_ADMIN_COMMANDS, false);

                        for (String cmd : commands) {
                            String fullCommand = String.format(Locale.US,
                                    "execute in %s positioned %f %f %f run %s",
                                    dimension, x, y, z, cmd);
                            try {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fullCommand);
                            } catch (Exception ignored) {}
                        }

                        if (Boolean.TRUE.equals(originalFeedback)) world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, true);
                        if (Boolean.TRUE.equals(originalLog)) world.setGameRule(GameRule.LOG_ADMIN_COMMANDS, true);
                    }
                }
                tick++;
            }

            if (group.isAnimating()) {
                tickCounters.put(gid, tick);
            }
        }
    }

    public void resetTick(UUID groupId) {
        tickCounters.remove(groupId);
        accumulators.remove(groupId);
    }

    public void removeGroup(UUID groupId) {
        tickCounters.remove(groupId);
        accumulators.remove(groupId);
    }
}
