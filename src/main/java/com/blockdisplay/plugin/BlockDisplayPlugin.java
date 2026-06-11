package com.blockdisplay.plugin;

import com.blockdisplay.plugin.furniture.FootprintEditor;
import com.blockdisplay.plugin.furniture.FurnitureAudit;
import com.blockdisplay.plugin.furniture.FurnitureCommand;
import com.blockdisplay.plugin.furniture.FurnitureGui;
import com.blockdisplay.plugin.furniture.FurnitureItems;
import com.blockdisplay.plugin.furniture.FurnitureListener;
import com.blockdisplay.plugin.furniture.FurnitureManager;
import com.blockdisplay.plugin.furniture.FurnitureRegistry;
import com.blockdisplay.plugin.furniture.MythicHook;
import com.blockdisplay.plugin.furniture.PlacementIndex;
import com.blockdisplay.plugin.furniture.SeatEditor;
import com.blockdisplay.plugin.furniture.SfCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlockDisplayPlugin extends JavaPlugin {

    private ModelManager modelManager;
    private PersistenceManager persistenceManager;
    private AnimationManager animationManager;
    private SilentCommandSender silentSender;
    private CommandFeedbackFilter logFilter;
    private FurnitureManager furnitureManager;
    private SeatEditor seatEditor;
    private FootprintEditor footprintEditor;
    private final Map<UUID, ModelGroup> activeGroups = new HashMap<>();
    // Animated furniture instances currently loaded (adopted vanilla-persistent entities).
    private final Map<UUID, ModelGroup> furnitureAnimGroups = new HashMap<>();

    // Config values
    private int searchRadius;
    private int cleanupRadius;
    private int purgeMaxRadius;
    private int maxModels;
    private boolean autoPlayAnimations;
    private float defaultAnimSpeed;
    private boolean defaultLoopMode;
    private float minAnimSpeed;
    private float maxAnimSpeed;

    @Override
    public void onEnable() {
        // The plugin was renamed SuperBlocksDisplays -> SuperFurnitures: adopt the old data
        // folder (config, library, snapshots) the first time the new name boots.
        migrateOldDataFolder();

        // Save default config if not present and load values
        saveDefaultConfig();
        loadConfigValues();

        // Install Log4j2 filter to suppress "Modified entity data" console spam
        this.logFilter = new CommandFeedbackFilter();
        ((Logger) LogManager.getRootLogger()).addFilter(logFilter);

        this.silentSender = new SilentCommandSender();
        this.modelManager = new ModelManager(this);
        this.persistenceManager = new PersistenceManager(this);

        // Load saved groups, then GC any data/ snapshots that no longer belong to a saved group
        this.persistenceManager.loadSavedGroups();
        this.modelManager.cleanupOrphanSnapshots(persistenceManager.getSavedGroupIds());

        // Start animation task (every tick)
        this.animationManager = new AnimationManager(this);
        this.animationManager.runTaskTimer(this, 1L, 1L);

        // Register command with tab completer
        BdeCommand bdeCommand = new BdeCommand(this);
        getCommand("bde").setExecutor(bdeCommand);
        getCommand("bde").setTabCompleter(bdeCommand);

        // ---- Furniture module ----
        MythicHook mythicHook = new MythicHook(this);
        FurnitureItems furnitureItems = new FurnitureItems(this);
        FurnitureRegistry furnitureRegistry = new FurnitureRegistry(this);
        PlacementIndex placementIndex = new PlacementIndex(this);
        FurnitureAudit furnitureAudit = new FurnitureAudit(this);
        this.furnitureManager = new FurnitureManager(this, furnitureRegistry, placementIndex, mythicHook,
                furnitureItems, furnitureAudit);
        this.seatEditor = new SeatEditor(this, furnitureManager);
        this.footprintEditor = new FootprintEditor(this, furnitureManager);
        getServer().getPluginManager().registerEvents(new FurnitureListener(this, furnitureManager), this);

        FurnitureGui furnitureGui = new FurnitureGui(furnitureManager);
        getServer().getPluginManager().registerEvents(furnitureGui, this);
        SfCommand sfCommand = new SfCommand(this, furnitureManager, seatEditor, footprintEditor, furnitureGui);
        getCommand("sf").setExecutor(sfCommand);
        getCommand("sf").setTabCompleter(sfCommand);
        FurnitureCommand furnitureCommand = new FurnitureCommand(furnitureManager, furnitureGui);
        getCommand("furniture").setExecutor(furnitureCommand);
        getCommand("furniture").setTabCompleter(furnitureCommand);

        logFurnitureHealth(furnitureRegistry, placementIndex);

        // Note: saved models load asynchronously, so they aren't counted here yet
        // (PersistenceManager logs "Loading N saved model(s)..." and one line per model as they arrive).
        getLogger().info("SuperFurnitures enabled.");
    }

    /** Startup health summary: a broken catalog/index should be visible without joining the game. */
    private void logFurnitureHealth(FurnitureRegistry registry, PlacementIndex index) {
        int loadErrors = registry.lastErrors().size();
        var placements = index.all();
        int orphanTypes = 0;
        int missingWorlds = 0;
        for (PlacementIndex.Placement p : placements.values()) {
            if (registry.byId(p.type()) == null) orphanTypes++;
            if (getServer().getWorld(p.world()) == null) missingWorlds++;
        }
        getLogger().info("Muebles: " + registry.all().size() + " tipo(s) en catálogo, "
                + placements.size() + " colocado(s) en el índice.");
        if (loadErrors > 0) {
            getLogger().warning("Muebles: " + loadErrors + " error(es) de config en furniture.yml — revisa con /sf check.");
        }
        if (orphanTypes > 0) {
            getLogger().warning("Muebles: " + orphanTypes + " colocado(s) de tipos fuera del catálogo (no devolverán item).");
        }
        if (missingWorlds > 0) {
            getLogger().warning("Muebles: " + missingWorlds + " colocado(s) en mundos que no están cargados.");
        }
    }

    private void migrateOldDataFolder() {
        File dataFolder = getDataFolder();
        if (dataFolder.exists()) return;
        File old = new File(dataFolder.getParentFile(), "SuperBlocksDisplays");
        if (old.exists() && old.isDirectory()) {
            if (old.renameTo(dataFolder)) {
                getLogger().info("Migrated data folder: SuperBlocksDisplays -> " + dataFolder.getName());
            } else {
                getLogger().warning("Could not migrate old SuperBlocksDisplays data folder; starting fresh.");
            }
        }
    }

    /** Re-read config.yml from disk and apply the new values (used by /bde reload). */
    public void reloadPluginConfig() {
        loadConfigValues();
    }

    private void loadConfigValues() {
        reloadConfig();
        this.searchRadius = getConfig().getInt("search-radius", 15);
        this.cleanupRadius = getConfig().getInt("cleanup-radius", 50);
        this.purgeMaxRadius = getConfig().getInt("purge-max-radius", 10);
        this.maxModels = getConfig().getInt("max-models", -1);
        this.autoPlayAnimations = getConfig().getBoolean("auto-play-animations", true);
        this.defaultAnimSpeed = (float) getConfig().getDouble("default-animation-speed", 1.0);
        this.defaultLoopMode = getConfig().getString("default-animation-mode", "loop").equalsIgnoreCase("loop");
        this.minAnimSpeed = (float) getConfig().getDouble("min-animation-speed", 0.25);
        this.maxAnimSpeed = (float) getConfig().getDouble("max-animation-speed", 4.0);
    }

    @Override
    public void onDisable() {
        if (animationManager != null) {
            animationManager.cancel();
        }
        // Seat stands are session-only helpers; furniture entities themselves persist with chunks.
        if (furnitureManager != null) {
            furnitureManager.removeAllSeats();
        }
        if (seatEditor != null) {
            seatEditor.discardAll();
        }
        if (footprintEditor != null) {
            footprintEditor.discardAll();
        }
        furnitureAnimGroups.clear();

        // Remove all admin-managed (/bde) display entities so they don't duplicate on restart
        int removedCount = 0;
        for (ModelGroup group : activeGroups.values()) {
            removedCount += group.getPartCount();
            group.remove(this);
        }
        activeGroups.clear();
        getLogger().info("Cleaned up " + removedCount + " entities from active models.");

        // Disable our log filter cleanly (stop() prevents it from matching)
        if (logFilter != null) {
            logFilter.stop();
        }
        getLogger().info("SuperFurnitures disabled.");
    }

    // Config getters
    public int getSearchRadius() { return searchRadius; }
    public int getCleanupRadius() { return cleanupRadius; }
    public int getPurgeMaxRadius() { return purgeMaxRadius; }
    public int getMaxModels() { return maxModels; }
    public boolean isAutoPlayAnimations() { return autoPlayAnimations; }
    public float getDefaultAnimSpeed() { return defaultAnimSpeed; }
    public boolean isDefaultLoopMode() { return defaultLoopMode; }
    public float getMinAnimSpeed() { return minAnimSpeed; }
    public float getMaxAnimSpeed() { return maxAnimSpeed; }

    // Service getters
    public ModelManager getModelManager() { return modelManager; }
    public PersistenceManager getPersistenceManager() { return persistenceManager; }
    public AnimationManager getAnimationManager() { return animationManager; }
    public SilentCommandSender getSilentSender() { return silentSender; }
    public Map<UUID, ModelGroup> getActiveGroups() { return activeGroups; }
    public Map<UUID, ModelGroup> getFurnitureAnimGroups() { return furnitureAnimGroups; }
    public FurnitureManager getFurnitureManager() { return furnitureManager; }
    public SeatEditor getSeatEditor() { return seatEditor; }
    public FootprintEditor getFootprintEditor() { return footprintEditor; }

    /**
     * Find a model group by its display name (case-insensitive).
     */
    public ModelGroup findGroupByName(String name) {
        for (ModelGroup group : activeGroups.values()) {
            if (group.getDisplayName().equalsIgnoreCase(name)) {
                return group;
            }
        }
        return null;
    }
}
