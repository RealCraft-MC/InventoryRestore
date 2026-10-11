package com.artillexstudios.axinventoryrestore.discord.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Finds the icon of an item the way the client does: the item definition (items/&lt;id&gt;.json, 1.21.4+) points to a
 * model, the model and its parents point to textures. Items the client draws as a 3d model ("special" models: heads,
 * beds, chests, shields) get a flat picture cut out of their entity texture; other models without a flat texture use
 * their particle texture.
 */
public final class ModelResolver {
    // which texture of a model looks most like the item in the inventory, in this order
    private static final String[] TEXTURE_KEYS = {"layer0", "all", "side", "front", "top", "end", "texture", "cross",
            "plant", "pattern", "wool", "particle"};
    // the front of the head in the entity texture of each head kind
    private static final Map<String, Region> HEADS = Map.of(
            "player", new Region("entity/player/wide/steve", 8, 8, 8, 8),
            "skeleton", new Region("entity/skeleton/skeleton", 8, 8, 8, 8),
            "wither_skeleton", new Region("entity/skeleton/wither_skeleton", 8, 8, 8, 8),
            "zombie", new Region("entity/zombie/zombie", 8, 8, 8, 8),
            "creeper", new Region("entity/creeper/creeper", 8, 8, 8, 8),
            "piglin", new Region("entity/piglin/piglin", 8, 8, 10, 8),
            "dragon", new Region("entity/enderdragon/dragon", 128, 46, 16, 16));
    // dye colors as the game tints banners with them
    private static final Map<String, Integer> DYES = Map.ofEntries(
            Map.entry("white", 0xF9FFFE), Map.entry("orange", 0xF9801D), Map.entry("magenta", 0xC74EBD),
            Map.entry("light_blue", 0x3AB3DA), Map.entry("yellow", 0xFED83D), Map.entry("lime", 0x80C71F),
            Map.entry("pink", 0xF38BAA), Map.entry("gray", 0x474F52), Map.entry("light_gray", 0x9D9D97),
            Map.entry("cyan", 0x169C9C), Map.entry("purple", 0x8932B8), Map.entry("blue", 0x3C44AA),
            Map.entry("brown", 0x835432), Map.entry("green", 0x5E7C16), Map.entry("red", 0xB02E26),
            Map.entry("black", 0x1D1D21));
    private static final int MAX_DEPTH = 16;

    private record Region(String texture, int x, int y, int width, int height) {
    }

    private final Function<String, BufferedImage> textures;
    private final Function<String, JsonObject> files;
    private final Map<String, Optional<BufferedImage>> cache = new ConcurrentHashMap<>();

    /**
     * @param textures texture by path like "block/white_wool", null if it does not exist
     * @param files    json file by path without extension like "items/white_bed" or "models/item/white_bed", null if
     *                 it does not exist
     */
    public ModelResolver(Function<String, BufferedImage> textures, Function<String, JsonObject> files) {
        this.textures = textures;
        this.files = files;
    }

    // key is the item id without namespace, like "white_bed"; null if no texture could be found
    @Nullable
    public BufferedImage icon(String key) {
        return cache.computeIfAbsent(key, k -> Optional.ofNullable(resolve(k))).orElse(null);
    }

    @Nullable
    private BufferedImage resolve(String key) {
        String model = null;
        JsonObject definition = files.apply("items/" + key);
        if (definition != null && definition.get("model") instanceof JsonObject root) {
            BufferedImage special = special(root, 0);
            if (special != null) return special;
            model = findModel(root, 0);
        }
        // before 1.21.4 there are no item definitions, the model has the name of the item
        if (model == null) model = "item/" + key;
        return modelTexture(model);
    }

    // ------------------------------------------------------------------ item definitions

