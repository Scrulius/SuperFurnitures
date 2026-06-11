package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** /sf — administración de muebles: give, types, reload. */
public class SfCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "SF" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
    private static final int MAX_LIST = 25;

    private final BlockDisplayPlugin plugin;
    private final FurnitureManager manager;
    private final SeatEditor seatEditor;
    private final FootprintEditor footprintEditor;

    public SfCommand(BlockDisplayPlugin plugin, FurnitureManager manager,
                     SeatEditor seatEditor, FootprintEditor footprintEditor) {
        this.plugin = plugin;
        this.manager = manager;
        this.seatEditor = seatEditor;
        this.footprintEditor = footprintEditor;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf give <mueble> [jugador]" + ChatColor.GRAY + " - Dar el item de un mueble");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf types" + ChatColor.GRAY + " - Listar muebles registrados");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf list [jugador]" + ChatColor.GRAY + " - Muebles colocados (todos o de un jugador)");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf purge <jugador>" + ChatColor.GRAY + " - Eliminar TODOS los muebles de un jugador");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf seats" + ChatColor.GRAY + " - Editor de asientos con maniquís (mueble cercano)");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf footprint" + ChatColor.GRAY + " - Editor de huella sólida a golpes (mueble cercano)");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf hitbox <ancho> <alto>" + ChatColor.GRAY + " - Cambiar el tamaño clicable del tipo cercano");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf show" + ChatColor.GRAY + " - Ver hitbox y barreras del mueble cercano (partículas)");
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "/sf info" + ChatColor.GRAY + " - Radiografía del mueble cercano");
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
            case "list" -> {
                if (args.length >= 2) {
                    OfflinePlayer target = resolvePlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Jugador '" + args[1] + "' desconocido (nunca ha entrado al server).");
                        return true;
                    }
                    var placements = manager.getIndex().byOwner(target.getUniqueId().toString());
                    if (placements.isEmpty()) {
                        sender.sendMessage(PREFIX + ChatColor.YELLOW + target.getName() + " no tiene muebles colocados.");
                        return true;
                    }
                    sender.sendMessage(PREFIX + ChatColor.GOLD + "Muebles de " + target.getName() + " (" + placements.size() + "):");
                    int shown = 0;
                    for (PlacementIndex.Placement p : placements) {
                        if (shown++ >= MAX_LIST) {
                            sender.sendMessage(ChatColor.GRAY + " ... y " + (placements.size() - MAX_LIST) + " más.");
                            break;
                        }
                        sender.sendMessage(ChatColor.WHITE + " " + p.type()
                                + ChatColor.DARK_GRAY + " @ " + ChatColor.GRAY
                                + String.format("%.0f, %.0f, %.0f", p.x(), p.y(), p.z())
                                + ChatColor.DARK_GRAY + " (" + p.world() + ")");
                    }
                } else {
                    // Sin jugador: resumen por dueño de todo el server
                    var all = manager.getIndex().all();
                    if (all.isEmpty()) {
                        sender.sendMessage(PREFIX + ChatColor.YELLOW + "No hay muebles colocados en el server.");
                        return true;
                    }
                    Map<String, Integer> byOwner = new HashMap<>();
                    for (PlacementIndex.Placement p : all.values()) {
                        byOwner.merge(p.owner(), 1, Integer::sum);
                    }
                    sender.sendMessage(PREFIX + ChatColor.GOLD + "Muebles colocados: " + all.size()
                            + " (de " + byOwner.size() + " jugador(es)):");
                    byOwner.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .forEach(entry -> {
                                String name;
                                try {
                                    OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(entry.getKey()));
                                    name = op.getName() != null ? op.getName() : entry.getKey();
                                } catch (IllegalArgumentException e) {
                                    name = entry.getKey();
                                }
                                sender.sendMessage(ChatColor.WHITE + " " + name
                                        + ChatColor.DARK_GRAY + ": " + ChatColor.GRAY + entry.getValue());
                            });
                }
            }
            case "purge" -> {
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /sf purge <jugador>");
                    return true;
                }
                OfflinePlayer target = resolvePlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Jugador '" + args[1] + "' desconocido (nunca ha entrado al server).");
                    return true;
                }
                int[] result = manager.purgeOwner(target.getUniqueId().toString());
                if (result[0] == 0 && result[1] == 0) {
                    sender.sendMessage(PREFIX + ChatColor.YELLOW + target.getName() + " no tenía muebles colocados.");
                } else {
                    sender.sendMessage(PREFIX + ChatColor.GREEN + "Purga de " + target.getName() + ": "
                            + result[0] + " mueble(s) eliminado(s)"
                            + (result[1] > 0 ? ChatColor.GRAY + " (+" + result[1] + " entrada(s) huérfana(s) podada(s) del índice)" : "")
                            + ChatColor.GREEN + ".");
                }
            }
            case "seats" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "El editor de asientos es in-game (hay que verse los maniquís).");
                    return true;
                }
                String sub = args.length >= 2 ? args[1].toLowerCase() : "start";
                switch (sub) {
                    case "start" -> seatEditor.start(player);
                    case "add" -> seatEditor.add(player);
                    case "list" -> seatEditor.list(player);
                    case "save" -> seatEditor.save(player);
                    case "cancel" -> seatEditor.cancel(player);
                    case "remove" -> {
                        Integer n = parseInt(sender, args, 2, "Uso: /sf seats remove <n>");
                        if (n != null) seatEditor.remove(player, n);
                    }
                    case "move" -> {
                        if (args.length < 5) {
                            sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /sf seats move <n> <x|y|z> <delta>  (ej: /sf seats move 1 y -0.2)");
                            return true;
                        }
                        Integer n = parseInt(sender, args, 2, "El número de asiento debe ser un entero.");
                        if (n == null) return true;
                        double delta;
                        try {
                            delta = Double.parseDouble(args[4]);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(PREFIX + ChatColor.RED + "Delta inválido: " + args[4]);
                            return true;
                        }
                        seatEditor.move(player, n, args[3], delta);
                    }
                    default -> sender.sendMessage(PREFIX + ChatColor.RED
                            + "Uso: /sf seats [add|move|remove|list|save|cancel]");
                }
            }
            case "footprint" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "El editor de huella es in-game (se pinta a golpes).");
                    return true;
                }
                String sub = args.length >= 2 ? args[1].toLowerCase() : "start";
                switch (sub) {
                    case "start" -> footprintEditor.start(player);
                    case "add" -> footprintEditor.add(player);
                    case "clear" -> footprintEditor.clear(player);
                    case "save" -> footprintEditor.save(player);
                    case "cancel" -> footprintEditor.cancel(player);
                    default -> sender.sendMessage(PREFIX + ChatColor.RED
                            + "Uso: /sf footprint [add|clear|save|cancel]  (golpea bloques para pintar)");
                }
            }
            case "hitbox" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Solo in-game.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Uso: /sf hitbox <ancho> <alto>  (del tipo del mueble cercano)");
                    return true;
                }
                double width;
                double height;
                try {
                    width = Double.parseDouble(args[1]);
                    height = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Ancho/alto inválidos: " + args[1] + " " + args[2]);
                    return true;
                }
                if (width <= 0 || width > 8 || height <= 0 || height > 8) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Fuera de rango: ancho y alto deben estar entre 0 y 8.");
                    return true;
                }
                Interaction anchor = manager.findNearestAnchor(player, 5.0);
                if (anchor == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "No hay ningún mueble a menos de 5 bloques.");
                    return true;
                }
                String typeId = anchor.getPersistentDataContainer().get(manager.keyType, PersistentDataType.STRING);
                if (manager.getRegistry().byId(typeId) == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Ese mueble ya no está en el catálogo.");
                    return true;
                }
                if (!saveHitbox(player, typeId, width, height)) {
                    return true;
                }
                // Live re-sync: this anchor now; the rest of loaded ones too; unloaded chunks
                // catch up via the EntitiesLoadEvent hitbox sync.
                FurnitureType type = manager.getRegistry().byId(typeId);
                int synced = 0;
                for (org.bukkit.World world : Bukkit.getWorlds()) {
                    for (Interaction other : world.getEntitiesByClass(Interaction.class)) {
                        if (typeId.equals(other.getPersistentDataContainer().get(manager.keyType, PersistentDataType.STRING))
                                && manager.syncHitbox(other, type)) {
                            synced++;
                        }
                    }
                }
                manager.reshell(anchor);
                FurnitureVisualizer.show(plugin, manager, player, anchor);
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Hitbox de '" + typeId + "' → " + width + "×" + height
                        + ChatColor.GRAY + " (" + synced + " colocado(s) actualizados en vivo; el resto al cargar su chunk).");
            }
            case "show" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Solo in-game.");
                    return true;
                }
                Interaction anchor = manager.findNearestAnchor(player, 5.0);
                if (anchor == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "No hay ningún mueble a menos de 5 bloques.");
                    return true;
                }
                FurnitureVisualizer.show(plugin, manager, player, anchor);
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Mostrando 10s: " + ChatColor.AQUA + "hitbox"
                        + ChatColor.GREEN + " y " + ChatColor.RED + "barreras" + ChatColor.GREEN + " del mueble.");
            }
            case "info" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Solo in-game.");
                    return true;
                }
                Interaction anchor = manager.findNearestAnchor(player, 5.0);
                if (anchor == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "No hay ningún mueble a menos de 5 bloques.");
                    return true;
                }
                sendInfo(player, anchor);
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
            return filter(Arrays.asList("give", "types", "list", "purge", "seats", "footprint", "hitbox", "show", "info", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("seats")) {
            return filter(Arrays.asList("add", "move", "remove", "list", "save", "cancel"), args[1]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("seats") && args[1].equalsIgnoreCase("move")) {
            return filter(Arrays.asList("x", "y", "z", "yaw"), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("footprint")) {
            return filter(Arrays.asList("add", "clear", "save", "cancel"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(manager.getRegistry().all().stream().map(t -> t.id).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return null; // jugadores online
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("purge"))) {
            return null; // jugadores online
        }
        return Collections.emptyList();
    }

    /** Persist hitbox.width/height of a type to furniture.yml and reload the catalog. */
    private boolean saveHitbox(Player player, String typeId, double width, double height) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "furniture.yml");
        org.bukkit.configuration.file.YamlConfiguration yml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        String basePath = "furniture." + typeId;
        if (!yml.isConfigurationSection(basePath)) {
            player.sendMessage(PREFIX + ChatColor.RED + "'" + typeId + "' ya no está en furniture.yml.");
            return false;
        }
        yml.set(basePath + ".hitbox.width", width);
        yml.set(basePath + ".hitbox.height", height);
        try {
            yml.save(file);
        } catch (Exception e) {
            player.sendMessage(PREFIX + ChatColor.RED + "No se pudo escribir furniture.yml: " + e.getMessage());
            return false;
        }
        manager.getRegistry().load();
        return true;
    }

    private void sendInfo(Player player, Interaction anchor) {
        var pdc = anchor.getPersistentDataContainer();
        String typeId = pdc.get(manager.keyType, PersistentDataType.STRING);
        String instance = pdc.get(manager.keyInstance, PersistentDataType.STRING);
        String ownerStr = pdc.get(manager.keyOwner, PersistentDataType.STRING);
        Float yaw = pdc.get(manager.keyYaw, PersistentDataType.FLOAT);
        String partsCsv = pdc.get(manager.keyParts, PersistentDataType.STRING);
        String barrierCsv = pdc.get(manager.keyBarriers, PersistentDataType.STRING);
        FurnitureType type = manager.getRegistry().byId(typeId);

        String ownerName = ownerStr;
        if (ownerStr != null) {
            try {
                String resolved = Bukkit.getOfflinePlayer(UUID.fromString(ownerStr)).getName();
                if (resolved != null) ownerName = resolved;
            } catch (IllegalArgumentException ignored) {
            }
        }
        int parts = (partsCsv == null || partsCsv.isEmpty()) ? 0 : partsCsv.split(",").length;
        int barriers = (barrierCsv == null || barrierCsv.isEmpty()) ? 0 : barrierCsv.split(";").length;
        Location loc = anchor.getLocation();

        player.sendMessage(PREFIX + ChatColor.GOLD + "Mueble '" + typeId + "'"
                + (type == null ? ChatColor.RED + " (¡ya no está en el catálogo!)" : ""));
        player.sendMessage(ChatColor.GRAY + " Dueño: " + ChatColor.WHITE + ownerName
                + ChatColor.DARK_GRAY + "  yaw " + (yaw != null ? Math.round(yaw) : "?") + "°");
        player.sendMessage(ChatColor.GRAY + " Posición: " + ChatColor.WHITE
                + String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ())
                + ChatColor.DARK_GRAY + " (" + loc.getWorld().getName() + ")");
        player.sendMessage(ChatColor.GRAY + " Piezas: " + ChatColor.WHITE + parts
                + ChatColor.GRAY + "  Barreras: " + ChatColor.WHITE + barriers
                + ChatColor.GRAY + "  Hitbox: " + ChatColor.WHITE
                + anchor.getInteractionWidth() + "×" + anchor.getInteractionHeight());
        if (type != null) {
            player.sendMessage(ChatColor.GRAY + " Interacción: " + ChatColor.WHITE
                    + type.interactionType.name().toLowerCase()
                    + (type.interactionType == FurnitureType.InteractionType.SEAT
                            ? ChatColor.GRAY + " (" + type.seats.size() + " asiento(s))" : "")
                    + (type.animated ? ChatColor.AQUA + "  animado" : "")
                    + (type.solid ? ChatColor.YELLOW + "  sólido" : ""));
        }
        player.sendMessage(ChatColor.DARK_GRAY + " Instancia: " + instance);
    }

    @Nullable
    private Integer parseInt(CommandSender sender, String[] args, int idx, String usage) {
        if (args.length <= idx) {
            sender.sendMessage(PREFIX + ChatColor.RED + usage);
            return null;
        }
        try {
            return Integer.parseInt(args[idx]);
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Número inválido: " + args[idx]);
            return null;
        }
    }

    /** Online exacto primero; si no, el usercache (cualquiera que haya entrado alguna vez). */
    @Nullable
    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) out.add(option);
        }
        return out;
    }
}
