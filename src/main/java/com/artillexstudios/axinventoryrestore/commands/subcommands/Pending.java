package com.artillexstudios.axinventoryrestore.commands.subcommands;

import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.pending.RestoreRequest;
import com.artillexstudios.axinventoryrestore.queue.Priority;
import com.artillexstudios.axinventoryrestore.utils.DateUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.LANG;
import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.MESSAGEUTILS;

public enum Pending {
    INSTANCE;

    public void execute(CommandSender sender, String player) {
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            UUID uuid = getUUID(player);
            if (uuid == null) {
                MESSAGEUTILS.sendLang(sender, "errors.unknown-player", Map.of("%number%", "name not found"));
                return;
            }

            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(player);
            List<RestoreRequest> requests = AxInventoryRestore.getDatabase().getRestoreRequests(uuid);
            if (requests.isEmpty()) {
                MESSAGEUTILS.sendLang(sender, "pending.none", Map.of("%player%", name));
                return;
            }

            MESSAGEUTILS.sendLang(sender, "pending.header", Map.of("%player%", name, "%amount%", Integer.toString(requests.size())));
            for (RestoreRequest request : requests) {
                String reason = request.reason() == null ? "---" : request.reason();
                MESSAGEUTILS.sendFormatted(sender, LANG.getString("pending.entry"), Map.of(
                        "%id%", Integer.toString(request.id()),
                        "%date%", DateUtils.formatDate(request.backupDate()),
                        "%category%", LANG.getString("categories." + reason + ".raw", reason),
                        "%granted%", LANG.getString("pending." + (request.granted() ? "yes" : "no")),
                        "%target-server%", request.targetServer() == null || request.targetServer().isBlank() ? "---" : request.targetServer()
                ));
            }
        }, Priority.HIGH);
    }

    @Nullable
    private UUID getUUID(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return AxInventoryRestore.getDatabase().getUUID(value);
        }
    }
}
