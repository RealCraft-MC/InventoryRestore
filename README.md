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
- `messages.restored` moved to `messages.yml` (`restored`).

**messages.yml**: `restored`, `restore-queued`, `errors.restore-queue-failed`, `errors.discord-disabled`, `pending.*`, `cancelpending.*`, `categories.RESTORE_OVERWRITE`.

**Commands**
- `/axir request <player>` (permission `axinventoryrestore.discord-request`): opens the backups of a player in request-only mode. Staff can browse the backups and send a Discord restore request, but cannot restore, teleport, export or take items, and don't need `axinventoryrestore.view`. Only works on a server with the Discord addon enabled.

Permission `axinventoryrestore.restore`:
- `/axir pending <player>`: list pending restore requests (id, backup date, category, accepted, target server).
- `/axir cancelpending <id>`: remove a pending restore request.

**Database**: new nullable column `axir_restorerequests.targetServer VARCHAR(64)`, added automatically on startup.
