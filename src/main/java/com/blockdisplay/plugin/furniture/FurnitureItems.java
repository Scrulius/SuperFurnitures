package com.blockdisplay.plugin.furniture;

import com.blockdisplay.plugin.BlockDisplayPlugin;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Native furniture items: the plugin builds and recognizes its own placeable items, with no
 * MythicMobs involved. Identity travels in the item's PDC (furniture_item = type id), so the
 * item survives renames of display name/lore and can never collide with another plugin's items.
 * Visuals are pure vanilla: any material, custom name/lore (legacy & codes or MiniMessage),
 * optional enchant glint, and PLAYER_HEAD with a base64 skin texture for fully custom looks
 * without a resource pack.
 */
public class FurnitureItems {

    private final NamespacedKey keyItem;

    public FurnitureItems(BlockDisplayPlugin plugin) {
        this.keyItem = new NamespacedKey(plugin, "furniture_item");
    }

    /** Furniture type id carried by a native item, or null if the stack isn't ours. */
    public String typeIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyItem, PersistentDataType.STRING);
    }

    /** Build the native item of a furniture type (1 unit). Returns null if the type has no item spec. */
    public ItemStack build(FurnitureType type) {
        FurnitureType.ItemSpec spec = type.item;
        if (spec == null) return null;

        ItemStack item = new ItemStack(spec.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Component name = parseText(spec.name);
        if (name == null) {
            name = Component.text(pretty(type.id), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false);
        }
        meta.displayName(name);

        if (!spec.lore.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : spec.lore) {
                Component parsed = parseText(line);
                lore.add(parsed != null ? parsed : Component.empty());
            }
            meta.lore(lore);
        }
        if (spec.glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (!spec.headTexture.isBlank() && meta instanceof SkullMeta skull) {
            // Stable per-type profile UUID so stacks of the same furniture item merge.
            UUID profileId = UUID.nameUUIDFromBytes(
                    ("superfurnitures:" + type.id).getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createProfile(profileId, null);
            profile.setProperty(new ProfileProperty("textures", spec.headTexture));
            skull.setPlayerProfile(profile);
        }
        meta.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, type.id);
        item.setItemMeta(meta);
        return item;
    }

    /** Legacy &-codes if present, MiniMessage otherwise; non-italic by default like vanilla names. */
    private static Component parseText(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Component parsed = (raw.indexOf('&') >= 0)
                ? LegacyComponentSerializer.legacyAmpersand().deserialize(raw)
                : MiniMessage.miniMessage().deserialize(raw);
        return parsed.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String pretty(String id) {
        String clean = id.replace('_', ' ').replace('-', ' ');
        return clean.isEmpty() ? id : Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }
}
