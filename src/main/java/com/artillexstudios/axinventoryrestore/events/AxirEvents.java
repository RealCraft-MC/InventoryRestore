package com.artillexstudios.axinventoryrestore.events;

import com.artillexstudios.axinventoryrestore.api.events.InventoryBackupEvent;
import com.artillexstudios.axinventoryrestore.api.events.InventoryRestoreEvent;
import com.artillexstudios.axinventoryrestore.backups.BackupData;
import com.artillexstudios.axinventoryrestore.utils.DateUtils;
import com.artillexstudios.axinventoryrestore.utils.DynamicLocation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.LANG;

public class AxirEvents {

    public static boolean callInventoryBackupEvent(@NotNull Player player, @NotNull String category, @Nullable String extraInfo) {
        final InventoryBackupEvent inventoryBackupEvent = new InventoryBackupEvent(player, category, extraInfo);
        Bukkit.getPluginManager().callEvent(inventoryBackupEvent);
        Webhooks.sendBackupWebhook(
                Map.of("%player%", player.getName(),
                    "%category%", LANG.getString("categories." + category + ".raw", category),
                    "%extrainfo%", extraInfo == null ? "---" : extraInfo,
                    "%location%", DynamicLocation.of(player.getLocation()).getReadable(),
                    "%date%", DateUtils.formatDate(System.currentTimeMillis())
                )
        );
        return inventoryBackupEvent.isCancelled();
    }

    public static boolean callInventoryRestoreEvent(@NotNull Player restorer, @NotNull BackupData backupData) {
        final InventoryRestoreEvent inventoryRestoreEvent = new InventoryRestoreEvent(restorer, backupData);
        Bukkit.getPluginManager().callEvent(inventoryRestoreEvent);
        sendRestoreWebhook(restorer.getName(), backupData);
        return inventoryRestoreEvent.isCancelled();
    }

    public static void sendRestoreWebhook(@NotNull String restorer, @NotNull BackupData backupData) {
        Webhooks.sendRestoreWebhook(
                Map.of("%restorer%", restorer,
                        "%player%", backupData.getPlayerName(),
                        "%category%", LANG.getString("categories." + backupData.getReason() + ".raw", backupData.getReason()),
                        "%extrainfo%", backupData.getCause() == null ? "---" : backupData.getCause(),
                        "%location%", backupData.getLocation().getReadable(),
                        "%date%", DateUtils.formatDate(backupData.getDate())
                )
        );
    }

    public static void callBackupExportEvent(@NotNull Player restorer, @NotNull BackupData backupData) {
        Webhooks.sendExportWebhook(
                Map.of(
                        "%restorer%", restorer.getName(),
                        "%category%", LANG.getString("categories." + backupData.getReason() + ".raw", backupData.getReason()),
                        "%player%", backupData.getPlayerName(),
                        "%extrainfo%", backupData.getCause() == null ? "---" : backupData.getCause(),
                        "%location%", backupData.getLocation().getReadable(),
                        "%date%", DateUtils.formatDate(backupData.getDate())
                )
        );
    }
}
