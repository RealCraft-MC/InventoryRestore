package com.artillexstudios.axinventoryrestore.discord.preview;

import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.DISCORD;

/**
 * Item textures are not part of the server, so they are taken from the official Minecraft client jar,
 * which is downloaded from Mojang once and only the item, block and entity textures and the item definitions and models
 * are kept (the models are used by {@link ModelResolver} for items that have no flat texture, like beds and heads).
 * Files are stored in plugins/AxInventoryRestore/textures/{item,block,entity}/*.png, textures/items/*.json and
 * textures/models/{item,block}/*.json
 */
public final class TextureManager {
    private static final Logger log = LoggerFactory.getLogger(TextureManager.class);
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Pattern TEXTURE = Pattern.compile("assets/minecraft/textures/((item|block)/[a-z0-9_]+|entity/[a-z0-9_/]+)\\.png");
    private static final Pattern DEFINITION = Pattern.compile("assets/minecraft/(items|models/(item|block))/[a-z0-9_]+\\.json");
    private static final Map<String, Optional<BufferedImage>> cache = new ConcurrentHashMap<>();
    private static volatile boolean ready = false;
    private static volatile ModelResolver models = new ModelResolver(TextureManager::get, TextureManager::json);

    public static File getFolder() {
        return new File(AxInventoryRestore.getInstance().getDataFolder(), "textures");
    }

    public static boolean isReady() {
        return ready;
    }

    public static void init() {
        cache.clear();
        models = new ModelResolver(TextureManager::get, TextureManager::json);
        ready = hasTextures();

        String version = Bukkit.getBukkitVersion().split("-")[0];
        if (!DISCORD.getBoolean("inventory-preview.download-textures", true)) {
            if (!ready) log.warn("No item textures found in {}, the inventory preview image is disabled.", getFolder());
            return;
        }
        // folders from before the models were kept are downloaded again once
        if (ready && version.equals(readVersion()) && hasModels()) return;

        Thread thread = new Thread(() -> download(version), "AxInventoryRestore-TextureDownload");
        thread.setDaemon(true);
        thread.start();
    }

    // path is like "item/diamond_sword", returns null if the texture does not exist
    @Nullable
    public static BufferedImage get(String path) {
        if (!ready) return null;
        return cache.computeIfAbsent(path, key -> {
            File file = new File(getFolder(), key + ".png");
            if (!file.isFile()) return Optional.empty();
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null) return Optional.empty();
                // animated textures are vertical strips of frames, only keep the first frame
                if (image.getHeight() > image.getWidth()) image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                return Optional.of(image);
            } catch (IOException exception) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    // the icon of an item that has no texture with its own name (beds, chests, heads, ...), see ModelResolver
    @Nullable
    public static BufferedImage modelIcon(String key) {
        if (!ready) return null;
        return models.icon(key);
    }

    // json file in the textures folder, path without extension like "items/white_bed"; null if it does not exist
    @Nullable
    static JsonObject json(String path) {
        File file = new File(getFolder(), path + ".json");
        if (!file.isFile()) return null;
        try {
            return JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean hasModels() {
        String[] files = new File(getFolder(), "items").list();
        return files != null && files.length > 0;
    }

    private static boolean hasTextures() {
        String[] files = new File(getFolder(), "item").list();
        return files != null && files.length > 0;
    }

    @Nullable
    private static String readVersion() {
        try {
            return Files.readString(new File(getFolder(), "version.txt").toPath()).trim();
        } catch (IOException exception) {
            return null;
        }
    }

    private static void download(String version) {
        log.info("Downloading item textures for Minecraft {} from Mojang for the discord inventory preview...", version);
        Path jar = new File(getFolder(), "client.jar.tmp").toPath();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()) {
            JsonObject versionInfo = null;
            for (JsonElement element : getJson(client, MANIFEST).getAsJsonArray("versions")) {
                JsonObject object = element.getAsJsonObject();
                if (object.get("id").getAsString().equals(version)) {
                    versionInfo = getJson(client, object.get("url").getAsString());
                    break;
                }
            }
            if (versionInfo == null) {
                log.warn("Minecraft version {} was not found in the Mojang version manifest, the inventory preview image is disabled.", version);
                return;
            }

            JsonObject clientJar = versionInfo.getAsJsonObject("downloads").getAsJsonObject("client");
            Files.createDirectories(jar.getParent());
            HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(clientJar.get("url").getAsString())).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofFile(jar));
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " while downloading the client jar");
            if (!sha1(jar).equalsIgnoreCase(clientJar.get("sha1").getAsString())) throw new IOException("checksum of the downloaded client jar does not match");

            int extracted = 0;
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String relative;
                    if (TEXTURE.matcher(entry.getName()).matches()) {
                        relative = entry.getName().substring("assets/minecraft/textures/".length());
                    } else if (DEFINITION.matcher(entry.getName()).matches()) {
                        relative = entry.getName().substring("assets/minecraft/".length());
                    } else {
                        continue;
                    }
                    Path target = getFolder().toPath().resolve(relative);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    extracted++;
                }
            }

            if (extracted == 0) {
                log.warn("The Minecraft {} client contains no item textures in the expected place, the inventory preview image is disabled.", version);
                return;
            }

            Files.writeString(new File(getFolder(), "version.txt").toPath(), version, StandardCharsets.UTF_8);
            cache.clear();
            models = new ModelResolver(TextureManager::get, TextureManager::json);
            ready = hasTextures();
            log.info("Downloaded {} item textures and models for the discord inventory preview.", extracted);
        } catch (Exception exception) {
            log.warn("Failed to download item textures, the inventory preview image is disabled until the next restart.", exception);
        } finally {
            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
            }
        }
    }

    private static JsonObject getJson(HttpClient client, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
