package com.artillexstudios.axinventoryrestore.discord.preview;

import com.artillexstudios.axinventoryrestore.backups.BackupData;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.DISCORD;

/**
 * The item list and picture of a backup that are added to a discord restore request.
 */
public record InventoryPreview(@Nullable byte[] image, List<String> lines) {
    private static final Logger log = LoggerFactory.getLogger(InventoryPreview.class);
    private static final String IMAGE_FILE = "inventory.png";
    private static final String LIST_FILE = "items.txt";

    // completes with null if the preview is disabled or failed, the request should be sent without it then
    public static CompletableFuture<InventoryPreview> create(BackupData backupData) {
        if (!DISCORD.getBoolean("inventory-preview.enabled", true)) return CompletableFuture.completedFuture(null);

        return backupData.getItems().thenApplyAsync(items -> {
            List<String> lines = ItemSummary.lines(items);
            byte[] image = null;
            if (DISCORD.getBoolean("inventory-preview.image", true) && TextureManager.isReady()) {
                try {
                    image = InventoryRenderer.render(items, "ENDER_CHEST".equals(backupData.getReason()));
                } catch (Throwable throwable) {
                    log.warn("Failed to render the inventory preview of backup {}!", backupData.getId(), throwable);
                }
            }
            return new InventoryPreview(image, lines);
        }).exceptionally(throwable -> {
            log.warn("Failed to create the inventory preview of backup {}!", backupData.getId(), throwable);
            return null;
        });
    }

    // returns the embed with the item list and picture, the files that have to be sent with it are added to files
    public MessageEmbed apply(MessageEmbed embed, List<FileUpload> files) {
        EmbedBuilder builder = new EmbedBuilder(embed);

        if (!lines.isEmpty()) {
            StringBuilder value = new StringBuilder();
            int shown = 0;
            for (String line : lines) {
                String escaped = MarkdownSanitizer.escape(line);
                // keep room for the "... and x more" line
                if (value.length() + escaped.length() + 1 > MessageEmbed.VALUE_MAX_LENGTH - 50) break;
                if (!value.isEmpty()) value.append('\n');
                value.append(escaped);
                shown++;
            }
            if (shown < lines.size()) {
                value.append("\n... and ").append(lines.size() - shown).append(" more (see ").append(LIST_FILE).append(')');
                files.add(FileUpload.fromData(String.join("\n", lines).getBytes(StandardCharsets.UTF_8), LIST_FILE));
            }
            builder.addField(DISCORD.getString("inventory-preview.items-field-name", "Items"), value.toString(), false);
        }

        if (image != null) {
            files.add(FileUpload.fromData(image, IMAGE_FILE));
            builder.setImage("attachment://" + IMAGE_FILE);
        }
        return builder.build();
    }
}
