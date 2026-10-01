package com.artillexstudios.axinventoryrestore.commands.subcommands;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.guis.MainGui;
import com.artillexstudios.axinventoryrestore.queue.Priority;
import com.artillexstudios.axinventoryrestore.search.OpenDetails;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.MESSAGEUTILS;

public enum View {
    INSTANCE;

    public void execute(Player sender, String player) {
        execute(sender, player, false);
    }

    // requestOnly: opened with /axir request, the gui only allows sending discord requests
    public void execute(Player sender, String player, boolean requestOnly) {
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            UUID uuid = getUUID(player);
            if (uuid == null) {
                MESSAGEUTILS.sendLang(sender, "errors.unknown-player", Map.of("%number%", "name not found"));
                return;
            }

            String name = Bukkit.getOfflinePlayer(uuid).getName();
            OpenDetails details = OpenDetails.player(uuid, Optional.ofNullable(name).orElse(player));
            if (requestOnly) details.requestOnly();
            Scheduler.get().run(sender, task -> {
                new MainGui(
                        details,
                        sender
                ).open();
            }, () -> {});
        }, Priority.HIGH);
    }

    @Nullable
    private UUID getUUID(String value) {
        UUID uuid = tryParseUUID(value);
        if (uuid != null) return uuid;
        uuid = AxInventoryRestore.getDatabase().getUUID(value);
        return uuid;
    }

    @Nullable
    private UUID tryParseUUID(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
