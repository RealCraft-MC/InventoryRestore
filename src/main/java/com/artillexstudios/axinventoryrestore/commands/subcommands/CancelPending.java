package com.artillexstudios.axinventoryrestore.commands.subcommands;

import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.queue.Priority;
import org.bukkit.command.CommandSender;

import java.util.Map;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.MESSAGEUTILS;

public enum CancelPending {
    INSTANCE;

    public void execute(CommandSender sender, int id) {
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            boolean removed = AxInventoryRestore.getDatabase().removeRestoreRequest(id);
            MESSAGEUTILS.sendLang(sender, "cancelpending." + (removed ? "success" : "not-found"), Map.of("%id%", Integer.toString(id)));
        }, Priority.HIGH);
    }
}
