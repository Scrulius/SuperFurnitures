package com.blockdisplay.plugin;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BdeCommand implements CommandExecutor, TabCompleter {

    private final BlockDisplayPlugin plugin;
    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "SBD" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;

    // Only allow alphanumeric characters and underscores, 2-32 chars
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{2,32}$");

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "spawn", "remove", "tp", "move", "purge", "list", "rotate", "anim", "speed", "info",
            "download", "library", "undownload", "clearcache", "reload", "help"
    );

    private static final List<String> MOVE_DIRECTIONS = Arrays.asList(
            "up", "down", "north", "south", "east", "west", "left", "right", "forward", "back"
    );

    public BdeCommand(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "spawn" -> handleSpawn(player, args);
            case "remove" -> handleRemove(player, args);
            case "tp" -> handleTp(player, args);
            case "move" -> handleMove(player, args);
            case "list" -> handleList(player);
            case "rotate" -> handleRotate(player, args);
            case "anim" -> handleAnim(player, args);
            case "speed" -> handleSpeed(player, args);
            case "info" -> handleInfo(player, args);
            case "purge" -> handlePurge(player, args);
            case "download" -> handleDownload(player, args);
            case "library" -> handleLibrary(player);
            case "undownload" -> handleUndownload(player, args);
            case "clearcache" -> handleClearCache(player);
            case "reload" -> {
                plugin.reloadPluginConfig();
                player.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
            }
            case "help" -> sendHelp(player);
            default -> player.sendMessage(PREFIX + ChatColor.RED + "Unknown command. Use /bde help");
        }

        return true;
    }

    // ========== SPAWN ==========
    private void handleSpawn(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde spawn <model_id|library_name> <name>");
            player.sendMessage(PREFIX + ChatColor.GRAY + "Name must be 2-32 characters, alphanumeric or underscores.");
            return;
        }
        String source = args[1];
        String displayName = args[2];

        // Validate name format
        if (!NAME_PATTERN.matcher(displayName).matches()) {
            player.sendMessage(PREFIX + ChatColor.RED + "Invalid name! Use 2-32 characters: letters, numbers, and underscores only.");
            return;
        }

        // Check for duplicate name
        if (plugin.findGroupByName(displayName) != null) {
            player.sendMessage(PREFIX + ChatColor.RED + "A model named '" + displayName + "' already exists. Choose another name.");
            return;
        }

        // Check max models limit
        int maxModels = plugin.getMaxModels();
        if (maxModels > 0 && plugin.getActiveGroups().size() >= maxModels) {
            player.sendMessage(PREFIX + ChatColor.RED + "Model limit reached (" + maxModels + "). Remove a model first.");
            return;
        }

        // Check library first
        ModelData libraryData = plugin.getModelManager().loadFromLibrary(source);
        if (libraryData != null) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Spawning from library: " + ChatColor.WHITE + source);
            spawnModel(player, libraryData, source, displayName);
            return;
        }

        // Otherwise fetch from API
        player.sendMessage(PREFIX + ChatColor.YELLOW + "Fetching model " + ChatColor.WHITE + source + ChatColor.YELLOW + " from API...");

        plugin.getModelManager().fetchModel(source).thenAccept(modelData -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (modelData == null) {
                    player.sendMessage(PREFIX + ChatColor.RED + "Failed to load model. Check the ID is valid and not expired.");
                    return;
                }
                spawnModel(player, modelData, source, displayName);
            });
        });
    }

    private void spawnModel(Player player, ModelData modelData, String modelId, String displayName) {
        Location loc = player.getLocation();
        ModelGroup group = new ModelGroup(loc, modelId, displayName);
        group.spawn(modelData, plugin);
        plugin.getActiveGroups().put(group.getGroupId(), group);

        if (plugin.isAutoPlayAnimations() && modelData.hasAnimations()) {
            group.setAnimating(true);
            group.setLoopAnim(plugin.isDefaultLoopMode());
            group.setAnimSpeed(plugin.getDefaultAnimSpeed());
        }

        // Snapshot the full model data locally so it re-spawns on restart without touching the API.
        plugin.getModelManager().saveSpawnedData(group.getGroupId(), modelData);
        plugin.getPersistenceManager().saveGroup(group);

        player.sendMessage(PREFIX + ChatColor.GREEN + "Model " + ChatColor.WHITE + displayName
                + ChatColor.GREEN + " spawned! (" + ChatColor.GRAY + modelId + ChatColor.GREEN + ")");

        if (modelData.hasAnimations() && plugin.isAutoPlayAnimations()) {
            player.sendMessage(PREFIX + ChatColor.AQUA + "✦ This model has animations! (auto-playing)");
        }
    }

    // ========== REMOVE ==========
    private void handleRemove(Player player, String[] args) {
        ModelGroup target;

        if (args.length >= 2) {
            if (args[1].equalsIgnoreCase("nearest")) {
                target = getNearestGroup(player);
            } else {
                target = resolveGroup(args[1]);
            }
        } else {
            target = getNearestGroup(player);
        }

        if (target != null) {
            String name = target.getDisplayName();
            target.remove(plugin);
            plugin.getAnimationManager().removeGroup(target.getGroupId());
            plugin.getActiveGroups().remove(target.getGroupId());
            plugin.getModelManager().deleteSpawnedData(target.getGroupId());
            plugin.getPersistenceManager().removeGroup(target.getGroupId());
            player.sendMessage(PREFIX + ChatColor.GREEN + "Model '" + name + "' removed.");
        } else {
            player.sendMessage(PREFIX + ChatColor.RED + "No model found. Use /bde list to see active models.");
        }
    }

    // ========== TP ==========
    private void handleTp(Player player, String[] args) {
        ModelGroup target = (args.length >= 2) ? resolveGroupOrNearest(args[1], player) : getNearestGroup(player);

        if (target == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No model found.");
            return;
        }

        Location loc = target.getOrigin().clone();
        loc.setYaw(player.getLocation().getYaw());
        loc.setPitch(player.getLocation().getPitch());
        player.teleport(loc);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Teleported to '" + ChatColor.WHITE + target.getDisplayName() + ChatColor.GREEN + "'.");
    }

    // ========== MOVE ==========
    private void handleMove(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde move <up|down|north|south|east|west|left|right|forward|back> <distance> [name|nearest]");
            player.sendMessage(PREFIX + ChatColor.GRAY + "Distance accepts decimals, e.g. 0.1 for fine adjustments.");
            return;
        }

        String dir = args[1].toLowerCase();
        if (!MOVE_DIRECTIONS.contains(dir)) {
            player.sendMessage(PREFIX + ChatColor.RED + "Unknown direction '" + args[1] + "'. Use: " + String.join(", ", MOVE_DIRECTIONS));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(PREFIX + ChatColor.RED + "Invalid distance. Use a number, e.g. 0.5");
            return;
        }
        if (!(amount > 0) || amount > 100) {
            player.sendMessage(PREFIX + ChatColor.RED + "Distance must be greater than 0 and at most 100.");
            return;
        }

        ModelGroup target = (args.length >= 4) ? resolveGroupOrNearest(args[3], player) : getNearestGroup(player);
        if (target == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No model found. Specify a name or stand near a model.");
            return;
        }

        double dx = 0, dy = 0, dz = 0;
        switch (dir) {
            case "up" -> dy = amount;
            case "down" -> dy = -amount;
            case "north" -> dz = -amount;
            case "south" -> dz = amount;
            case "east" -> dx = amount;
            case "west" -> dx = -amount;
            default -> {
                // Player-relative, snapped to the nearest cardinal so repeated nudges stay on-grid.
                double rad = Math.toRadians(player.getLocation().getYaw());
                double fx = -Math.sin(rad), fz = Math.cos(rad);
                if (Math.abs(fx) >= Math.abs(fz)) {
                    fx = Math.signum(fx);
                    fz = 0;
                } else {
                    fz = Math.signum(fz);
                    fx = 0;
                }
                switch (dir) {
                    case "forward" -> { dx = fx * amount; dz = fz * amount; }
                    case "back" -> { dx = -fx * amount; dz = -fz * amount; }
                    case "right" -> { dx = -fz * amount; dz = fx * amount; }
                    case "left" -> { dx = fz * amount; dz = -fx * amount; }
                }
            }
        }

        target.move(dx, dy, dz);
        // Compiled fallback commands bake the origin in; recompile if this model ever uses them.
        plugin.getAnimationManager().invalidateCompiled(target.getGroupId());
        plugin.getPersistenceManager().saveGroup(target);

        Location o = target.getOrigin();
        player.sendMessage(PREFIX + ChatColor.GREEN + "Model '" + ChatColor.WHITE + target.getDisplayName()
                + ChatColor.GREEN + "' moved " + ChatColor.WHITE + dir + " " + amount
                + ChatColor.GRAY + String.format(" (now at %.2f, %.2f, %.2f)", o.getX(), o.getY(), o.getZ()));
    }

    // ========== LIST ==========
    private void handleList(Player player) {
        Map<UUID, ModelGroup> groups = plugin.getActiveGroups();
        if (groups.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "No active models.");
            return;
        }

        player.sendMessage(PREFIX + ChatColor.GOLD + "Active Models (" + groups.size() + "):");
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");

        for (Map.Entry<UUID, ModelGroup> entry : groups.entrySet()) {
            ModelGroup g = entry.getValue();
            Location loc = g.getOrigin();
            String coords = String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());

            String animStatus;
            if (g.getModelData() != null && g.getModelData().hasAnimations()) {
                if (g.isAnimating()) {
                    animStatus = ChatColor.GREEN + "▶ " + g.getAnimSpeed() + "x";
                } else {
                    animStatus = ChatColor.YELLOW + "⏸ Paused";
                }
            } else {
                animStatus = ChatColor.GRAY + "Static";
            }

            TextComponent line = new TextComponent(
                    ChatColor.WHITE + " " + g.getDisplayName() + " " +
                    ChatColor.DARK_GRAY + "(" + ChatColor.GRAY + "#" + g.getModelId() + ChatColor.DARK_GRAY + ") " +
                    ChatColor.DARK_GRAY + "@ " + ChatColor.GRAY + coords + " " +
                    animStatus + " " +
                    ChatColor.DARK_GRAY + "[" + g.getPartCount() + " parts]"
            );
            line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/bde info " + g.getDisplayName()));
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click for details")));
            player.spigot().sendMessage(line);
        }
    }

    // ========== ROTATE ==========
    private void handleRotate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde rotate <yaw> [name|nearest]");
            return;
        }

        float yaw;
        try {
            yaw = Float.parseFloat(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(PREFIX + ChatColor.RED + "Invalid yaw. Use a number (0-360).");
            return;
        }

        ModelGroup target = (args.length >= 3) ? resolveGroupOrNearest(args[2], player) : getNearestGroup(player);

        if (target != null) {
            target.setYaw(yaw);
            plugin.getPersistenceManager().saveGroup(target);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Model '" + target.getDisplayName() + "' rotated to " + ChatColor.WHITE + yaw + "°");
        } else {
            player.sendMessage(PREFIX + ChatColor.RED + "No model found. Specify a name or stand near a model.");
        }
    }

    // ========== ANIM ==========
    private void handleAnim(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde anim <play|stop|list> ...");
            return;
        }

        String subAction = args[1].toLowerCase();

        switch (subAction) {
            case "play" -> {
                if (args.length < 3) {
                    player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde anim play <loop|once> [name|nearest] [animation]");
                    return;
                }
                String mode = args[2].toLowerCase();
                if (!mode.equals("loop") && !mode.equals("once")) {
                    player.sendMessage(PREFIX + ChatColor.RED + "You must specify 'loop' or 'once' for the animation mode.");
                    return;
                }
                boolean loop = mode.equals("loop");

                ModelGroup target = (args.length >= 4) ? resolveGroupOrNearest(args[3], player) : getNearestGroup(player);
                if (target == null) {
                    player.sendMessage(PREFIX + ChatColor.RED + "No model found.");
                    return;
                }
                ModelData data = target.getModelData();
                if (data == null || !data.hasAnimations()) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "This model has no animations.");
                    return;
                }

                // Optional named animation as the last argument
                if (args.length >= 5) {
                    String requested = args[4];
                    String match = data.getAnimationNames().stream()
                            .filter(n -> n.equalsIgnoreCase(requested))
                            .findFirst().orElse(null);
                    if (match == null) {
                        player.sendMessage(PREFIX + ChatColor.RED + "Animation '" + requested + "' not found. Available: "
                                + ChatColor.WHITE + String.join(", ", data.getAnimationNames().stream().sorted().toList()));
                        return;
                    }
                    target.setCurrentAnim(match);
                }

                target.setAnimating(true);
                target.setLoopAnim(loop);
                plugin.getAnimationManager().resetTick(target.getGroupId());
                plugin.getPersistenceManager().saveGroup(target);

                String animSuffix = (data.getAnimationNames().size() > 1)
                        ? ChatColor.GRAY + " [" + target.getCurrentAnim() + "]" : "";
                player.sendMessage(PREFIX + ChatColor.GREEN + "Animation started ▶ (" + target.getAnimSpeed() + "x) Mode: "
                        + (loop ? "Loop" : "Once") + animSuffix);
            }
            case "stop" -> {
                ModelGroup target = (args.length >= 3) ? resolveGroupOrNearest(args[2], player) : getNearestGroup(player);
                if (target == null) {
                    player.sendMessage(PREFIX + ChatColor.RED + "No model found.");
                    return;
                }
                target.setAnimating(false);
                plugin.getPersistenceManager().saveGroup(target);
                player.sendMessage(PREFIX + ChatColor.YELLOW + "Animation stopped ⏸");
            }
            case "list" -> {
                ModelGroup target = (args.length >= 3) ? resolveGroupOrNearest(args[2], player) : getNearestGroup(player);
                if (target == null) {
                    player.sendMessage(PREFIX + ChatColor.RED + "No model found.");
                    return;
                }
                ModelData data = target.getModelData();
                if (data == null || !data.hasAnimations()) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "This model has no animations.");
                    return;
                }
                player.sendMessage(PREFIX + ChatColor.GOLD + "Animations of '" + target.getDisplayName() + "' ("
                        + data.getAnimationNames().size() + "):");
                for (String name : data.getAnimationNames().stream().sorted().toList()) {
                    boolean current = name.equals(target.getCurrentAnim());
                    TextComponent line = new TextComponent(
                            ChatColor.WHITE + " " + name
                            + (current ? ChatColor.GREEN + " ← current" : "")
                            + ChatColor.DARK_GRAY + " [" + ChatColor.GREEN + "play" + ChatColor.DARK_GRAY + "]"
                    );
                    line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                            "/bde anim play loop " + target.getDisplayName() + " " + name));
                    line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to play this animation")));
                    player.spigot().sendMessage(line);
                }
            }
            default -> player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde anim <play|stop|list> ...");
        }
    }

    // ========== SPEED ==========
    private void handleSpeed(Player player, String[] args) {
        float minSpeed = plugin.getMinAnimSpeed();
        float maxSpeed = plugin.getMaxAnimSpeed();

        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde speed <" + minSpeed + "-" + maxSpeed + "> [name|nearest]");
            return;
        }

        float speed;
        try {
            speed = Float.parseFloat(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(PREFIX + ChatColor.RED + "Invalid speed. Use a number between " + minSpeed + " and " + maxSpeed);
            return;
        }

        if (speed < minSpeed || speed > maxSpeed) {
            player.sendMessage(PREFIX + ChatColor.RED + "Speed must be between " + minSpeed + " and " + maxSpeed);
            return;
        }

        ModelGroup target = (args.length >= 3) ? resolveGroupOrNearest(args[2], player) : getNearestGroup(player);

        if (target == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No model found.");
            return;
        }

        if (target.getModelData() == null || !target.getModelData().hasAnimations()) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "This model has no animations to adjust speed for.");
            return;
        }

        target.setAnimSpeed(speed);
        plugin.getAnimationManager().resetTick(target.getGroupId());
        plugin.getPersistenceManager().saveGroup(target);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Animation speed for '" + target.getDisplayName() + "' set to " + ChatColor.WHITE + speed + "x");
    }

    // ========== INFO ==========
    private void handleInfo(Player player, String[] args) {
        ModelGroup target = (args.length >= 2) ? resolveGroupOrNearest(args[1], player) : getNearestGroup(player);

        if (target == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No model found.");
            return;
        }

        Location loc = target.getOrigin();
        ModelData data = target.getModelData();

        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");
        player.sendMessage(PREFIX + ChatColor.GOLD + "Model Info");
        player.sendMessage(ChatColor.GRAY + " Name: " + ChatColor.WHITE + target.getDisplayName());
        player.sendMessage(ChatColor.GRAY + " Model ID: " + ChatColor.WHITE + target.getModelId());
        player.sendMessage(ChatColor.GRAY + " Group ID: " + ChatColor.DARK_GRAY + target.getGroupId());
        player.sendMessage(ChatColor.GRAY + " Location: " + ChatColor.WHITE + String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ()));
        player.sendMessage(ChatColor.GRAY + " World: " + ChatColor.WHITE + loc.getWorld().getName());
        player.sendMessage(ChatColor.GRAY + " Parts: " + ChatColor.WHITE + target.getPartCount());
        player.sendMessage(ChatColor.GRAY + " Yaw: " + ChatColor.WHITE + target.getYawOffset() + "°");

        if (data != null && data.hasAnimations()) {
            boolean multi = data.getAnimationNames().size() > 1;
            String animLine = target.isAnimating()
                    ? ChatColor.GREEN + "Playing ▶ " + ChatColor.WHITE + target.getAnimSpeed() + "x " + ChatColor.GRAY + "(" + (target.isLoopAnim() ? "Loop" : "Once") + ")"
                    : ChatColor.YELLOW + "Stopped ⏸";
            if (multi && target.getCurrentAnim() != null) {
                animLine += ChatColor.GRAY + " [" + target.getCurrentAnim() + "]";
            }
            player.sendMessage(ChatColor.GRAY + " Animation: " + animLine);
            if (multi) {
                player.sendMessage(ChatColor.GRAY + " Available: " + ChatColor.WHITE
                        + String.join(", ", data.getAnimationNames().stream().sorted().toList()));
            }
        } else {
            player.sendMessage(ChatColor.GRAY + " Animation: " + ChatColor.DARK_GRAY + "None");
        }

        if (data != null && data.content != null && data.content.version != null) {
            player.sendMessage(ChatColor.GRAY + " MC Version: " + ChatColor.WHITE + data.content.version);
        }
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");
    }

    // ========== PURGE ==========
    private void handlePurge(Player player, String[] args) {
        int maxRadius = plugin.getPurgeMaxRadius();

        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde purge <1-" + maxRadius + ">");
            return;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(PREFIX + ChatColor.RED + "Invalid radius. Use a number between 1 and " + maxRadius + ".");
            return;
        }

        if (radius < 1 || radius > maxRadius) {
            player.sendMessage(PREFIX + ChatColor.RED + "Radius must be between 1 and " + maxRadius + ".");
            return;
        }

        int removed = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            // Interaction covers model hitboxes, which aren't Display entities and would
            // otherwise survive a purge as invisible orphans.
            if (entity instanceof Display || entity instanceof Interaction) {
                entity.remove();
                removed++;
            }
        }

        player.sendMessage(PREFIX + ChatColor.GREEN + "Purged " + ChatColor.WHITE + removed
                + ChatColor.GREEN + " display entities within " + ChatColor.WHITE + radius + ChatColor.GREEN + " blocks.");
    }

    // ========== DOWNLOAD ==========
    private void handleDownload(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde download <model_id> <library_name>");
            return;
        }
        String modelId = args[1];
        String libraryName = args[2];

        if (!NAME_PATTERN.matcher(libraryName).matches()) {
            player.sendMessage(PREFIX + ChatColor.RED + "Invalid name! Use 2-32 characters: letters, numbers, and underscores only.");
            return;
        }

        if (plugin.getModelManager().libraryHas(libraryName)) {
            player.sendMessage(PREFIX + ChatColor.RED + "'" + libraryName + "' already exists in the library. Use /bde undownload to remove it first.");
            return;
        }

        player.sendMessage(PREFIX + ChatColor.YELLOW + "Downloading model " + ChatColor.WHITE + modelId + ChatColor.YELLOW + "...");

        plugin.getModelManager().fetchModel(modelId).thenAccept(modelData -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (modelData == null) {
                    player.sendMessage(PREFIX + ChatColor.RED + "Failed to download model. Check the ID is valid and not expired.");
                    return;
                }
                plugin.getModelManager().saveToLibrary(libraryName, modelData);
                player.sendMessage(PREFIX + ChatColor.GREEN + "Model saved to library as '" + ChatColor.WHITE + libraryName + ChatColor.GREEN + "'.");
                player.sendMessage(PREFIX + ChatColor.GRAY + "Use /bde spawn " + libraryName + " <name> to spawn it anytime.");
            });
        });
    }

    // ========== LIBRARY ==========
    private void handleLibrary(Player player) {
        List<String> names = plugin.getModelManager().getLibraryNames();
        if (names.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Library is empty. Use /bde download <id> <name> to save models.");
            return;
        }

        player.sendMessage(PREFIX + ChatColor.GOLD + "Model Library (" + names.size() + "):");
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");

        for (String name : names) {
            TextComponent line = new TextComponent(
                    ChatColor.WHITE + " " + name + " " +
                    ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "spawn" + ChatColor.DARK_GRAY + "] " +
                    ChatColor.DARK_GRAY + "[" + ChatColor.RED + "delete" + ChatColor.DARK_GRAY + "]"
            );
            line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/bde spawn " + name + " "));
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to spawn this model")));
            player.spigot().sendMessage(line);
        }
    }

    // ========== UNDOWNLOAD ==========
    private void handleUndownload(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.RED + "Usage: /bde undownload <library_name>");
            return;
        }
        String libraryName = args[1];

        if (plugin.getModelManager().deleteFromLibrary(libraryName)) {
            player.sendMessage(PREFIX + ChatColor.GREEN + "Removed '" + libraryName + "' from library.");
        } else {
            player.sendMessage(PREFIX + ChatColor.RED + "'" + libraryName + "' not found in library.");
        }
    }

    // ========== CLEARCACHE ==========
    private void handleClearCache(Player player) {
        plugin.getModelManager().clearCache();
        player.sendMessage(PREFIX + ChatColor.GREEN + "Model cache cleared.");
    }

    // ========== HELP ==========
    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");
        player.sendMessage(PREFIX + ChatColor.GOLD + "SuperBlocksDisplays " + ChatColor.GRAY + "by Melonzio");
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");
        player.sendMessage(ChatColor.YELLOW + " /bde spawn <id|lib> <name>" + ChatColor.GRAY + " - Spawn a model");
        player.sendMessage(ChatColor.YELLOW + " /bde remove [name|nearest]" + ChatColor.GRAY + " - Remove model");
        player.sendMessage(ChatColor.YELLOW + " /bde tp [name|nearest]" + ChatColor.GRAY + " - Teleport to model");
        player.sendMessage(ChatColor.YELLOW + " /bde move <dir> <dist> [name]" + ChatColor.GRAY + " - Nudge model precisely");
        player.sendMessage(ChatColor.YELLOW + " /bde list" + ChatColor.GRAY + " - List all active models");
        player.sendMessage(ChatColor.YELLOW + " /bde rotate <yaw> [name|nearest]" + ChatColor.GRAY + " - Rotate a model");
        player.sendMessage(ChatColor.YELLOW + " /bde anim play <loop|once> [name] [anim]" + ChatColor.GRAY + " - Play animation");
        player.sendMessage(ChatColor.YELLOW + " /bde anim stop [name|nearest]" + ChatColor.GRAY + " - Stop animation");
        player.sendMessage(ChatColor.YELLOW + " /bde anim list [name|nearest]" + ChatColor.GRAY + " - List animations");
        player.sendMessage(ChatColor.YELLOW + " /bde speed <0.25-4.0> [name]" + ChatColor.GRAY + " - Set anim speed");
        player.sendMessage(ChatColor.YELLOW + " /bde info [name|nearest]" + ChatColor.GRAY + " - Show model details");
        player.sendMessage(ChatColor.YELLOW + " /bde purge <1-10>" + ChatColor.GRAY + " - Kill all displays in radius");
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");
        player.sendMessage(PREFIX + ChatColor.GOLD + "Library");
        player.sendMessage(ChatColor.YELLOW + " /bde download <id> <name>" + ChatColor.GRAY + " - Save model to library");
        player.sendMessage(ChatColor.YELLOW + " /bde library" + ChatColor.GRAY + " - List saved models");
        player.sendMessage(ChatColor.YELLOW + " /bde undownload <name>" + ChatColor.GRAY + " - Remove from library");
        player.sendMessage(ChatColor.YELLOW + " /bde clearcache" + ChatColor.GRAY + " - Clear API cache");
        player.sendMessage(ChatColor.YELLOW + " /bde reload" + ChatColor.GRAY + " - Reload config.yml");
        player.sendMessage(ChatColor.DARK_GRAY + "────────────────────────────────");
    }

    // ========== TAB COMPLETION ==========
    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filterStartsWith(SUBCOMMANDS, args[0]);
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "remove", "info", "tp" -> {
                if (args.length == 2) {
                    List<String> options = new ArrayList<>();
                    options.add("nearest");
                    options.addAll(getGroupNameSuggestions());
                    return filterStartsWith(options, args[1]);
                }
            }
            case "move" -> {
                if (args.length == 2) return filterStartsWith(MOVE_DIRECTIONS, args[1]);
                if (args.length == 3) return Arrays.asList("0.1", "0.25", "0.5", "1", "2", "5");
                if (args.length == 4) {
                    List<String> options = new ArrayList<>();
                    options.add("nearest");
                    options.addAll(getGroupNameSuggestions());
                    return filterStartsWith(options, args[3]);
                }
            }
            case "rotate" -> {
                if (args.length == 2) return Arrays.asList("0", "45", "90", "135", "180", "225", "270", "315");
                if (args.length == 3) {
                    List<String> options = new ArrayList<>();
                    options.add("nearest");
                    options.addAll(getGroupNameSuggestions());
                    return filterStartsWith(options, args[2]);
                }
            }
            case "anim" -> {
                if (args.length == 2) {
                    return filterStartsWith(Arrays.asList("play", "stop", "list"), args[1]);
                }
                if (args.length == 3) {
                    if (args[1].equalsIgnoreCase("play")) {
                        return filterStartsWith(Arrays.asList("loop", "once"), args[2]);
                    } else if (args[1].equalsIgnoreCase("stop") || args[1].equalsIgnoreCase("list")) {
                        List<String> options = new ArrayList<>();
                        options.add("nearest");
                        options.addAll(getGroupNameSuggestions());
                        return filterStartsWith(options, args[2]);
                    }
                }
                if (args.length == 4) {
                    if (args[1].equalsIgnoreCase("play")) {
                        List<String> options = new ArrayList<>();
                        options.add("nearest");
                        options.addAll(getGroupNameSuggestions());
                        return filterStartsWith(options, args[3]);
                    }
                }
                if (args.length == 5 && args[1].equalsIgnoreCase("play") && sender instanceof Player p) {
                    // Suggest the actual animation names of the model chosen in args[3]
                    ModelGroup g = resolveGroupOrNearest(args[3], p);
                    if (g != null && g.getModelData() != null) {
                        return filterStartsWith(g.getModelData().getAnimationNames().stream().sorted().toList(), args[4]);
                    }
                }
            }
            case "speed" -> {
                if (args.length == 2) return Arrays.asList("0.25", "0.5", "1", "1.5", "2", "3", "4");
                if (args.length == 3) {
                    List<String> options = new ArrayList<>();
                    options.add("nearest");
                    options.addAll(getGroupNameSuggestions());
                    return filterStartsWith(options, args[2]);
                }
            }
            case "purge" -> {
                if (args.length == 2) return Arrays.asList("1", "2", "3", "5", "10");
            }
            case "spawn" -> {
                if (args.length == 2) {
                    List<String> options = new ArrayList<>(plugin.getModelManager().getLibraryNames());
                    options.add("<model_id>");
                    return filterStartsWith(options, args[1]);
                }
                if (args.length == 3) return Collections.singletonList("<name>");
            }
            case "download" -> {
                if (args.length == 2) return Collections.singletonList("<model_id>");
                if (args.length == 3) return Collections.singletonList("<library_name>");
            }
            case "undownload" -> {
                if (args.length == 2) {
                    return filterStartsWith(plugin.getModelManager().getLibraryNames(), args[1]);
                }
            }
        }

        return Collections.emptyList();
    }

    // ========== HELPERS ==========

    /**
     * Resolve a group by name or partial UUID.
     * Priority: exact name match > partial UUID match.
     */
    private ModelGroup resolveGroup(String identifier) {
        // Try exact name match first (case-insensitive)
        ModelGroup byName = plugin.findGroupByName(identifier);
        if (byName != null) return byName;

        // Fallback: try partial UUID match
        return findGroupByPartialId(identifier);
    }

    /**
     * Resolve a group by name, partial UUID, or "nearest" keyword.
     */
    private ModelGroup resolveGroupOrNearest(String identifier, Player player) {
        if (identifier.equalsIgnoreCase("nearest")) {
            return getNearestGroup(player);
        }
        return resolveGroup(identifier);
    }

    private ModelGroup getNearestGroup(Player player) {
        NamespacedKey groupKey = new NamespacedKey(plugin, "group_id");
        Entity nearest = null;
        double minDistance = Double.MAX_VALUE;

        int r = plugin.getSearchRadius();
        for (Entity entity : player.getNearbyEntities(r, r, r)) {
            if (entity instanceof Display && entity.getPersistentDataContainer().has(groupKey, PersistentDataType.STRING)) {
                double dist = entity.getLocation().distanceSquared(player.getLocation());
                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = entity;
                }
            }
        }

        if (nearest != null) {
            String uuidStr = nearest.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
            if (uuidStr != null) {
                return plugin.getActiveGroups().get(UUID.fromString(uuidStr));
            }
        }
        return null;
    }

    private ModelGroup findGroupByPartialId(String partial) {
        for (Map.Entry<UUID, ModelGroup> entry : plugin.getActiveGroups().entrySet()) {
            if (entry.getKey().toString().startsWith(partial)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<String> getGroupNameSuggestions() {
        return plugin.getActiveGroups().values().stream()
                .map(ModelGroup::getDisplayName)
                .collect(Collectors.toList());
    }

    private List<String> filterStartsWith(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
