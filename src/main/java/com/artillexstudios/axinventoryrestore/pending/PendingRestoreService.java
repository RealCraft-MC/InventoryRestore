package com.artillexstudios.axinventoryrestore.pending;

import com.artillexstudios.axapi.executor.ExceptionReportingScheduledThreadPool;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.ContainerUtils;
import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.api.BackupInfo;
import com.artillexstudios.axinventoryrestore.api.RestoreResult;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    // backup id -> amount of claimed requests for it that are not applied yet
    private static final Map<Integer, Integer> applying = new ConcurrentHashMap<>();
    private static ExceptionReportingScheduledThreadPool pool = null;

    // EXTERNAL: the request button only calls AxirRestoreRequestEvent, another plugin handles the request
    public static boolean isExternalRequestHandler() {
        return "EXTERNAL".equalsIgnoreCase(CONFIG.getString("restore-requests.handler", "INTERNAL").trim());
    }

    // other servers can write to the same database
    public static boolean isSharedDatabase() {
        final String type = AxInventoryRestore.getDatabase().getType();
        return type.equals("MySQL") || type.equals("PostgreSQL");
    }

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

    // must be called on the threaded queue, returns the requests this call is applying by request id
    public static Map<Integer, CompletableFuture<Boolean>> process(@NotNull Player player, @NotNull List<RestoreRequest> requests) {
        final Database database = AxInventoryRestore.getDatabase();
        final Map<Integer, CompletableFuture<Boolean>> applied = new HashMap<>();
        for (RestoreRequest request : requests) {
            if (!request.granted() || !isForThisServer(request.targetServer())) continue;
            if (!player.isOnline()) break;
            if (!database.claimRestoreRequest(request.id())) continue;

            final BackupData backupData = database.getBackupDataById(request.backupId());
            if (backupData == null) {
                log.warn("Dropped restore request #{} of {}, backup {} does not exist anymore!", request.id(), player.getName(), request.backupId());
                continue;
            }

            applied.put(request.id(), apply(player, backupData, request));
        }
        return applied;
    }

    // the request must already be claimed, completes with false if the player left and the request was queued again
    public static CompletableFuture<Boolean> apply(@NotNull Player player, @NotNull BackupData backupData, @NotNull RestoreRequest request) {
        final String cause = "request #" + request.id();
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        applying.merge(backupData.getId(), 1, Integer::sum);
        done.whenComplete((result, ex) -> applying.computeIfPresent(backupData.getId(), (id, count) -> count == 1 ? null : count - 1));

        final CompletableFuture<Void> future;
        if ("SHULKER".equalsIgnoreCase(CONFIG.getString("pending-restore-mode", "REPLACE"))) {
            future = backupData.getInShulkers("---").thenAccept(items -> runOnPlayer(player, request, done, () -> {
                ContainerUtils.INSTANCE.addOrDrop(player.getInventory(), items, player.getLocation());
                finish(player, backupData, cause);
            }));
        } else {
            future = backupData.getItems().thenAccept(items -> runOnPlayer(player, request, done, () -> {
                replace(player, isEnderChest(backupData), items, cause);
                finish(player, backupData, cause);
            }));
        }
        future.exceptionally(ex -> {
            done.completeExceptionally(ex);
            return null;
        });
        return done;
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

    /**
     * Restore through the api: stores a granted request like {@link #queue(Player, BackupData)} does and applies it
     * through {@link #process(Player, List)} if the player is online here, otherwise the join or poll of the
     * backend it is meant for picks it up.
     */
    public static CompletableFuture<RestoreResult> queueApproved(int backupId, @NotNull UUID target, @Nullable UUID actor, @NotNull String source) {
        final CompletableFuture<RestoreResult> result = new CompletableFuture<>();
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            try {
                queueApproved(backupId, target, actor, source, result);
            } catch (Exception ex) {
                log.error("Failed to restore backup {} for {} ({})!", backupId, target, source, ex);
                result.complete(new RestoreResult(RestoreResult.Status.FAILED, -1, "Unexpected error, check the console: " + ex));
            }
        }, Priority.HIGH);
        return result;
    }

    // runs on the threaded queue, which is a single thread, so two calls never check for duplicates at the same time
    private static void queueApproved(int backupId, UUID target, @Nullable UUID actor, String source, CompletableFuture<RestoreResult> result) {
        final Database database = AxInventoryRestore.getDatabase();
        final BackupInfo backup = database.getBackupInfo(backupId);
        if (backup == null) {
            result.complete(new RestoreResult(RestoreResult.Status.BACKUP_NOT_FOUND, -1, "Backup #" + backupId + " does not exist."));
            return;
        }
        if (!backup.player().equals(target)) {
            result.complete(new RestoreResult(RestoreResult.Status.FAILED, -1, "Backup #" + backupId + " belongs to " + backup.player() + ", not to " + target + "."));
            return;
        }

        // a backup is restored on the backend that made it, backups without a server-id on this one
        String targetServer = getTargetServer();
        if (backup.serverId() != null && !isForThisServer(backup.serverId())) {
            if (!isSharedDatabase()) {
                result.complete(new RestoreResult(RestoreResult.Status.WRONG_SERVER, -1, "Backup #" + backupId + " was made on '" + backup.serverId()
                        + "' and this server ('" + getServerId() + "') does not share its database with other servers."));
                return;
            }
            targetServer = backup.serverId().trim();
        }

        for (RestoreRequest pending : database.getRestoreRequests(target)) {
            if (pending.backupId() != backupId || !pending.granted()) continue;
            result.complete(new RestoreResult(RestoreResult.Status.QUEUED, pending.id(), "Backup #" + backupId + " is already queued as request #" + pending.id() + ", it was not queued again."));
            return;
        }
        if (applying.containsKey(backupId)) {
            result.complete(new RestoreResult(RestoreResult.Status.QUEUED, -1, "Backup #" + backupId + " is being restored right now, it was not queued again."));
            return;
        }

        final int id = database.addRestoreRequest(backupId, true, targetServer);
        if (id == -1) {
            result.complete(new RestoreResult(RestoreResult.Status.FAILED, -1, "Could not store the restore request, check the console."));
            return;
        }
        log.info("Restore of backup {} for {} queued as request #{} (target server: {}) by {} via {}.",
                backupId, target, id, targetServer == null ? "any" : targetServer, actor == null ? "console" : actor, source);

        final Player player = Bukkit.getPlayer(target);
        if (!isForThisServer(targetServer)) {
            result.complete(new RestoreResult(RestoreResult.Status.QUEUED, id, "Queued as request #" + id + " for server '" + targetServer + "', it is applied there when the player is online."));
            return;
        }
        if (player == null) {
            result.complete(new RestoreResult(RestoreResult.Status.QUEUED, id, "Queued as request #" + id + ", it is applied when the player joins."));
            return;
        }

        final CompletableFuture<Boolean> applied = process(player, database.getGrantedRestoreRequests(List.of(target))).get(id);
        if (applied == null) {
            result.complete(new RestoreResult(RestoreResult.Status.QUEUED, id, "Queued as request #" + id + ", it is applied when the player joins."));
            return;
        }

        // copy, so the timeout does not complete the future the service itself waits for
        applied.copy().orTimeout(30, TimeUnit.SECONDS).whenComplete((success, throwable) -> {
            final Throwable ex = throwable instanceof CompletionException ? throwable.getCause() : throwable;
            if (ex instanceof TimeoutException) {
                result.complete(new RestoreResult(RestoreResult.Status.QUEUED, id, "Request #" + id + " is being applied, but it was not confirmed within 30 seconds."));
            } else if (ex != null) {
                log.error("Failed to apply restore request #{} ({})!", id, source, ex);
                result.complete(new RestoreResult(RestoreResult.Status.FAILED, id, "Applying request #" + id + " failed, check the console: " + ex));
            } else if (success) {
                result.complete(new RestoreResult(RestoreResult.Status.APPLIED, id, "Request #" + id + " was applied to " + player.getName() + "."));
            } else {
                result.complete(new RestoreResult(RestoreResult.Status.QUEUED, id, player.getName() + " left before request #" + id + " was applied, it was queued again for the next join."));
            }
        });
    }

    private static void runOnPlayer(Player player, RestoreRequest request, CompletableFuture<Boolean> done, Runnable runnable) {
        Scheduler.get().run(player, task -> {
            if (!player.isOnline()) {
                requeue(player, request);
                done.complete(false);
                return;
            }
            try {
                runnable.run();
            } catch (RuntimeException ex) {
                done.completeExceptionally(ex);
                throw ex;
            }
            done.complete(true);
        }, () -> {
            requeue(player, request);
            done.complete(false);
        });
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
        if (!isSharedDatabase()) return;

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
