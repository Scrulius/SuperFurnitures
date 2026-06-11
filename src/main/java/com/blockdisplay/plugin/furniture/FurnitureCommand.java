package com.blockdisplay.plugin.furniture;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** /furniture — comando de jugador: list (tus muebles y dónde) y limit (cuota). */
public class FurnitureCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "Muebles" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
    private static final int MAX_LIST = 25;

    private final FurnitureManager manager;

    public FurnitureCommand(FurnitureManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }

        String action = (args.length > 0) ? args[0].toLowerCase() : "limit";
        switch (action) {
            case "list" -> {
                List<PlacementIndex.Placement> placements =
                        manager.getIndex().byOwner(player.getUniqueId().toString());
                if (placements.isEmpty()) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "No tienes muebles colocados.");
                    return true;
                }
                player.sendMessage(PREFIX + ChatColor.GOLD + "Tus muebles (" + placements.size() + "):");
                int shown = 0;
                for (PlacementIndex.Placement p : placements) {
                    if (shown++ >= MAX_LIST) {
                        player.sendMessage(ChatColor.GRAY + " ... y " + (placements.size() - MAX_LIST) + " más.");
                        break;
                    }
                    player.sendMessage(ChatColor.WHITE + " " + p.type()
                            + ChatColor.DARK_GRAY + " @ " + ChatColor.GRAY
                            + String.format("%.0f, %.0f, %.0f", p.x(), p.y(), p.z())
                            + ChatColor.DARK_GRAY + " (" + p.world() + ")");
                }
            }
            case "limit" -> {
                int used = manager.getIndex().countByOwner(player.getUniqueId().toString());
                int limit = manager.getRegistry().getPerPlayerDefault();
                for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
                    String perm = pai.getPermission();
                    if (pai.getValue() && perm.startsWith("superfurnitures.limit.")) {
                        try {
                            limit = Math.max(limit, Integer.parseInt(perm.substring("superfurnitures.limit.".length())));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                player.sendMessage(PREFIX + ChatColor.GRAY + "Muebles colocados: "
                        + ChatColor.WHITE + used + ChatColor.GRAY + " / " + ChatColor.WHITE + limit);
            }
            default -> {
                player.sendMessage(PREFIX + ChatColor.YELLOW + "/furniture list" + ChatColor.GRAY + " - Tus muebles y dónde están");
                player.sendMessage(PREFIX + ChatColor.YELLOW + "/furniture limit" + ChatColor.GRAY + " - Tu cuota de muebles");
            }
        }
        return true;
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String option : Arrays.asList("list", "limit")) {
                if (option.startsWith(args[0].toLowerCase())) out.add(option);
            }
            return out;
        }
        return Collections.emptyList();
    }
}
