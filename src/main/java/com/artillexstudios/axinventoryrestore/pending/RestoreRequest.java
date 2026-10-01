package com.artillexstudios.axinventoryrestore.pending;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A row of axir_restorerequests, joined with the backup it points to.
 */
public record RestoreRequest(int id, int backupId, boolean granted, @Nullable String targetServer,
                             UUID player, long backupDate, @Nullable String reason) {
}
