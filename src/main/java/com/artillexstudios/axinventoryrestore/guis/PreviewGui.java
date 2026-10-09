package com.artillexstudios.axinventoryrestore.guis;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.ItemBuilder;
import com.artillexstudios.axapi.utils.PaperUtils;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axapi.utils.logging.LogUtils;
import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.api.events.AxirRestoreRequestEvent;
import com.artillexstudios.axinventoryrestore.backups.BackupData;
import com.artillexstudios.axinventoryrestore.discord.DiscordAddon;
import com.artillexstudios.axinventoryrestore.events.AxirEvents;
import com.artillexstudios.axinventoryrestore.pending.PendingRestoreService;
import com.artillexstudios.axinventoryrestore.search.OpenDetails;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.DISCORD;
import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.LANG;
import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.MESSAGEUTILS;

public class PreviewGui {
    private final OpenDetails details;
    private final Gui previewGui;
    private final Player viewer;
    private final BackupData backupData;
    private final PaginatedGui lastGui;
    private final int pageNum;

    public PreviewGui(OpenDetails details, Player viewer, BackupData backupData, PaginatedGui lastGui, int pageNum) {
        this.details = details;
        this.viewer = viewer;
        this.backupData = backupData;
        this.lastGui = lastGui;
        this.pageNum = pageNum;

        previewGui = Gui.gui()
                .title(StringUtils.format(LANG.getString("guis.previewgui.title").replace("%player%", details.getName())))
                .rows(6)
                .create();
    }

    public void open() {
        long time = System.currentTimeMillis();
        if (AxInventoryRestore.isDebugMode()) LogUtils.debug("Opening backup preview for {}", viewer.getName());
        backupData.getItems().thenAccept(items -> {
            if (AxInventoryRestore.isDebugMode()) LogUtils.debug("Preview data loaded for {} in {}ms", viewer.getName(), System.currentTimeMillis() - time);
            int n = -1;

            for (ItemStack it : items) {
                n++;
                if (it == null) continue;
                if (it.getType().isAir()) continue;

                previewGui.setItem(n, new GuiItem(it, event -> {
                    if (details.isRequestOnly() || !viewer.hasPermission("axinventoryrestore.modify")) {
                        event.setCancelled(true);
                        return;
                    }

                    event.setCurrentItem(it);
                }));
            }

            int starter = 46;
            final DiscordAddon discordAddon = AxInventoryRestore.getDiscordAddon();
            final boolean requestButton = discordAddon != null || PendingRestoreService.isExternalRequestHandler();
            if (requestButton) starter = 45;

            previewGui.setItem(starter, new GuiItem(ItemBuilder.create(LANG.getSection("gui-items.back")).get(), event -> {
                lastGui.open(viewer, pageNum);
                event.setCancelled(true);
            }));

            // opened with /axir request: only allow looking at the items and sending a restore request
            if (details.isRequestOnly()) {
                if (requestButton) setRequestButton(starter + 8, discordAddon);
                previewGui.update();
                return;
            }

            previewGui.setItem(starter + 2, new GuiItem(ItemBuilder.create(LANG.getSection("guis.previewgui.teleport"), Map.of("%location%", backupData.getLocation().getReadable())).get(), event -> {
                event.setCancelled(true);

                if (!viewer.hasPermission("axinventoryrestore.teleport")) {
                    MESSAGEUTILS.sendLang(viewer, "errors.no-permission");
                    return;
                }

                Location location = backupData.getLocation().get();
                if (location == null) {
                    MESSAGEUTILS.sendLang(viewer, "errors.world-not-found");
                    return;
                }
                PaperUtils.teleportAsync(viewer, location);
                viewer.closeInventory();
            }));

            boolean isEnder = backupData.getReason().equals("ENDER_CHEST");
            previewGui.setItem(starter + 4, new GuiItem(ItemBuilder.create(LANG.getSection("guis.previewgui.quick-restore" + (isEnder ? "-ender-chest" : ""))).get(), event -> {
                event.setCancelled(true);

                if (!viewer.hasPermission("axinventoryrestore.restore")) {
                    MESSAGEUTILS.sendLang(viewer, "errors.no-permission");
                    return;
                }

                if (AxirEvents.callInventoryRestoreEvent(viewer, backupData)) return;

                Player player = Bukkit.getPlayer(backupData.getPlayerUUID());
                if (player == null) {
                    // not online on this server, execute it when they join
                    PendingRestoreService.queue(viewer, backupData);
                    return;
                }

                Scheduler.get().run(player, task -> {
                    PendingRestoreService.replace(player, isEnder, items, "quick restore by " + viewer.getName());
                }, () -> MESSAGEUTILS.sendLang(viewer, "errors.player-offline"));
            }));

            final int starterFinal = starter;
            backupData.getInShulkers(viewer.getName()).thenAccept(item -> {
                previewGui.setItem(starterFinal + 6, new GuiItem(ItemBuilder.create(LANG.getSection("guis.previewgui.export-as-shulker"), Map.of("%shulker-amount%", Integer.toString(item.size()))).get(), event -> {
                    event.setCancelled(true);

                    if (!viewer.hasPermission("axinventoryrestore.export")) {
                        MESSAGEUTILS.sendLang(viewer, "errors.no-permission");
                        return;
                    }

                    AxirEvents.callBackupExportEvent(viewer, backupData);

                    for (ItemStack i : item) {
                        viewer.getInventory().addItem(i);
                    }
                }));
                previewGui.update();
            });

            if (requestButton) setRequestButton(starter + 8, discordAddon);

            previewGui.update();
        });

        previewGui.open(viewer);
        if (AxInventoryRestore.isDebugMode()) LogUtils.debug("Preview gui opened for {} in {}ms", viewer.getName(), System.currentTimeMillis() - time);
    }

    // discordAddon is null when only the external handler is used
    private void setRequestButton(int slot, DiscordAddon discordAddon) {
        previewGui.setItem(slot, new GuiItem(ItemBuilder.create(DISCORD.getSection("request-restore")).get(), event -> {
            event.setCancelled(true);

            if (!viewer.hasPermission("axinventoryrestore.discord-request")) {
                MESSAGEUTILS.sendLang(viewer, "errors.no-permission");
                return;
            }

            // close the gui so the same backup isn't requested twice by accident
            viewer.closeInventory();
            if (PendingRestoreService.isExternalRequestHandler()) {
                if (AxirRestoreRequestEvent.getHandlerList().getRegisteredListeners().length == 0) {
                    MESSAGEUTILS.sendLang(viewer, "discord-request.no-handler");
                    return;
                }
                Bukkit.getPluginManager().callEvent(new AxirRestoreRequestEvent(viewer, backupData.getPlayerUUID(), backupData.getId()));
                return;
            }
            if (discordAddon == null) {
                MESSAGEUTILS.sendLang(viewer, "errors.discord-disabled");
                return;
            }

            discordAddon.sendRequest((Player) event.getWhoClicked(), backupData).thenAccept(success -> {
                MESSAGEUTILS.sendLang(viewer, "discord-request." + (success ? "success" : "failure"));
            });
        }));
    }

    public Gui getPreviewGui() {
        return previewGui;
    }

    public OpenDetails getDetails() {
        return details;
    }

    public Player getViewer() {
        return viewer;
    }
}
