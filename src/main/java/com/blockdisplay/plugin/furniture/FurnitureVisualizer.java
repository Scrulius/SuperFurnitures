package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Particle X-ray for a furniture instance (/sf show): the Interaction hitbox as an aqua
 * wireframe and every barrier block of its solid shell as a red one. Only the requesting
 * admin sees the particles. Invisible geometry (hitbox sizing, footprint offsets) becomes
 * tunable by eye instead of by guess-deploy-look cycles.
 */
public final class FurnitureVisualizer {

    private static final int PERIOD_TICKS = 5;
    private static final int DURATION_SECONDS = 10;
    private static final double STEP = 0.25;

    private static final Particle.DustOptions HITBOX_DUST = new Particle.DustOptions(Color.AQUA, 0.6f);
    private static final Particle.DustOptions BARRIER_DUST = new Particle.DustOptions(Color.RED, 0.6f);

    private FurnitureVisualizer() {
    }

    public static void show(BlockDisplayPlugin plugin, FurnitureManager manager, Player player, Interaction anchor) {
        Location base = anchor.getLocation();
        double halfW = anchor.getInteractionWidth() / 2.0;
        double height = anchor.getInteractionHeight();
        String barrierCsv = anchor.getPersistentDataContainer().get(manager.keyBarriers, PersistentDataType.STRING);

        new BukkitRunnable() {
            int runs = (DURATION_SECONDS * 20) / PERIOD_TICKS;

            @Override
            public void run() {
                if (runs-- <= 0 || !player.isOnline() || !anchor.isValid()) {
                    cancel();
                    return;
                }
                World world = base.getWorld();
                drawBox(player, world,
                        base.getX() - halfW, base.getY(), base.getZ() - halfW,
                        base.getX() + halfW, base.getY() + height, base.getZ() + halfW,
                        HITBOX_DUST);
                if (barrierCsv != null && !barrierCsv.isEmpty()) {
                    for (String pos : barrierCsv.split(";")) {
                        String[] xyz = pos.split(",");
                        int bx = Integer.parseInt(xyz[0]);
                        int by = Integer.parseInt(xyz[1]);
                        int bz = Integer.parseInt(xyz[2]);
                        drawBox(player, world, bx, by, bz, bx + 1, by + 1, bz + 1, BARRIER_DUST);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, PERIOD_TICKS);
    }

    /** The 12 edges of an axis-aligned box, sampled every STEP blocks. */
    static void drawBox(Player player, World world,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2,
                                Particle.DustOptions dust) {
        for (double x = x1; x <= x2; x += STEP) {
            dot(player, world, x, y1, z1, dust);
            dot(player, world, x, y1, z2, dust);
            dot(player, world, x, y2, z1, dust);
            dot(player, world, x, y2, z2, dust);
        }
        for (double y = y1; y <= y2; y += STEP) {
            dot(player, world, x1, y, z1, dust);
            dot(player, world, x1, y, z2, dust);
            dot(player, world, x2, y, z1, dust);
            dot(player, world, x2, y, z2, dust);
        }
        for (double z = z1; z <= z2; z += STEP) {
            dot(player, world, x1, y1, z, dust);
            dot(player, world, x1, y2, z, dust);
            dot(player, world, x2, y1, z, dust);
            dot(player, world, x2, y2, z, dust);
        }
    }

    private static void dot(Player player, World world, double x, double y, double z, Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, new Location(world, x, y, z), 1, 0, 0, 0, 0, dust);
    }
}
