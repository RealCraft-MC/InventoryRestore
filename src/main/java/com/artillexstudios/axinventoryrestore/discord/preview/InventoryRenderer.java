package com.artillexstudios.axinventoryrestore.discord.preview;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws a backup like a Minecraft inventory. Text is drawn with a built-in pixel font,
 * because headless servers often have no fonts installed.
 */
public final class InventoryRenderer {
    private static final int SCALE = 3;
    private static final int SLOT = 18 * SCALE;
    private static final int PADDING = 7 * SCALE;
    private static final int GAP = 4 * SCALE;
    private static final int BACKGROUND = 0xFFC6C6C6;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;
    private static final int FOLIAGE = 0x48B518;
    private static final Set<String> FOLIAGE_ITEMS = Set.of("vine", "fern", "large_fern", "grass", "short_grass", "tall_grass", "lily_pad");
    // items that use the texture of another item in the game
    private static final Map<String, String> ALIASES = Map.of("enchanted_golden_apple", "golden_apple");
    // items that always have the enchantment glint in the game
    private static final Set<String> ALWAYS_GLINT = Set.of("enchanted_golden_apple");
    private static final String[] SUFFIXES ={"_slab", "_stairs", "_wall", "_fence_gate", "_fence", "_pressure_plate", "_button", "_pane", "_carpet", "_wood", "_hyphae"};
    // 3x5 pixel digits
    private static final String[] DIGITS = {
            "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
            "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"
    };

