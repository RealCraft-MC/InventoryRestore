package com.artillexstudios.axinventoryrestore.discord.preview;

import org.bukkit.ChatColor;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a backup into readable lines like "1x Netherite Sword (Sharpness V, Unbreaking III)".
 */
public final class ItemSummary {
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    public static List<String> lines(ItemStack[] items) {
        // identical items are merged, in the order they appear in the inventory
        List<ItemStack> types = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            int index = -1;
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).isSimilar(item)) {
                    index = i;
                    break;
                }
            }
            if (index == -1) {
                types.add(item);
                amounts.add(item.getAmount());
            } else {
                amounts.set(index, amounts.get(index) + item.getAmount());
            }
        }

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            lines.add(amounts.get(i) + "x " + describe(types.get(i)));
        }
        return lines;
    }

    private static String describe(ItemStack item) {
        StringBuilder builder = new StringBuilder(pretty(item.getType().getKey().getKey()));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return builder.toString();

        if (meta.hasDisplayName()) {
            builder.append(" \"").append(ChatColor.stripColor(meta.getDisplayName())).append('"');
        }

        Map<Enchantment, Integer> enchants = new LinkedHashMap<>(meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta storage) enchants.putAll(storage.getStoredEnchants());
        if (!enchants.isEmpty()) {
            List<String> names = new ArrayList<>();
            enchants.forEach((enchantment, level) -> {
                String name = pretty(enchantment.getKey().getKey());
                if (enchantment.getMaxLevel() == 1 && level == 1) names.add(name);
                else names.add(name + " " + (level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : level));
            });
            builder.append(" (").append(String.join(", ", names)).append(')');
        }

        try {
            if (meta instanceof BlockStateMeta blockState && blockState.hasBlockState() && blockState.getBlockState() instanceof ShulkerBox shulker) {
                int stacks = 0;
                for (ItemStack content : shulker.getInventory().getContents()) {
                    if (content != null && !content.getType().isAir()) stacks++;
                }
                builder.append(" [").append(stacks).append(" stacks inside]");
            }
        } catch (Exception ignored) {
            // the block state can not always be read outside of the main thread
        }
        return builder.toString();
    }

    private static String pretty(String key) {
        StringBuilder builder = new StringBuilder();
        for (String word : key.split("_")) {
            if (word.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(word.substring(0, 1).toUpperCase(Locale.ENGLISH)).append(word.substring(1));
        }
        return builder.toString();
    }
}
