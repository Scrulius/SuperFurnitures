package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Thin bridge to MythicMobs' native item API (`MythicBukkit.inst().getItemManager()`),
 * invoked reflectively so the Maven build doesn't depend on the server's snapshot jar
 * (filename carries a commit hash). The methods used are the stable public entry points.
 */
public class MythicHook {

    private final boolean available;
    private Object itemManager;
    private Method getMythicTypeFromItem;
    private Method getItemStack;

    public MythicHook(BlockDisplayPlugin plugin) {
        boolean ok = false;
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
            try {
                Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                Object inst = mythicBukkit.getMethod("inst").invoke(null);
                this.itemManager = mythicBukkit.getMethod("getItemManager").invoke(inst);
                this.getMythicTypeFromItem = find(itemManager, "getMythicTypeFromItem", ItemStack.class);
                this.getItemStack = find(itemManager, "getItemStack", String.class);
                ok = (getMythicTypeFromItem != null && getItemStack != null);
                if (!ok) {
                    plugin.getLogger().warning("MythicMobs item API methods not found - furniture disabled.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not hook into MythicMobs (" + e.getMessage() + ") - furniture disabled.");
            }
        } else {
            plugin.getLogger().info("MythicMobs not present - furniture system disabled.");
        }
        this.available = ok;
    }

    /** getMethods() includes public methods inherited from interfaces even on non-public impl classes. */
    private static Method find(Object target, String name, Class<?>... params) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), params)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return available;
    }

    /** MythicMobs internal item id of this stack, or null if it isn't a Mythic item. */
    public String getMythicType(ItemStack item) {
        if (!available || item == null) return null;
        try {
            Object result = getMythicTypeFromItem.invoke(itemManager, item);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Build the ItemStack of a MythicMobs item id, or null if it doesn't exist. */
    public ItemStack getItem(String mythicId) {
        if (!available || mythicId == null) return null;
        try {
            Object result = getItemStack.invoke(itemManager, mythicId);
            return (result instanceof ItemStack stack) ? stack : null;
        } catch (Exception e) {
            return null;
        }
    }
}
