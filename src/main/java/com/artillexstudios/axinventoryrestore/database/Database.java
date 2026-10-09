package com.artillexstudios.axinventoryrestore.database;

import com.artillexstudios.axinventoryrestore.api.BackupInfo;
import com.artillexstudios.axinventoryrestore.backups.Backup;
import com.artillexstudios.axinventoryrestore.backups.BackupData;
import com.artillexstudios.axinventoryrestore.pending.RestoreRequest;
import com.artillexstudios.axinventoryrestore.utils.DynamicWorld;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Database {

    String getType();

    void setup();

    @Nullable
    Integer getUserId(@NotNull UUID uuid);

    @Nullable
    UUID getUserUUID(int id);

    @Nullable
    String getReasonName(int id);

    @Nullable
    Integer getReasonId(@NotNull String reason);

    void saveInventory(@NotNull Player player, @NotNull String reason, @Nullable String cause);

    void saveInventory(ItemStack[] items, @NotNull Player player, @NotNull String reason, @Nullable String cause);

    int storeItems(byte[] items);

    int storeWorld(String world);

    DynamicWorld getWorld(int id);

    void loadBackupsOfPlayer(Backup backup, UUID uuid);

    void loadBackupsFromSearch(Backup backup, long time, @NotNull String search, int limit);

    void join(@NotNull Player player);

    @Nullable
    UUID getUUID(@NotNull String player);

    int addRestoreRequest(int backupId);

    int addRestoreRequest(int backupId, boolean granted, @Nullable String targetServer);

    // false if the request does not exist or was already granted
    boolean grantRestoreRequest(int restoreId);

    // deletes a granted request, only the caller that gets true may execute it
    boolean claimRestoreRequest(int restoreId);

    // deletes a request that is not granted yet
    boolean declineRestoreRequest(int restoreId);

    List<RestoreRequest> getRestoreRequests(@NotNull UUID uuid);

    List<RestoreRequest> getGrantedRestoreRequests(@NotNull Collection<UUID> uuids);

    BackupData getBackupDataById(int backupId);

    @Nullable
    BackupInfo getBackupInfo(int backupId);

    // newest first, limit <= 0 = all
    List<BackupInfo> getBackupInfos(@NotNull UUID uuid, int limit);

    ItemStack[] getItemsFromBackup(int backupId);

    // null reason = any reason
    int getSaves(UUID uuid, @Nullable String reason);

    // null reason = any reason
    void removeLastSaves(UUID uuid, @Nullable String reason, int amount);

    void fetchRestoreRequests(@NotNull UUID uuid);

    boolean removeRestoreRequest(int restoreId);

    void cleanup();

    void disable();
}
