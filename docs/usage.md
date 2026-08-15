# TimeMachine operator guide

TimeMachine by PLAYCITY BLOCK

[한국어](usage-ko.md) · English

TimeMachine stores Paper `region`, `entities`, and `poi` `.mca` files as FULL and incremental snapshots. It is not a complete server backup.

## Requirements

- A Paper server on the `26.2` API line. `plugin.yml` declares `api-version: '26.2'`, and the plugin is built against `paper-api 26.2.build.111-stable`.
- Java 25 or newer (compiled with `--release 25`).
- Folia is not supported.

## Install

1. Put the release JAR in the server's `plugins` directory.
2. Start the server normally.
3. Run `/tmb backup`. The first unfiltered backup becomes a FULL baseline.
4. Check `/tmb status` and `/tmb doctor`.
5. Enable `schedule.enabled` only if automatic backups are wanted, then run `/tmb reload`.
6. Verify a snapshot with `/tmb verify <snapshotId>`.

Do not use Paper reload commands or hot-reload tools such as PlugMan.

## Language

`language: auto` follows each player's client language and the server JVM language for console output. Set `language: en` or `language: ko` to force one language, then run `/tmb reload`.

## Commands

```text
/timemachine help [advanced|config|permissions]
/timemachine backup
/timemachine backup <world>
/timemachine backup --full
/timemachine backup <world> --full
/timemachine backup --message <text>
/timemachine status
/timemachine doctor
/timemachine history [count]
/timemachine verify <snapshotId>
/timemachine prune [confirm <token>]
/timemachine reconcile
/timemachine reload
```

`/timemachine` is canonical and `/tmb` is the recommended short alias. `/tm` remains for compatibility but can conflict with other plugins. A world can be selected by name, NamespacedKey, or UUID. Place `--message` last.

## Permission roles

| Role | Access |
|---|---|
| `timemachine.viewer` | status, doctor, history |
| `timemachine.operator` | viewer access, backup, verify |
| `timemachine.admin` | every TimeMachine command |

Server operators receive all commands. Existing individual `timemachine.*` permissions remain available.

## Default configuration

New installations show only common choices:

```yaml
language: auto

backup:
  change-detection: safe

schedule:
  enabled: false
  daily-times:
    - "04:00"
  timezone: "system"
```

Advanced keys use safe built-in defaults and can be added when needed. Existing advanced configuration is preserved.

### Storage and SQLite

```yaml
storage:
  root: plugins/Timemachine/backups
  archive-roots: []

database:
  enabled: true
  table-prefix: tm_
  sqlite:
    file: plugins/Timemachine/timemachine-meta.db
```

`storage.root` receives new snapshots. Add directories containing moved snapshots to `storage.archive-roots`; TimeMachine does not move them automatically. Storage roots must not overlap each other or any world directory.

SQLite is a search index. Snapshot files are the source of truth. `/timemachine reconcile` marks discovered primary snapshots as `LOCAL`, archive snapshots as `ARCHIVED`, and undiscovered records as `MISSING`. Missing rows stay in the database for audit and rediscovery.

Reconcile validates the active parent chain. A missing active snapshot blocks the next incremental; the next unfiltered backup creates a new FULL. If the missing snapshot reappears in an archive root, reload and reconcile can resume the recorded chain. A deleted incremental is never skipped to splice a chain together.

### Backup controls

```yaml
backup:
  include-worlds: []
  scopes:
    - region
    - entities
    - poi
  pause-autosave: true
  skip-if-no-change: true
  change-detection: safe
  copy-threads: 2
  minimum-free-space-mib: 1024
```

- Empty `include-worlds` selects every loaded world.
- Keep all three scopes for consistent recovery.
- `pause-autosave` reduces changes during copy.
- `safe` hashes every tracked file. `fast` compares only size and modification time.
- `copy-threads` accepts 1 through 8.
- Free-space checks reserve `minimum-free-space-mib` beyond the estimated copy size.

### Incremental schedule

```yaml
schedule:
  enabled: false
  interval-minutes: 0
  daily-times:
    - "04:00"
  monthly-days: []
  monthly-times: []
  timezone: "system"
  catch-up-on-startup: true
```

An enabled schedule needs at least one interval, daily time, or complete monthly rule. `system` uses the server OS timezone; IANA IDs such as `Asia/Seoul` are accepted. Catch-up queues only the latest missed regular and FULL runs, preventing a restart or long pause from creating a backup storm.

### FULL schedule and retention

```yaml
full-backup:
  enabled: false
  monthly-days:
    - 1
  monthly-times:
    - "03:00"
  catch-up-on-startup: true

retention:
  enabled: false
  max-chains: 8
  max-age-days: 30
  minimum-chains: 2
  pinned-snapshots: []
```

Only an unfiltered FULL becomes a global baseline. Retention is disabled by default, removes complete chains only, protects the active and pinned chains, and keeps at least two chains. `/timemachine prune` creates a preview token; confirmation fails if the inventory changes or a retained chain cannot be verified.

## Verify and restore

`/timemachine verify <snapshotId>` checks the complete chain to its FULL base, including paths, mappings, file size, SHA-256, counts, and parent relationships.

Stop the server before recovery and use the same JAR offline:

```powershell
java -jar Timemachine-<version>.jar restore list
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <new-directory>
```

Use `--store <path>` for a non-default primary store and repeat `--archive <path>` for archive roots. `restore list` also accepts `--limit <1-1000>` (default 20). Add `--lang en` or `--lang ko` anywhere in the command. Exit codes are `0` success, `1` I/O or runtime error, `2` usage error, and `3` verification failure.

Export verifies the chain, writes into a new directory, and checks every restored file. It rejects existing destinations, paths overlapping storage, and symbolic links. It never overwrites a live world.

Recommended recovery drill:

1. Verify the target before shutdown.
2. Stop the server completely.
3. Export into a new directory.
4. Review `RESTORE_README.txt` and `restore-worlds.tsv`.
5. Install the export only in a copy or recovery environment while preserving the original world.
6. Start the recovery server and inspect chunks, entities, and POI data.

## Operating notes

- Keep a separate verified off-site backup regardless of retention settings.
- Back up player data, `level.dat`, datapacks, plugin data, and configuration separately.
- After manually moving snapshots, configure archive roots, reload, reconcile, and verify the latest leaf.
- Prefer `/timemachine prune` over deleting individual snapshots.
- See the [SAFE/FAST benchmark](change-detection-benchmark.md) before selecting FAST.