    // the first plain model in the definition, following the default branch of selects and conditions
    @Nullable
    private static String findModel(JsonElement element, int depth) {
        if (depth > MAX_DEPTH || element == null) return null;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                String found = findModel(child, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (!(element instanceof JsonObject object)) return null;
        String type = string(object, "type");
        if (type != null && type.endsWith("model") && string(object, "model") != null) return string(object, "model");
        if (string(object, "base") != null) return string(object, "base");
        for (String key : new String[]{"fallback", "on_false", "on_true", "model", "models", "cases", "entries"}) {
            String found = findModel(object.get(key), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    // a flat picture for the special models we know, from their entity texture
    @Nullable
    private BufferedImage special(JsonElement element, int depth) {
        if (depth > MAX_DEPTH || element == null) return null;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                BufferedImage found = special(child, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (!(element instanceof JsonObject object)) return null;
        String type = strip(string(object, "type"));
        if (type != null) {
            BufferedImage image = switch (type) {
                case "player_head" -> head("player");
                case "head" -> head(string(object, "kind"));
                case "bed" -> bed(strip(string(object, "texture")));
                case "chest" -> chest(strip(string(object, "texture")));
                case "shield" -> crop(new Region("entity/shield_base_nopattern", 1, 1, 12, 22));
                case "banner" -> banner(string(object, "color"));
                default -> null;
            };
            if (image != null) return image;
        }
        for (String key : new String[]{"fallback", "on_false", "on_true", "model", "models", "cases", "entries"}) {
            BufferedImage found = special(object.get(key), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private BufferedImage head(@Nullable String kind) {
        Region region = kind == null ? null : HEADS.get(kind);
        return region == null ? null : crop(region);
    }

    // the top of the bed as seen from above: pillow part on top, blanket part below it
    @Nullable
    private BufferedImage bed(@Nullable String color) {
        if (color == null) return null;
        BufferedImage texture = textures.apply("entity/bed/" + color);
        if (texture == null || texture.getWidth() < 64 || texture.getHeight() < 44) return null;
        BufferedImage bed = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = bed.createGraphics();
        graphics.drawImage(texture.getSubimage(6, 6, 16, 16), 0, 0, null);
        graphics.drawImage(texture.getSubimage(6, 28, 16, 16), 0, 16, null);
        graphics.dispose();
        return square(bed);
    }

    // the front of the chest: lid on top of the bottom part, with the lock in the middle
    @Nullable
    private BufferedImage chest(@Nullable String variant) {
        if (variant == null) return null;
        BufferedImage texture = textures.apply("entity/chest/" + variant);
        if (texture == null || texture.getWidth() < 64 || texture.getHeight() < 43) return null;
        BufferedImage chest = new BufferedImage(14, 15, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = chest.createGraphics();
        graphics.drawImage(texture.getSubimage(14, 14, 14, 5), 0, 0, null);
        graphics.drawImage(texture.getSubimage(14, 33, 14, 10), 0, 5, null);
        graphics.drawImage(texture.getSubimage(1, 1, 2, 4), 6, 2, null);
        graphics.dispose();
        return square(chest);
    }

    // the front of the flag, tinted with the color of the banner (patterns are not drawn)
    @Nullable
    private BufferedImage banner(@Nullable String color) {
        Integer rgb = color == null ? null : DYES.get(color);
        BufferedImage flag = crop(new Region("entity/banner_base", 1, 1, 20, 40));
        if (rgb == null || flag == null) return null;
        BufferedImage tinted = new BufferedImage(flag.getWidth(), flag.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < flag.getWidth(); x++) {
            for (int y = 0; y < flag.getHeight(); y++) {
                int argb = flag.getRGB(x, y);
                int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
                int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
                int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
                tinted.setRGB(x, y, (argb & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
        return tinted;
    }

    @Nullable
    private BufferedImage crop(Region region) {
        BufferedImage texture = textures.apply(region.texture());
        if (texture == null || texture.getWidth() < region.x() + region.width()
                || texture.getHeight() < region.y() + region.height()) return null;
        return square(texture.getSubimage(region.x(), region.y(), region.width(), region.height()));
    }

    // pads a picture to a square, centered, so it is not stretched when it is drawn in a slot
    private static BufferedImage square(BufferedImage image) {
        int size = Math.max(image.getWidth(), image.getHeight());
        if (image.getWidth() == size && image.getHeight() == size) return image;
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = square.createGraphics();
        graphics.drawImage(image, (size - image.getWidth()) / 2, (size - image.getHeight()) / 2, null);
        graphics.dispose();
        return square;
    }

    // ------------------------------------------------------------------ models

    // the texture of a model, after following its parents and #references
    @Nullable
    private BufferedImage modelTexture(String model) {
        Map<String, String> slots = new HashMap<>();
        String current = model;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            JsonObject json = files.apply("models/" + strip(current));
            if (json == null) break; // builtin/generated and friends have no file
            if (json.get("textures") instanceof JsonObject texturesObject) {
                for (Map.Entry<String, JsonElement> entry : texturesObject.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) slots.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
            current = string(json, "parent");
        }

        for (String key : TEXTURE_KEYS) {
            BufferedImage image = texture(slots, slots.get(key));
            if (image != null) return image;
        }
        for (String value : slots.values()) {
            BufferedImage image = texture(slots, value);
            if (image != null) return image;
        }
        return null;
    }

    @Nullable
    private BufferedImage texture(Map<String, String> slots, @Nullable String value) {
        for (int i = 0; value != null && value.startsWith("#") && i < MAX_DEPTH; i++) {
            value = slots.get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) return null;
        return textures.apply(strip(value));
    }

    @Nullable
    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    @Nullable
    private static String strip(@Nullable String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
