package com.artillexstudios.axinventoryrestore.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Metadata of a backup.
 *
 * @param reason   the category, for example DEATH, QUIT or RESTORE_OVERWRITE
 * @param time     creation time in epoch milliseconds
 * @param serverId the server-id of the backend that made the backup, null for backups made without a server-id
 */
public record BackupInfo(int id, @NotNull UUID player, @NotNull String reason, @Nullable String cause, long time,
                         @Nullable String serverId) {
}
