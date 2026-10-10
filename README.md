**Bug Reports and Feature Requests:** https://github.com/Artillex-Studios/Issues

**Support:** https://dc.artillex-studios.com/

![axinventoryrestore-banner](https://github.com/Artillex-Studios/AxInventoryRestore/assets/52270269/cb680863-5ddf-4f91-b3f8-ab91e6d66435)

## RealCraft fork changes

Fork for the RealCraft network (multiple Paper backends behind a proxy, sharing one MySQL database). Version: `<upstream>-realcraft`.

**Behaviour**
- Accepted restore requests (Discord) are executed on every backend, also without a bot token, when the player joins.
- Restores are claimed atomically in the database, so a request is never executed twice (accept + join, multiple backends, poll).
- By default a restore replaces the inventory (or the ender chest for `ENDER_CHEST` backups). The overwritten items are backed up first in the new `RESTORE_OVERWRITE` category, so every restore can be undone via `/axir view`. The quick-restore button does this too.
- Quick-restore for a player who is not online on this backend queues a request that is already accepted. It runs when the player joins.
- Discord accept/decline now checks the permission *before* changing anything. A second click gets `messages.already-handled`.

**config.yml**
| Key | Default | Description |
| --- | --- | --- |
| `server-id` | `""` | Name of this backend. Requests created here only run on a backend with the same id. Empty = no scoping. |
| `pending-restore-mode` | `REPLACE` | `REPLACE` the inventory, or `SHULKER` (old behaviour, items in shulker boxes). |
| `pending-restore-poll-seconds` | `10` | How often online players are checked for accepted requests (MySQL/PostgreSQL only, `0` = off). |
| `save-limits.restore-overwrite` | `-1` | Backup limit for the `RESTORE_OVERWRITE` category. |

**discord.yml**
- `ping-role-ids: []`: role IDs (as strings) that are pinged when a request is posted. The role must be mentionable, or the bot needs "Mention @everyone, @here and All Roles". Only these roles can be pinged.
- `messages.already-handled`: reply when a request was already accepted/declined.
- `%target-server%` placeholder in `prompt` (new field 6).
- `inventory-preview.*`: restore requests get a list of the items in the backup (identical items merged, with enchantments, custom names and the number of stacks in shulker boxes) and a picture of the inventory. If the list is too long for Discord, the full list is attached as `items.txt`. The bot server downloads the official Minecraft client from Mojang once (about 30 MB) and keeps only the item, block and entity textures and the item models (about 1 MB) in `plugins/AxInventoryRestore/textures`. Set `download-textures: false` to provide them yourself. Blocks are shown as one flat face. Items without a texture of their own are looked up through their item model like the client does; beds, chests, heads, banners and shields get a flat picture from their entity texture (banner patterns are not drawn). A textures folder from an older version without the models is downloaded again once.
- `messages.restored` moved to `messages.yml` (`restored`).

**messages.yml**: `restored`, `restore-queued`, `errors.restore-queue-failed`, `errors.discord-disabled`, `pending.*`, `cancelpending.*`, `categories.RESTORE_OVERWRITE`.

**Commands**
- `/axir request <player>` (permission `axinventoryrestore.discord-request`): opens the backups of a player in request-only mode. Staff can browse the backups and send a Discord restore request, but cannot restore, teleport, export or take items, and don't need `axinventoryrestore.view`. Only works on a server with the Discord addon enabled.

Permission `axinventoryrestore.restore`:
- `/axir pending <player>`: list pending restore requests (id, backup date, category, accepted, target server).
- `/axir cancelpending <id>`: remove a pending restore request.

**Database**: new nullable columns `axir_restorerequests.targetServer VARCHAR(64)` and `axir_backups.serverId VARCHAR(64)` (the `server-id` of the backend that made the backup), added automatically on startup.

### Restore API for other plugins

`AxirAPI` is registered in the Bukkit `ServicesManager` (`Bukkit.getServicesManager().load(AxirAPI.class)`), callable from any thread:
- `listBackups(player, limit)` / `getBackup(id)`: metadata only (`BackupInfo`: id, player, reason, cause, time, serverId).
- `getItems(id)`: copies of the items of a backup, for a read-only preview (slot order of `PlayerInventory#getContents()`, or the 27 ender chest slots for `ENDER_CHEST` backups).
- `renderPreview(id)`: the picture (PNG) and item list of a backup, the same preview the discord addon adds to a request (`BackupPreview`). The picture needs the item textures, which are also downloaded with `restore-requests.handler: EXTERNAL` when the discord addon is off; until they are there the image is null.
- `queueRestore(backupId, target, actor, source)`: restores without a new approval, through the same pending-request route as a queued quick-restore (atomic claim, `RESTORE_OVERWRITE` backup in REPLACE mode). The backup is restored on the backend whose `server-id` made it; backups without a server-id on the calling backend. Results: `APPLIED` (done on this backend), `QUEUED` (stored, applied by join/poll on the right backend, also for a backup that is already queued or being restored, which is not queued again), `BACKUP_NOT_FOUND`, `WRONG_SERVER` (backup from another backend while the database is not shared), `FAILED`. `source` goes to the console log together with the request id; the `RESTORE_OVERWRITE` backup has cause `request #<id>`.

`config.yml` → `restore-requests.handler`:
- `INTERNAL` (default): the request button posts a Discord request, as before.
- `EXTERNAL`: the request button (also with `/axir request`, which then works without the Discord addon) only calls `AxirRestoreRequestEvent` (requester, target, backup id). No Discord message, no row in `axir_restorerequests`. Without a listener the requester gets `discord-request.no-handler`. The Discord addon posts no request embeds; accept/decline of older requests keeps working.
