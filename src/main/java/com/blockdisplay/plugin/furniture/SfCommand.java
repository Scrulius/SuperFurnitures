package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** /sf — administración de muebles: give, types, reload. */
public class SfCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "SF" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;

    private final BlockDisplayPlugin plugin;
    private final FurnitureManager manager;

    public SfCommand(BlockDisplayPlugin plugin, FurnitureManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf give <mueble> [jugador]" + ChatColor.GRAY + " - Dar el item de un mueble");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf types" + ChatColor.GRAY + " - Listar muebles registrados");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf reload" + ChatColor.GRAY + " - Recargar furniture.yml");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give" -> {
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /sf give <mueble> [jugador]");
                    return true;
                }
                FurnitureType type = manager.getRegistry().byId(args[1]);
                if (type == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Mueble '" + args[1] + "' no registrado. Mira /sf types");
                    return true;
                }
                Player target;
                if (args.length >= 3) {
                    target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Jugador '" + args[2] + "' no encontrado.");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Desde consola indica el jugador: /sf give <mueble> <jugador>");
                    return true;
                }
                ItemStack item = manager.getMythic().getItem(type.mythicItem);
                if (item == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "El item MM '" + type.mythicItem + "' no existe (¿MythicMobs cargado y el item creado?).");
                    return true;
                }
                target.getInventory().addItem(item).values()
                        .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Item de '" + type.id + "' dado a " + target.getName() + ".");
            }
            case "types" -> {
                var all = manager.getRegistry().all();
                if (all.isEmpty()) {
                    sender.sendMessage(PREFIX + ChatColor.YELLOW + "No hay muebles registrados (edita furniture.yml).");
                    return true;
                }
                sender.sendMessage(PREFIX + ChatColor.GOLD + "Muebles registrados (" + all.size() + "):");
                for (FurnitureType t : all) {
                    sender.sendMessage(ChatColor.WHITE + " " + t.id
                            + ChatColor.DARK_GRAY + " (modelo " + ChatColor.GRAY + t.model
                            + ChatColor.DARK_GRAY + ", item " + ChatColor.GRAY + t.mythicItem
                            + ChatColor.DARK_GRAY + ", " + ChatColor.GRAY + t.anchor.name().toLowerCase()
                            + (t.animated ? ChatColor.AQUA + ", animado" : "")
                            + (t.solid ? ChatColor.YELLOW + ", sólido" : "")
                            + ChatColor.DARK_GRAY + ")");
                }
            }
            case "reload" -> {
                manager.getRegistry().load();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "furniture.yml recargado: "
                        + manager.getRegistry().all().size() + " mueble(s).");
            }
            default -> sender.sendMessage(PREFIX + ChatColor.RED + "Subcomando desconocido. Usa /sf");
        }
        return true;
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("give", "types", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(manager.getRegistry().all().stream().map(t -> t.id).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return null; // jugadores online
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) out.add(option);
        }
        return out;
    }
}
