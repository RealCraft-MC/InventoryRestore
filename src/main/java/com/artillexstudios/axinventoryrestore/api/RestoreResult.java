package com.artillexstudios.axinventoryrestore.api;

import org.jetbrains.annotations.NotNull;

/**
 * @param requestId the id in axir_restorerequests (as shown by /axir pending), -1 if no request was made
 * @param message   human readable explanation, also for successful results
 */
public record RestoreResult(@NotNull Status status, int requestId, @NotNull String message) {

    public enum Status {
        // the items were given to the player on this backend
        APPLIED,
        // the restore is stored and will be applied by the backend it belongs to (join or poll), don't retry
        QUEUED,
        BACKUP_NOT_FOUND,
        // the backup belongs to another backend that can't be reached through the database
        WRONG_SERVER,
        FAILED
    }

    public boolean isSuccess() {
        return status == Status.APPLIED || status == Status.QUEUED;
    }
}
