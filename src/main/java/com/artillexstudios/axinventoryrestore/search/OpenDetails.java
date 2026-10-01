package com.artillexstudios.axinventoryrestore.search;

import com.artillexstudios.axinventoryrestore.AxInventoryRestore;
import com.artillexstudios.axinventoryrestore.backups.Backup;
import com.artillexstudios.axinventoryrestore.utils.ThreadUtils;

import java.util.UUID;

import static com.artillexstudios.axinventoryrestore.AxInventoryRestore.CONFIG;

public class OpenDetails {
    private final OpenMethod openMethod;
    private final UUID restoreUser;
    private final String name;
    private final String search;
    // opened with /axir request, only viewing and discord requests are allowed
    private boolean requestOnly = false;

    public OpenDetails(OpenMethod openMethod, UUID restoreUser, String name, String search) {
        this.openMethod = openMethod;
        this.restoreUser = restoreUser;
        this.name = name;
        this.search = search;
    }

    public String getName() {
        return switch (openMethod) {
            case PLAYER -> name;
            case SEARCH -> search;
        };
    }

    public boolean isRequestOnly() {
        return requestOnly;
    }

    public OpenDetails requestOnly() {
        this.requestOnly = true;
        return this;
    }

    public void loadBackup(Backup backup) {
        ThreadUtils.checkNotMain("This method must be called async.");
        switch (openMethod) {
            case PLAYER -> AxInventoryRestore.getDatabase().loadBackupsOfPlayer(backup, restoreUser);
            case SEARCH -> AxInventoryRestore.getDatabase().loadBackupsFromSearch(backup, System.currentTimeMillis(), search, CONFIG.getInt("search.maximum-matches", 1000));
        }
    }

    public static OpenDetails player(UUID uuid, String name) {
        return new OpenDetails(
                OpenMethod.PLAYER,
                uuid,
                name,
                null
        );
    }

    public static OpenDetails search(String search) {
        return new OpenDetails(
                OpenMethod.SEARCH,
                null,
                null,
                search
        );
    }
}
