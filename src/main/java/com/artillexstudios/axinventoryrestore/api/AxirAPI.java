package com.artillexstudios.axinventoryrestore.api;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Restore api for other plugins, registered in the Bukkit ServicesManager:
 * {@code Bukkit.getServicesManager().load(AxirAPI.class)}.
 * <p>
 * Every method can be called from any thread. The database work runs on the AxInventoryRestore datastore thread,
 * the futures complete on that thread or on the thread that owns the player, so don't block in callbacks.
 */
public interface AxirAPI {

    /**
     * The newest backups of a player, newest first. Only metadata, no items.
     *
     * @param limit maximum amount of backups, 0 or less for all of them
     */
    CompletableFuture<List<BackupInfo>> listBackups(@NotNull UUID player, int limit);

    CompletableFuture<Optional<BackupInfo>> getBackup(int backupId);

    /**
     * The items of a backup, for a read-only preview. Same slot order as {@code PlayerInventory#getContents()}:
     * 0-8 hotbar, 9-35 main inventory, 36-39 armor (boots, leggings, chestplate, helmet), 40 offhand.
     * {@code ENDER_CHEST} backups hold the 27 ender chest slots instead. Empty slots are null.
     * The items are copies: changing them does not change the backup. Empty if the backup does not exist.
     */
    CompletableFuture<Optional<ItemStack[]>> getItems(int backupId);

    /**
     * Restores a backup without asking for approval again, the same way {@code /axir pending} requests are executed:
     * applied right away if the player is online on the backend the backup belongs to, otherwise on their next join there.
     * In REPLACE mode the current items are saved as RESTORE_OVERWRITE first.
     * A backup that is already queued or being restored is not queued a second time.
     *
     * @param target the player that gets the items, must be the owner of the backup
     * @param actor  the staff member responsible, null for console/automation
     * @param source free text for the log, for example "stafftools request #12"
     */
    CompletableFuture<RestoreResult> queueRestore(int backupId, @NotNull UUID target, @Nullable UUID actor, @NotNull String source);
}
