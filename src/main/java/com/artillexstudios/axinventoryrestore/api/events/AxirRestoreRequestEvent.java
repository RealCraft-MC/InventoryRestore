package com.artillexstudios.axinventoryrestore.api.events;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Called when the "request restore" button is clicked while restore-requests.handler is EXTERNAL.
 * AxInventoryRestore does nothing else with the request, the listener handles it and informs the requester.
 */
public class AxirRestoreRequestEvent extends Event {
    private static final HandlerList handlerList = new HandlerList();
    private final Player requester;
    private final UUID target;
    private final int backupId;

    public AxirRestoreRequestEvent(@NotNull Player requester, @NotNull UUID target, int backupId) {
        super(!Bukkit.isPrimaryThread());

        this.requester = requester;
        this.target = target;
        this.backupId = backupId;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlerList;
    }

    public static HandlerList getHandlerList() {
        return handlerList;
    }

    @NotNull
    public Player getRequester() {
        return requester;
    }

    // the owner of the backup
    @NotNull
    public UUID getTarget() {
        return target;
    }

    public int getBackupId() {
        return backupId;
    }
}
