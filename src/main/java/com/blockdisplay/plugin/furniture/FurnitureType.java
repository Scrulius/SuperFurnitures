package com.blockdisplay.plugin.furniture;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One immutable entry of the furniture catalog (furniture.yml). */
public class FurnitureType {

    public enum Anchor { FLOOR, WALL, CEILING }

    public enum InteractionType { NONE, SEAT, MENU, COMMANDS }

    public final String id;
    public final String model;          // library model name
    public final String mythicItem;     // MythicMobs item id
    public final String permission;     // extra per-furniture permission ("" = none)
    public final Anchor anchor;
    public final boolean solid;
    public final boolean animated;
    public final float hitboxWidth;
    public final float hitboxHeight;
    public final InteractionType interactionType;
    public final List<double[]> seats;      // local-space seat offsets (x, y, z); +z = facing
    public final String menu;               // DeluxeMenus menu name
    public final List<String> commands;     // "player: cmd" / "console: cmd"
    public final List<int[]> footprint;     // local-space barrier offsets (solid only)
    public final String placeSound;
    public final String pickupSound;

    private FurnitureType(String id, String model, String mythicItem, String permission, Anchor anchor,
                          boolean solid, boolean animated, float hitboxWidth, float hitboxHeight,
                          InteractionType interactionType, List<double[]> seats, String menu,
                          List<String> commands, List<int[]> footprint, String placeSound, String pickupSound) {
        this.id = id;
        this.model = model;
        this.mythicItem = mythicItem;
        this.permission = permission;
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
        if (model == null || model.isBlank()) {
            errors.add("furniture '" + id + "': falta 'model'");
            return null;
        }
        if (mythicItem == null || mythicItem.isBlank()) {
            errors.add("furniture '" + id + "': falta 'mythic-item'");
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
                id, model, mythicItem,
                s.getString("permission", ""),
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

    /** Accepts "x,y,z" strings or [x, y, z] number lists per element. */
    private static List<double[]> parseOffsets(List<?> raw, List<String> errors, String id, String path) {
        List<double[]> out = new ArrayList<>();
        if (raw == null) return out;
        for (Object element : raw) {
            try {
                if (element instanceof String str) {
                    String[] split = str.split(",");
                    out.add(new double[]{
                            Double.parseDouble(split[0].trim()),
                            Double.parseDouble(split[1].trim()),
                            Double.parseDouble(split[2].trim())});
                } else if (element instanceof List<?> list && list.size() >= 3) {
                    out.add(new double[]{
                            ((Number) list.get(0)).doubleValue(),
                            ((Number) list.get(1)).doubleValue(),
                            ((Number) list.get(2)).doubleValue()});
                }
            } catch (Exception e) {
                errors.add("furniture '" + id + "': offset inválido en " + path + ": " + element);
            }
        }
        return out;
    }
}
