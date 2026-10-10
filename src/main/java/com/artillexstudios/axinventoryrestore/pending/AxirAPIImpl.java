package com.artillexstudios.axinventoryrestore.pending;

import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.api.AxirAPI;
import com.artillexstudios.axinventoryrestore.api.BackupPreview;
import com.artillexstudios.axinventoryrestore.api.BackupInfo;
import com.artillexstudios.axinventoryrestore.api.RestoreResult;
import com.artillexstudios.axinventoryrestore.backups.BackupData;
import com.artillexstudios.axinventoryrestore.discord.preview.InventoryRenderer;
import com.artillexstudios.axinventoryrestore.discord.preview.ItemSummary;
import com.artillexstudios.axinventoryrestore.discord.preview.TextureManager;
import com.artillexstudios.axinventoryrestore.queue.Priority;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class AxirAPIImpl implements AxirAPI {
    private static final Logger log = LoggerFactory.getLogger(AxirAPIImpl.class);

    @Override
    public CompletableFuture<List<BackupInfo>> listBackups(@NotNull UUID player, int limit) {
        Objects.requireNonNull(player, "player");
        return supply(() -> AxInventoryRestore.getDatabase().getBackupInfos(player, limit));
    }

    @Override
    public CompletableFuture<Optional<BackupInfo>> getBackup(int backupId) {
        return supply(() -> Optional.ofNullable(AxInventoryRestore.getDatabase().getBackupInfo(backupId)));
    }

    @Override
    public CompletableFuture<Optional<ItemStack[]>> getItems(int backupId) {
        return supply(() -> Optional.ofNullable(AxInventoryRestore.getDatabase().getBackupDataById(backupId)))
                .thenCompose(data -> data.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<ItemStack[]>empty())
                        : data.get().getItems().thenApply(items -> Optional.of(copy(items))));
    }

    @Override
    public CompletableFuture<Optional<BackupPreview>> renderPreview(int backupId) {
        return supply(() -> Optional.ofNullable(AxInventoryRestore.getDatabase().getBackupDataById(backupId)))
                .thenCompose(data -> {
                    if (data.isEmpty()) return CompletableFuture.completedFuture(Optional.<BackupPreview>empty());
                    BackupData backup = data.get();
                    return backup.getItems().thenApplyAsync(items -> {
                        ItemStack[] safe = items == null ? new ItemStack[0] : items;
                        byte[] image = null;
                        if (TextureManager.isReady()) {
                            try {
                                image = InventoryRenderer.render(safe, "ENDER_CHEST".equals(backup.getReason()));
                            } catch (Throwable throwable) {
                                log.warn("Failed to render the inventory preview of backup {}!", backupId, throwable);
                            }
                        }
                        return Optional.of(new BackupPreview(image, List.copyOf(ItemSummary.lines(safe))));
                    });
                });
    }

    private static ItemStack[] copy(ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }
        final ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    @Override
    public CompletableFuture<RestoreResult> queueRestore(int backupId, @NotNull UUID target, @Nullable UUID actor, @NotNull String source) {
        Objects.requireNonNull(target, "target");
        return PendingRestoreService.queueApproved(backupId, target, actor, source == null ? "api" : source);
    }

    private static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        AxInventoryRestore.getThreadedQueue().submit(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }, Priority.HIGH);
        return future;
    }
}