    public static byte[] render(ItemStack[] items, boolean enderChest) throws IOException {
        List<int[]> slots = new ArrayList<>(); // {index, x, y}
        int width;
        int height;
        if (!enderChest && items.length >= 41) {
            // armor column, offhand next to the boots, then the main inventory with the hotbar below it
            int mainX = PADDING + 2 * SLOT + GAP;
            for (int row = 0; row < 4; row++) {
                int y = PADDING + row * SLOT + (row == 3 ? GAP : 0);
                slots.add(new int[]{39 - row, PADDING, y});
                for (int col = 0; col < 9; col++) {
                    int index = row == 3 ? col : 9 + row * 9 + col;
                    slots.add(new int[]{index, mainX + col * SLOT, y});
                }
            }
            slots.add(new int[]{40, PADDING + SLOT, PADDING + 3 * SLOT + GAP});
            width = mainX + 9 * SLOT + PADDING;
            height = PADDING * 2 + 4 * SLOT + GAP;
        } else {
            int rows = Math.max(1, (items.length + 8) / 9);
            for (int i = 0; i < rows * 9; i++) {
                slots.add(new int[]{i, PADDING + (i % 9) * SLOT, PADDING + (i / 9) * SLOT});
            }
            width = PADDING * 2 + 9 * SLOT;
            height = PADDING * 2 + rows * SLOT;
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        fill(image, 0, 0, width, height, BACKGROUND);

        for (int[] slot : slots) {
            int x = slot[1];
            int y = slot[2];
            fill(image, x, y, SLOT, SLOT, SLOT_LIGHT);
            fill(image, x, y, SLOT - SCALE, SLOT - SCALE, SLOT_DARK);
            fill(image, x + SCALE, y + SCALE, SLOT - 2 * SCALE, SLOT - 2 * SCALE, SLOT_COLOR);

            ItemStack item = slot[0] < items.length ? items[slot[0]] : null;
            if (item == null || item.getType().isAir()) continue;
            graphics.drawImage(icon(item), x + SCALE, y + SCALE, 16 * SCALE, 16 * SCALE, null);
            drawDurability(image, item, x + SCALE, y + SCALE);
            if (item.getAmount() > 1) drawNumber(image, item.getAmount(), x + SLOT - SCALE, y + SLOT - SCALE);
        }
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage icon(ItemStack item) {
        String key = item.getType().getKey().getKey();
        ItemMeta meta = item.getItemMeta();
        BufferedImage icon = null;

        if (meta instanceof LeatherArmorMeta leather && TextureManager.get("item/" + key) != null) {
            icon = tint(TextureManager.get("item/" + key), leather.getColor().asRGB());
            icon = layer(icon, TextureManager.get("item/" + key + "_overlay"));
        } else if (meta instanceof PotionMeta potion) {
            boolean arrow = item.getType() == Material.TIPPED_ARROW;
            BufferedImage liquid = TextureManager.get(arrow ? "item/tipped_arrow_head" : "item/potion_overlay");
            BufferedImage bottle = TextureManager.get(arrow ? "item/tipped_arrow_base" : "item/" + key);
            if (liquid != null && bottle != null) icon = layer(tint(liquid, potionColor(potion)), bottle);
        }

        if (icon == null) icon = find(ALIASES.getOrDefault(key, key));
        // no texture with the name of the item: follow the item model like the client does (beds, chests, heads, ...)
        if (icon == null) icon = TextureManager.modelIcon(key);
        if (icon == null) return missing();
        if (FOLIAGE_ITEMS.contains(key) || (key.endsWith("_leaves") && !key.contains("cherry") && !key.contains("azalea"))) {
            icon = tint(icon, FOLIAGE);
        }
        if (ALWAYS_GLINT.contains(key) || (meta != null && (meta.hasEnchants() || (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants())))) {
            icon = glint(icon);
        }
        return icon;
    }

    // blocks have no item texture, so use one of their faces
    @Nullable
    private static BufferedImage find(String key) {
        for (String path : new String[]{"item/" + key, "item/" + key + "_00", "item/" + key + "_standby", "block/" + key,
                "block/" + key + "_side", "block/" + key + "_front", "block/" + key + "_top"}) {
            BufferedImage image = TextureManager.get(path);
            if (image != null) return image;
        }

        for (String suffix : SUFFIXES) {
            if (!key.endsWith(suffix)) continue;
            String base = key.substring(0, key.length() - suffix.length());
            String[] candidates = switch (suffix) {
                case "_carpet" -> new String[]{base + "_wool"};
                case "_wood" -> new String[]{base + "_log"};
                case "_hyphae" -> new String[]{base + "_stem"};
                default -> new String[]{base, base + "_planks", base + "s", base + "_block"};
            };
            for (String candidate : candidates) {
                BufferedImage image = TextureManager.get("block/" + candidate);
                if (image != null) return image;
            }
        }
        return null;
    }

    private static int potionColor(PotionMeta potion) {
        if (potion.hasColor()) return potion.getColor().asRGB();
        try {
            Color color = potion.getBasePotionType().getEffectType().getColor();
            return color.asRGB();
        } catch (Throwable ignored) {
            // water, awkward, ... have no effect
            return 0x385DC6;
        }
    }

    private static void drawDurability(BufferedImage image, ItemStack item, int x, int y) {
        int max = item.getType().getMaxDurability();
        if (max <= 0 || !(item.getItemMeta() instanceof Damageable damageable) || damageable.getDamage() <= 0) return;
        float left = Math.max(0, 1f - (float) damageable.getDamage() / max);
        int width = Math.round(13 * left);
        int color = java.awt.Color.HSBtoRGB(left / 3f, 1f, 1f) | 0xFF000000;
        fill(image, x + 2 * SCALE, y + 13 * SCALE, 13 * SCALE, 2 * SCALE, 0xFF000000);
        fill(image, x + 2 * SCALE, y + 13 * SCALE, width * SCALE, SCALE, color);
    }

    // right and bottom are the edges the number is aligned to
    private static void drawNumber(BufferedImage image, int number, int right, int bottom) {
        String text = Integer.toString(number);
        int pixel = SCALE - 1 > 0 ? SCALE - 1 : 1;
        int charWidth = 4 * pixel;
        int x = right - text.length() * charWidth;
        int y = bottom - 5 * pixel - pixel;
        for (char c : text.toCharArray()) {
            String digit = DIGITS[c - '0'];
            for (int i = 0; i < 15; i++) {
                if (digit.charAt(i) != '1') continue;
                int px = x + (i % 3) * pixel;
                int py = y + (i / 3) * pixel;
                fill(image, px + pixel, py + pixel, pixel, pixel, 0xFF3F3F3F);
                fill(image, px, py, pixel, pixel, 0xFFFFFFFF);
            }
            x += charWidth;
        }
    }

    private static BufferedImage tint(BufferedImage source, int rgb) {
        BufferedImage result = copy(source);
        int tr = (rgb >> 16) & 0xFF, tg = (rgb >> 8) & 0xFF, tb = rgb & 0xFF;
        for (int x = 0; x < result.getWidth(); x++) {
            for (int y = 0; y < result.getHeight(); y++) {
                int argb = result.getRGB(x, y);
                int r = ((argb >> 16) & 0xFF) * tr / 255;
                int g = ((argb >> 8) & 0xFF) * tg / 255;
                int b = (argb & 0xFF) * tb / 255;
                result.setRGB(x, y, (argb & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
        return result;
    }

    // lightens the item with purple like the game does, stronger in diagonal stripes for the shimmer
    private static BufferedImage glint(BufferedImage source) {
        BufferedImage result = copy(source);
        int[] glint = {0xA0, 0x60, 0xFF};
        for (int x = 0; x < result.getWidth(); x++) {
            for (int y = 0; y < result.getHeight(); y++) {
                int argb = result.getRGB(x, y);
                if ((argb >>> 24) == 0) continue;
                double strength = Math.floorMod(x - y, 12) < 3 ? 0.65 : 0.25;
                int out = argb & 0xFF000000;
                for (int i = 0; i < 3; i++) {
                    int value = (argb >> (16 - 8 * i)) & 0xFF;
                    // screen blend, this only makes pixels lighter
                    double screen = 255 - (255 - value) * (255 - glint[i]) / 255.0;
                    out |= Math.min(255, (int) Math.round(value + (screen - value) * strength)) << (16 - 8 * i);
                }
                result.setRGB(x, y, out);
            }
        }
        return result;
    }

    private static BufferedImage layer(BufferedImage bottom, @Nullable BufferedImage top) {
        if (top == null) return bottom;
        BufferedImage result = copy(bottom);
        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(top, 0, 0, result.getWidth(), result.getHeight(), null);
        graphics.dispose();
        return result;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return result;
    }

    // the magenta and black texture minecraft shows for missing textures
    private static BufferedImage missing() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, (x < 8) == (y < 8) ? 0xFFF800F8 : 0xFF000000);
            }
        }
        return image;
    }

    private static void fill(BufferedImage image, int x, int y, int width, int height, int argb) {
        for (int px = Math.max(0, x); px < Math.min(image.getWidth(), x + width); px++) {
            for (int py = Math.max(0, y); py < Math.min(image.getHeight(), y + height); py++) {
                image.setRGB(px, py, argb);
            }
        }
    }
}
