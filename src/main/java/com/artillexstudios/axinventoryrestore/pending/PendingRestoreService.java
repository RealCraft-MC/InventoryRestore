package com.artillexstudios.axinventoryrestore.pending;

import com.artillexstudios.axapi.executor.ExceptionReportingScheduledThreadPool;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.ContainerUtils;
import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.backups.BackupData;
import com.artillexstudios.axinventoryrestore.database.Database;
import com.artillexstudios.axinventoryrestore.events.AxirEvents;
import com.artillexstudios.axinventoryrestore.queue.Priority;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.CONFIG;
import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.MESSAGEUTILS;

/**
 * Executes granted restore requests (Discord accept, queued quick-restores) on the server the player is on.
 * Every route (join, accept, poll) goes through {@link #process(Player, List)}, which claims each request
 * atomically before applying it, so a request is never executed twice.
 */
public final class PendingRestoreService {
    public static final String OVERWRITE_REASON = "RESTORE_OVERWRITE";
    private static final Logger log = LoggerFactory.getLogger(PendingRestoreService.class);
    private static final AtomicBoolean polling = new AtomicBoolean(false);
    private static ExceptionReportingScheduledThreadPool pool = null;

    public static String getServerId() {
        return CONFIG.getString("server-id", "").trim();
    }

    // the value stored in axir_restorerequests.targetServer for requests created on this server
    @Nullable
    public static String getTargetServer() {
        String serverId = getServerId();
        return serverId.isEmpty() ? null : serverId;
    }

    public static boolean isForThisServer(@Nullable String targetServer) {
        if (targetServer == null || targetServer.isBlank()) return true;
        return targetServer.trim().equalsIgnoreCase(getServerId());
    }

    public static boolean isEnderChest(@NotNull BackupData backupData) {
        return "ENDER_CHEST".equals(backupData.getReason());
    }

    // must be called on the threaded queue
    public static void process(@NotNull Player player, @NotNull List<RestoreRequest> requests) {
        final Database database = AxInventoryRestore.getDatabase();
        for (RestoreRequest request : requests) {
            if (!request.granted() || !isForThisServer(request.targetServer())) continue;
            if (!player.isOnline()) return;
            if (!database.claimRestoreRequest(request.id())) continue;

            final BackupData backupData = database.getBackupDataById(request.backupId());
            if (backupData == null) {
                log.warn("Dropped restore request #{} of {}, backup {} does not exist anymore!", request.id(), player.getName(), request.backupId());
                continue;
            }

            apply(player, backupData, request);
        }
    }

    // the request must already be claimed
    public static void apply(@NotNull Player player, @NotNull BackupData backupData, @NotNull RestoreRequest request) {
        final String cause = "request #" + request.id();
        if ("SHULKER".equalsIgnoreCase(CONFIG.getString("pending-restore-mode", "REPLACE"))) {
            backupData.getInShulkers("---").thenAccept(items -> runOnPlayer(player, request, () -> {
                ContainerUtils.INSTANCE.addOrDrop(player.getInventory(), items, player.getLocation());
                finish(player, backupData, cause);
            }));
            return;
        }

        backupData.getItems().thenAccept(items -> runOnPlayer(player, request, () -> {
            replace(player, isEnderChest(backupData), items, cause);
            finish(player, backupData, cause);
        }));
    }

    /**
     * Replaces the inventory (or ender chest) of the player with the items, after backing up the current
     * contents as RESTORE_OVERWRITE. Must be called on the thread that owns the player.
     */
    public static void replace(@NotNull Player player, boolean enderChest, @NotNull ItemStack[] items, @NotNull String cause) {
        final Inventory inventory = enderChest ? player.getEnderChest() : player.getInventory();

        final ItemStack[] current = inventory.getContents();
        for (int i = 0; i < current.length; i++) {
            if (current[i] != null) current[i] = current[i].clone();
        }
        AxInventoryRestore.getDatabase().saveInventory(current, player, OVERWRITE_REASON, cause);

        final int size = Math.min(items.length, inventory.getSize());
        for (int i = 0; i < size; i++) {
            final ItemStack it = items[i];
            inventory.setItem(i, it == null ? new ItemStack(Material.AIR) : it);
        }
    }

    // quick-restore for a player who is not online on this server
    public static void queue(@NotNull Player staff, @NotNull BackupData backupData) {
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            final Database database = AxInventoryRestore.getDatabase();
            final int id = database.addRestoreRequest(backupData.getId(), true, getTargetServer());
            if (id == -1) {
                MESSAGEUTILS.sendLang(staff, "errors.restore-queue-failed");
                return;
            }

            MESSAGEUTILS.sendLang(staff, "restore-queued", Map.of("%player%", backupData.getPlayerName(), "%id%", Integer.toString(id)));
            // the player might have joined since the button was clicked
            database.fetchRestoreRequests(backupData.getPlayerUUID());
        }, Priority.HIGH);
    }

    private static void runOnPlayer(Player player, RestoreRequest request, Runnable runnable) {
        Scheduler.get().run(player, task -> {
            if (!player.isOnline()) {
                requeue(player, request);
                return;
            }
            runnable.run();
        }, () -> requeue(player, request));
    }

    // the player left between claiming and applying, put the request back so the next join picks it up
    private static void requeue(Player player, RestoreRequest request) {
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            final int id = AxInventoryRestore.getDatabase().addRestoreRequest(request.backupId(), true, request.targetServer());
            log.warn("{} left before restore request #{} was applied, it was queued again as #{}.", player.getName(), request.id(), id);
        });
    }

    private static void finish(Player player, BackupData backupData, String cause) {
        MESSAGEUTILS.sendLang(player, "restored");
        AxirEvents.sendRestoreWebhook(cause, backupData);
    }

    public static void start() {
        stop();
        final int seconds = CONFIG.getInt("pending-restore-poll-seconds", 10);
        if (seconds <= 0) return;
        // polling only makes sense if other servers can write to the same database
        final String type = AxInventoryRestore.getDatabase().getType();
        if (!type.equals("MySQL") && !type.equals("PostgreSQL")) return;

        pool = new ExceptionReportingScheduledThreadPool(1);
        pool.scheduleAtFixedRate(PendingRestoreService::poll, seconds, seconds, TimeUnit.SECONDS);
    }

    public static void stop() {
        if (pool == null) return;
        pool.shutdown();
        pool = null;
    }

    private static void poll() {
        if (!polling.compareAndSet(false, true)) return;
        final List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        if (online.isEmpty()) {
            polling.set(false);
            return;
        }

        AxInventoryRestore.getThreadedQueue().submit(() -> {
            try {
                final Map<UUID, List<RestoreRequest>> requests = AxInventoryRestore.getDatabase().getGrantedRestoreRequests(online)
                        .stream()
                        .collect(Collectors.groupingBy(RestoreRequest::player));

                requests.forEach((uuid, list) -> {
                    final Player player = Bukkit.getPlayer(uuid);
                    if (player != null) process(player, list);
                });
            } finally {
                polling.set(false);
            }
        });
    }
}
