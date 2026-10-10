package com.artillexstudios.axinventoryrestore.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The same preview the discord addon adds to a restore request.
 *
 * @param image PNG picture of the inventory, null if the item textures are not available (yet) or rendering failed
 * @param items readable lines like "1x Netherite Sword (Sharpness V)", identical items merged, inventory order
 */
public record BackupPreview(@Nullable byte[] image, @NotNull List<String> items) {
}
