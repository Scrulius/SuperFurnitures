package com.blockdisplay.plugin.furniture;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One immutable entry of the furniture catalog (furniture.yml). */
public class FurnitureType {

    public enum Anchor { FLOOR, WALL, CEILING }

    public enum InteractionType { NONE, SEAT, MENU, COMMANDS }

    /** Native item definition (item built by the plugin itself; no MythicMobs needed). */
    public static class ItemSpec {
        public final Material material;
        public final String name;           // legacy &-codes or MiniMessage; "" = pretty type id
        public final List<String> lore;
        public final boolean glow;
        public final String headTexture;    // base64 "textures" property (PLAYER_HEAD only)

        ItemSpec(Material material, String name, List<String> lore, boolean glow, String headTexture) {
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.glow = glow;
            this.headTexture = headTexture;
        }
    }

    public final String id;
    public final String model;          // library model name
    public final String mythicItem;     // MythicMobs item id (null = native item only)
    public final ItemSpec item;         // native item spec (null = MythicMobs item only)
    public final String permission;     // extra per-furniture permission ("" = none)
    public final int maxPerPlayer;      // per-type per-player cap (-1 = only the global limit)
    public final Anchor anchor;
    public final boolean solid;
    public final boolean animated;
    public final float hitboxWidth;
    public final float hitboxHeight;
    public final InteractionType interactionType;
    public final List<double[]> seats;      // local-space seat offsets (x, y, z[, yawRelativo]); +z = facing
    public final String menu;               // DeluxeMenus menu name
    public final List<String> commands;     // "player: cmd" / "console: cmd"
    public final List<int[]> footprint;     // local-space barrier offsets (solid only)
    public final String placeSound;
    public final String pickupSound;

    private FurnitureType(String id, String model, String mythicItem, ItemSpec item, String permission,
                          int maxPerPlayer, Anchor anchor,
                          boolean solid, boolean animated, float hitboxWidth, float hitboxHeight,
                          InteractionType interactionType, List<double[]> seats, String menu,
                          List<String> commands, List<int[]> footprint, String placeSound, String pickupSound) {
        this.id = id;
        this.model = model;
        this.mythicItem = mythicItem;
        this.item = item;
        this.permission = permission;
        this.maxPerPlayer = maxPerPlayer;
        this.anchor = anchor;
        this.solid = solid;
        this.animated = animated;
        this.hitboxWidth = hitboxWidth;
        this.hitboxHeight = hitboxHeight;
        this.interactionType = interactionType;
        this.seats = seats;
        this.menu = menu;
        this.commands = commands;
        this.footprint = footprint;
        this.placeSound = placeSound;
        this.pickupSound = pickupSound;
    }

    /** @return the parsed type, or null (with the reason in {@code errors}) if the entry is invalid. */
    static FurnitureType parse(String id, ConfigurationSection s, List<String> errors) {
        String model = s.getString("model");
        String mythicItem = s.getString("mythic-item");
        if (mythicItem != null && mythicItem.isBlank()) mythicItem = null;
        if (model == null || model.isBlank()) {
            errors.add("furniture '" + id + "': falta 'model'");
            return null;
        }

        ItemSpec itemSpec = parseItemSpec(id, s.getConfigurationSection("item"), errors);
        if (itemSpec == null && s.isConfigurationSection("item")) {
            return null; // the item section exists but is invalid: the error is already logged
        }
        if (mythicItem == null && itemSpec == null) {
            errors.add("furniture '" + id + "': necesita un item — 'item:' (nativo) o 'mythic-item:' (MythicMobs)");
            return null;
        }

        Anchor anchor;
        try {
            anchor = Anchor.valueOf(s.getString("anchor", "floor").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("furniture '" + id + "': anchor inválido (usa floor/wall/ceiling)");
            return null;
        }

        InteractionType interactionType;
        try {
            interactionType = InteractionType.valueOf(
                    s.getString("interaction.type", "none").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("furniture '" + id + "': interaction.type inválido (none/seat/menu/commands)");
            return null;
        }

        List<double[]> seats = parseOffsets(s.getList("interaction.seats"), errors, id, "interaction.seats");
        if (interactionType == InteractionType.SEAT && seats.isEmpty()) {
            seats.add(new double[]{0.0, 0.4, 0.0});
        }

        List<int[]> footprint = new ArrayList<>();
        for (double[] off : parseOffsets(s.getList("footprint"), errors, id, "footprint")) {
            footprint.add(new int[]{(int) Math.round(off[0]), (int) Math.round(off[1]), (int) Math.round(off[2])});
        }

        return new FurnitureType(
                id, model, mythicItem, itemSpec,
                s.getString("permission", ""),
                s.getInt("max-per-player", -1),
                anchor,
                s.getBoolean("solid", false),
                s.getBoolean("animated", false),
                (float) s.getDouble("hitbox.width", 1.0),
                (float) s.getDouble("hitbox.height", 1.0),
                interactionType,
                seats,
                s.getString("interaction.menu", ""),
                new ArrayList<>(s.getStringList("interaction.commands")),
                footprint,
                s.getString("sounds.place", "block.wood.place"),
                s.getString("sounds.pickup", "block.wood.break")
        );
    }

    /**
     * Parses the native item section. Returns null on error (logged in {@code errors}) AND when
     * the section simply isn't there — the caller distinguishes both via isConfigurationSection.
     * A head-texture implies PLAYER_HEAD, so material may be omitted in that case.
     */
    private static ItemSpec parseItemSpec(String id, ConfigurationSection s, List<String> errors) {
        if (s == null) return null;
        String headTexture = s.getString("head-texture", "").trim();
        String materialName = s.getString("material", headTexture.isEmpty() ? "" : "PLAYER_HEAD");
        if (materialName.isBlank()) {
            errors.add("furniture '" + id + "': item.material es obligatorio (o define item.head-texture)");
            return null;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            errors.add("furniture '" + id + "': item.material inválido: " + materialName);
            return null;
        }
        if (!headTexture.isEmpty() && material != Material.PLAYER_HEAD) {
            errors.add("furniture '" + id + "': item.head-texture solo funciona con material PLAYER_HEAD");
            return null;
        }
        return new ItemSpec(material,
                s.getString("name", ""),
                new ArrayList<>(s.getStringList("lore")),
                s.getBoolean("glow", false),
                headTexture);
    }

    /**
     * Accepts "x,y,z" strings or [x, y, z] number lists per element. A 4th component is kept
     * if present (seats use it as a per-seat yaw, relative to the furniture facing).
     */
    private static List<double[]> parseOffsets(List<?> raw, List<String> errors, String id, String path) {
        List<double[]> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object element : raw) {
            try {
                if (element instanceof String str) {
                    String[] split = str.split(",");
                    if (split.length < 3) throw new IllegalArgumentException("faltan componentes");
                    double[] parsed = new double[Math.min(split.length, 4)];
                    for (int i = 0; i < parsed.length; i++) {
                        parsed[i] = Double.parseDouble(split[i].trim());
                    }
                    out.add(parsed);
                } else if (element instanceof List<?> list && list.size() >= 3) {
                    double[] parsed = new double[Math.min(list.size(), 4)];
                    for (int i = 0; i < parsed.length; i++) {
                        parsed[i] = ((Number) list.get(i)).doubleValue();
                    }
                    out.add(parsed);
                }
            } catch (Exception e) {
                errors.add("furniture '" + id + "': offset inválido en " + path + ": " + element);
            }
        }
        return out;
    }
}
