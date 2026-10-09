package com.artillexstudios.axinventoryrestore.commands;

import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.commands.subcommands.CancelPending;
import com.artillexstudios.axinventoryrestore.commands.subcommands.Cleanup;
import com.artillexstudios.axinventoryrestore.commands.subcommands.Help;
import com.artillexstudios.axinventoryrestore.commands.subcommands.Pending;
import com.artillexstudios.axinventoryrestore.commands.subcommands.Reload;
import com.artillexstudios.axinventoryrestore.commands.subcommands.Save;
import com.artillexstudios.axinventoryrestore.commands.subcommands.SaveAll;
import com.artillexstudios.axinventoryrestore.commands.subcommands.Search;
import com.artillexstudios.axinventoryrestore.commands.subcommands.View;
import com.artillexstudios.axinventoryrestore.pending.PendingRestoreService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.AutoComplete;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.DefaultFor;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.MESSAGEUTILS;

@Command({"axinventoryrestore", "axir", "axinvrestore", "invrestore", "inventoryrestore"})
public class Commands {

    @DefaultFor({"~", "~ help"})
    public void help(@NotNull CommandSender sender) {
        Help.INSTANCE.execute(sender);
    }

    @Subcommand("view")
    @CommandPermission("axinventoryrestore.view")
    @AutoComplete("@offlinePlayers")
    public void view(Player sender, String player) {
        View.INSTANCE.execute(sender, player);
    }

    @Subcommand("reload")
    @CommandPermission("axinventoryrestore.reload")
    public void reload(CommandSender sender) {
        Reload.INSTANCE.execute(sender);
    }

    @Subcommand("cleanup")
    @CommandPermission("axinventoryrestore.cleanup")
    public void cleanup(CommandSender sender) {
        Cleanup.INSTANCE.execute(sender);
    }

    @Subcommand("save")
    @CommandPermission("axinventoryrestore.manualbackup")
    public void save(CommandSender sender, Player player) {
        Save.INSTANCE.execute(sender, player);
    }

    @Subcommand("saveall")
    @CommandPermission("axinventoryrestore.manualbackup")
    public void saveAll(CommandSender sender) {
        SaveAll.INSTANCE.execute(sender);
    }

    @Subcommand("search")
    @CommandPermission("axinventoryrestore.search")
    public void search(Player sender, String search) {
        Search.INSTANCE.execute(sender, search);
    }

    @Subcommand("request")
    @CommandPermission("axinventoryrestore.discord-request")
    @AutoComplete("@offlinePlayers")
    public void request(Player sender, String player) {
        if (AxInventoryRestore.getDiscordAddon() == null && !PendingRestoreService.isExternalRequestHandler()) {
            MESSAGEUTILS.sendLang(sender, "errors.discord-disabled");
            return;
        }
        View.INSTANCE.execute(sender, player, true);
    }

    @Subcommand("pending")
    @CommandPermission("axinventoryrestore.restore")
    @AutoComplete("@offlinePlayers")
    public void pending(CommandSender sender, String player) {
        Pending.INSTANCE.execute(sender, player);
    }

    @Subcommand("cancelpending")
    @CommandPermission("axinventoryrestore.restore")
    public void cancelPending(CommandSender sender, int id) {
        CancelPending.INSTANCE.execute(sender, id);
    }
}
