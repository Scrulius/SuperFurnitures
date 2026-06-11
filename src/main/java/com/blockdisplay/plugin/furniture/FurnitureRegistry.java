package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import com.blockdisplay.plugin.ModelData;
import com.blockdisplay.plugin.ModelGroup;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads furniture.yml: the furniture catalog, limits and module settings.
 * Each valid type gets its library ModelData cached here (used for spawning AND for
 * re-binding animations on chunk load), with a part-count sanity warning — every part
 * is an entity the client must receive and render, so oversized models are flagged.
 */
public class FurnitureRegistry {

    private final BlockDisplayPlugin plugin;
    private final Map<String, FurnitureType> byId = new LinkedHashMap<>();
    private final Map<String, FurnitureType> byMythicItem = new HashMap<>();
    private final Map<String, ModelData> modelCache = new HashMap<>();
    /** Errors of the LAST load, kept so /sf reload and /sf check can show them in-game. */
    private final List<String> lastErrors = new ArrayList<>();

    private int perPlayerDefault = 20;
    private int perChunk = 12;
    private int maxPartsWarn = 100;
    private Set<String> disabledWorlds = Set.of();

    public FurnitureRegistry(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        byId.clear();
        byMythicItem.clear();
        modelCache.clear();
        lastErrors.clear();

        File file = new File(plugin.getDataFolder(), "furniture.yml");
        if (!file.exists()) {
            plugin.saveResource("furniture.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        this.perPlayerDefault = config.getInt("limits.per-player-default", 20);
        this.perChunk = config.getInt("limits.per-chunk", 12);
        this.maxPartsWarn = config.getInt("max-parts-warn", 100);
        List<String> worlds = config.getStringList("disabled-worlds");
        Set<String> lowered = new java.util.HashSet<>();
        for (String w : worlds) lowered.add(w.toLowerCase(Locale.ROOT));
        this.disabledWorlds = lowered;

        ConfigurationSection section = config.getConfigurationSection("furniture");
        if (section == null) {
            plugin.getLogger().info("Furniture: catálogo vacío (sin sección 'furniture' en furniture.yml).");
            return;
        }

        List<String> errors = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) continue;

            FurnitureType type = FurnitureType.parse(id.toLowerCase(Locale.ROOT), entry, errors);
            if (type == null) continue;

            ModelData modelData = plugin.getModelManager().loadFromLibrary(type.model);
            if (modelData == null || !modelData.hasPassengers()) {
                errors.add("furniture '" + id + "': el modelo '" + type.model
                        + "' no existe en la library (usa /bde download primero) - mueble omitido");
                continue;
            }

            int parts = ModelGroup.countParts(modelData);
            if (maxPartsWarn > 0 && parts > maxPartsWarn) {
                plugin.getLogger().warning("Furniture '" + id + "': el modelo tiene " + parts
                        + " piezas (umbral " + maxPartsWarn + ") - cada unidad colocada son " + parts
                        + " entities que el cliente renderiza. Considera un modelo más ligero.");
            }

            if (type.animated && !modelData.hasAnimations()) {
                plugin.getLogger().warning("Furniture '" + id + "': animated=true pero el modelo no trae animaciones.");
            }

            if (type.mythicItem != null) {
                FurnitureType clash = byMythicItem.get(type.mythicItem.toLowerCase(Locale.ROOT));
                if (clash != null) {
                    errors.add("furniture '" + id + "': mythic-item '" + type.mythicItem
                            + "' ya lo usa '" + clash.id + "' - mueble omitido");
                    continue;
                }
                byMythicItem.put(type.mythicItem.toLowerCase(Locale.ROOT), type);
            }

            byId.put(type.id, type);
            modelCache.put(type.id, modelData);
        }

        lastErrors.addAll(errors);
        for (String error : errors) {
            plugin.getLogger().warning("Furniture config: " + error);
        }
        plugin.getLogger().info("Furniture: " + byId.size() + " mueble(s) registrados.");
    }

    /** Errors collected by the last load() (also logged to console). */
    public List<String> lastErrors() {
        return List.copyOf(lastErrors);
    }

    public FurnitureType byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public FurnitureType byMythicItem(String mythicId) {
        return mythicId == null ? null : byMythicItem.get(mythicId.toLowerCase(Locale.ROOT));
    }

    public ModelData modelData(String typeId) {
        return modelCache.get(typeId);
    }

    public Collection<FurnitureType> all() {
        return byId.values();
    }

    public int getPerPlayerDefault() { return perPlayerDefault; }
    public int getPerChunk() { return perChunk; }

    public boolean isWorldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
