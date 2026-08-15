# TimeMachine

**Verifiable FULL and incremental region backups for Paper.**

TimeMachine by PLAYCITY BLOCK

English · [한국어](README.ko.md)

![TimeMachine backup timeline](assets/timemachine-cover.png)

TimeMachine copies the `.mca` files of your Paper worlds into snapshots you can check. A snapshot is a plain directory: the copied region files, a manifest of every entry, a SHA-256 checksum list, and the world mapping needed to put the data back. Nothing is packed into a proprietary container, so a snapshot stays readable even without the plugin.

The first unfiltered backup becomes a FULL baseline. Later backups store only the files that changed, forming a chain from that baseline. `/tmb verify` walks a chain back to its FULL base and re-checks sizes, hashes, manifests, and parent links, so "the backup exists" and "the backup restores" stay two separate questions you can answer before you need the answer.

Recovery is deliberately manual. The same JAR runs as an offline command-line tool that verifies a chain and exports it into a **new** directory, refusing an existing destination or one that overlaps your backup storage. TimeMachine never replaces a running world for you.

TimeMachine is not a whole-server backup. It protects region, entity, and POI data — not player data, `level.dat`, datapacks, plugin data, or server configuration. Keep a separate, tested off-site backup for those.

---

## 1. Requirements

| Item | Requirement |
|---|---|
| Server | Paper on the `26.2` API line. `plugin.yml` declares `api-version: '26.2'`; the plugin is built against `paper-api 26.2.build.111-stable`. |
| Java | 25 or newer (compiled with `--release 25`). |
| Client | None. Players install nothing. |
| External dependencies | None. The SQLite JDBC driver is bundled in the JAR. |
| Disk | Room for the snapshot store, plus the reserve set by `backup.minimum-free-space-mib` (default 1024 MiB). |

Folia is not supported. Hot-reload tools such as PlugMan and Paper's reload commands are not supported; restart the server instead.

## 2. Installation

1. Copy `Timemachine-<version>.jar` into the server's `plugins` directory.
2. Start the server normally. TimeMachine creates `plugins/Timemachine/config.yml` with safe defaults and initializes its storage in the background.
3. Wait until the console reports that TimeMachine is enabled. Until then, commands answer `TimeMachine storage is still initializing`.

No setup wizard, database server, or account is required.

## 3. Five-minute quick start

```text
/tmb backup                 # first unfiltered backup becomes the FULL baseline
/tmb status                 # progress, last result, next scheduled run
/tmb doctor                 # storage, space, chain, schedule, scope warnings
/tmb history                # recent snapshots (default 5)
/tmb verify <snapshotId>    # re-check a snapshot and its whole chain
```

A snapshot ID looks like `2026/2026-08-16_04-00-00-123_manual_1a2b3c4d` — the year directory, the local timestamp, the trigger, and a random suffix. Copy it from `/tmb history`.

To turn on nightly backups, edit `plugins/Timemachine/config.yml`:

```yaml
schedule:
  enabled: true
  daily-times:
    - "04:00"
  timezone: "system"
```

Then run `/tmb reload` and confirm the next run with `/tmb status`.

Finish the drill on a test machine: stop a copy of the server, run the offline exporter, and open the exported world. A backup you have never restored is an assumption.

## 4. Essential usage

### What a backup covers

For every **loaded** world that passes the world filter, TimeMachine scans three directories and copies `.mca` files only:

- `region` — chunks and block entities
- `entities` — entity data
- `poi` — points of interest

Worlds are tracked by UUID, so renaming a world does not break the chain. The world name and NamespacedKey are recorded for display and for restore mapping. A world that is not loaded is not backed up.

### FULL, incremental, and scoped-full snapshots

| Kind | When | Contents |
|---|---|---|
| `FULL` | First backup, `/tmb backup --full` without a world filter, or an automatic promotion | Every tracked file. No parent. Becomes the base of a new chain. |
| `INCREMENTAL` | Normal backup | Files changed since the previous snapshot, plus a list of deleted paths. Points at a parent and at the chain's FULL base. |
| `SCOPED_FULL` | `/tmb backup <world> --full` | Complete data for the selected world, kept as a child of the current chain. Does **not** start a new chain. |

Only an unfiltered FULL becomes a global baseline. TimeMachine promotes the next unfiltered backup to FULL by itself when it cannot trust incremental continuity — a missing or unreadable index, an index recovered from its `.bak` copy, a broken active chain, or a world whose physical storage path changed. A world-filtered request in that state is rejected with an explanatory message instead of silently producing an incomplete baseline.

If an incremental finds no changed and no deleted files, and `backup.skip-if-no-change` is on (the default), no snapshot is written.

### SAFE and FAST change detection

| Mode | Unchanged-file scan | Detects a content change that preserves size and timestamp |
|---|---|---|
| `safe` (default) | Reads and SHA-256 hashes every tracked file, then compares | Yes |
| `fast` | Compares size and modification time only | No |

That row is the entire safety boundary. Files that are actually copied are SHA-256 hashed and verified in both modes; the difference is whether an unchanged-looking file is trusted without reading it. `/tmb doctor` prints a standing warning while `fast` is active. See the [SAFE/FAST benchmark](docs/change-detection-benchmark.md) for the measured scan-stage difference and the conditions it was measured under.

Every copy goes through a stability check: source attributes are read before and after, the copy is retried up to three times if the source changed mid-copy, and the file is hashed before it is moved into the snapshot.

### Verification

`/tmb verify <snapshotId>` follows the parent chain to its FULL base and checks format versions, cycles and missing parents, world mappings, entry paths and identities, file sizes, SHA-256 values, the checksum manifest, deletion entries, and the recorded counts. Symbolic links inside snapshot content are rejected. Verification searches the primary store and every configured archive root.

A valid chain proves the snapshot data is internally consistent. It does not prove that anything outside TimeMachine's scope was backed up.

### Reconciliation of moved or removed snapshots

Snapshot directories on disk are the source of truth; the SQLite file is a rebuildable index. If you move old snapshots to another disk, add that directory to `storage.archive-roots`, run `/tmb reload`, then `/tmb reconcile`.

Reconcile scans the primary store and the archive roots and updates the index:

- found under `storage.root` → `LOCAL`
- found under an archive root → `ARCHIVED`
- recorded but found nowhere → `MISSING` (the row is kept for audit and later rediscovery)

Reconcile also re-inspects the active chain. If a snapshot the chain depends on has disappeared, incrementals are blocked and the next unfiltered backup will create a new FULL baseline. If the missing snapshot reappears — for example through a newly configured archive root — reconcile can restore the recorded chain and incrementals continue. A gap is never spliced over by skipping a snapshot.

`/tmb reconcile` needs `database.enabled: true`. It also runs automatically in the background after a successful reload.

### Retention

Retention is disabled by default. When enabled it works on whole restore chains, never on individual snapshots:

- The active chain and any chain containing a pinned snapshot are protected.
- At least `retention.minimum-chains` chains always survive (minimum 2; the value cannot be set lower).
- Any inventory problem — a cycle, a missing parent, an incomplete FULL chain, an unreadable snapshot, a symbolic link — aborts the whole prune.
- Retained chain leaves are verified before anything is deleted.
- Deletion targets move into a plugin-owned `retention-trash` directory first, with a manifest, and roll back if a move fails.

`/tmb prune` prints a preview and a confirmation token. `/tmb prune confirm <token>` applies it, and fails if the inventory changed since the preview. With `retention.enabled: true`, the same policy also runs automatically after each successful backup; if its safety checks fail, the backup is still reported as successful and nothing is deleted.

### Offline restore

Restore is never automatic and never online. Stop the server, then run the same JAR from the server root:

```text
java -jar Timemachine-<version>.jar restore list [--limit <1-1000>]
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <new-directory>
java -jar Timemachine-<version>.jar version
```

Options: `--store <dir>` for a non-default primary store (default `plugins/Timemachine/backups`, relative to the current directory), `--archive <dir>` repeated once per archive root, and `--lang en` or `--lang ko` anywhere in the command. Exit codes are `0` success, `1` I/O or runtime error, `2` usage error, `3` verification failure.

`restore export`:

1. verifies the complete chain before touching anything;
2. replays the chain from the FULL base, applying each snapshot's entries and deletions in order;
3. writes into a temporary staging directory, verifying the size and SHA-256 of every restored file;
4. re-verifies the source chain and aborts without publishing if it changed during the export;
5. moves the finished output into the requested directory.

It refuses a destination that already exists, that overlaps the primary store or any archive root, or that contains a symbolic-link component. Worlds recorded with a Paper dimension source path are exported as `<world-root>/dimensions/<namespace>/<dimension>`; other worlds get one top-level directory named after the world, or after its UUID when the name is not portable or not unique. The export includes `restore-worlds.tsv` (world UUID → output directory) and `RESTORE_README.txt`.

Installing the export into a server is your step, on purpose. Keep the original world directories until the recovered server has been checked.

## 5. Commands and permissions

`/timemachine` is the canonical command. `/tmb` is the recommended short form and is what all in-game help text prints. `/tm` is still registered for compatibility, but another plugin may claim it depending on registration order — prefer `/tmb`.

| Command | Permission | Notes |
|---|---|---|
| `/tmb help [advanced\|config\|permissions]` | none | Shows only what the sender may run |
| `/tmb backup [world] [--full] [--message <text>]` | `timemachine.backup` | World by name, NamespacedKey, or UUID. Put `--message` last; it consumes the rest of the line |
| `/tmb status` | `timemachine.status` | Runtime state, live progress, last result, next scheduled run |
| `/tmb doctor` | `timemachine.doctor` | Storage, free space, change detection, chain health, database, retention, worlds, warnings |
| `/tmb history [count]` | `timemachine.history` | `count` is clamped to 1–20, default 5 |
| `/tmb verify <snapshotId>` | `timemachine.verify` | Verifies the snapshot and its whole chain |
| `/tmb prune [confirm <token>]` | `timemachine.prune` | Preview without arguments; requires `retention.enabled` |
| `/tmb reconcile` | `timemachine.reconcile` | Requires `database.enabled` |
| `/tmb reload` | `timemachine.reload` | Refused while an operation is running; a rejected reload keeps the current runtime |

Three grouped roles are provided:

| Role | Grants |
|---|---|
| `timemachine.viewer` | `status`, `doctor`, `history` |
| `timemachine.operator` | viewer plus `backup`, `verify` |
| `timemachine.admin` | every TimeMachine permission (default: op) |

`viewer` and `operator` default to no one; assign them in your permission plugin. The individual `timemachine.*` nodes default to op and remain available for custom roles.

Only one operation — backup, verify, prune, reconcile, or reload — runs at a time. World access happens on the server thread; scanning, hashing, copying, SQLite work, and verification run on TimeMachine's own threads.

### Language

Command output, help, progress, diagnostics, retention results, and the offline CLI are available in English and Korean. `language: auto` follows each player's client language and the server JVM language for console output. Set `language: en` or `language: ko` to force one language for everyone, then run `/tmb reload`.

## 6. Configuration, storage, and network behavior

### The default file

A new install writes only the settings most servers touch:

```yaml
config-version: 7

language: auto

backup:
  change-detection: safe

schedule:
  enabled: false
  daily-times:
    - "04:00"
  timezone: "system"
```

Every other setting uses a built-in default and can be added to the file when you need it. The full key reference lives in the [operator guide](docs/usage.md); the defaults are:

| Key | Default |
|---|---|
| `storage.root` | `plugins/Timemachine/backups` |
| `storage.archive-roots` | `[]` |
| `database.enabled` | `true` |
| `database.table-prefix` | `tm_` |
| `database.sqlite.file` | `plugins/Timemachine/timemachine-meta.db` |
| `backup.include-worlds` | `[]` (all loaded worlds) |
| `backup.scopes` | `region`, `entities`, `poi` |
| `backup.pause-autosave` | `true` |
| `backup.skip-if-no-change` | `true` |
| `backup.copy-threads` | `2` (allowed 1–8) |
| `backup.minimum-free-space-mib` | `1024` |
| `schedule.interval-minutes` | `0` (off) |
| `schedule.monthly-days` / `schedule.monthly-times` | `[]` (must be set together) |
| `schedule.catch-up-on-startup` | `true` |
| `full-backup.enabled` | `false` |
| `retention.enabled` | `false` |
| `retention.max-chains` / `max-age-days` / `minimum-chains` | `8` / `30` / `2` |

Relative paths resolve against the server root. Invalid values are rejected at load or reload time with the offending key named; a rejected reload leaves the running configuration untouched.

Schedules run in `schedule.timezone` (`system`, or an IANA ID such as `Asia/Seoul`). Catch-up queues only the single latest missed regular run and the single latest missed FULL run, so a long downtime does not produce a burst of backups.

### On-disk layout

```text
plugins/Timemachine/
  config.yml
  timemachine-meta.db
  backups/
    state/current-index.tsv          # tracked-file index; .bak kept alongside
    staging/                         # in-progress and failed snapshots
    retention-trash/                 # prune holding area
    snapshots/<year>/<snapshot-dir>/
      files/worlds/<world-uuid>/<scope>/r.<x>.<z>.mca
      snapshot.properties            # id, parent, base, kind, counts, scopes
      worlds.tsv                     # world UUID, key, name, storage path, source path
      entries.tsv                    # changed entries with size, mtime, SHA-256
      deletions.tsv                  # paths removed in this snapshot
      checksums.sha256
      restore-notes.txt
```

Snapshot metadata is written and fsynced before the staging directory is moved into place, and the tracked-file index is committed through a temporary file and an atomic replace. If the index commit fails after a snapshot was published, the snapshot is quarantined into `staging/` and a new FULL baseline is required.

The primary store, archive roots, and the SQLite file must not overlap each other or any world directory. TimeMachine checks this at startup, at reload, and again before every backup, and refuses to run if it finds an overlap.

### Database

The SQLite file is a local search and metadata index — history, snapshot status, and schedule baselines. Snapshot directories remain the source of truth, and the index can be rebuilt with `/tmb reconcile`. Set `database.enabled: false` to run without it; `/tmb history` then reads the snapshot files directly, and `/tmb reconcile` becomes unavailable.

The schema version is checked on open. A database written by a newer TimeMachine is rejected rather than migrated downward.

### Network behavior

TimeMachine performs no external network communication. It opens no sockets, contacts no remote service, sends no telemetry or analytics, and downloads nothing at runtime. There is no remote database: SQLite is a local file accessed through the driver bundled in the JAR. Nothing is uploaded anywhere — off-site copies are your own process.

## 7. Upgrading

1. Stop the server. Do not hot-reload the JAR.
2. Replace `Timemachine-<old>.jar` with the new JAR.
3. Start the server, check the console, then run `/tmb doctor`.

On start, TimeMachine merges any missing keys from the bundled defaults into your `config.yml`. When something changes it first copies the current file to `config.backup-<timestamp>.yml` in the same directory, writes the merged file atomically, raises `config-version`, and logs what it did. Your existing values and comments are preserved.

Three legacy keys are migrated automatically when the modern key is absent: `schedule.clock-times` → `schedule.daily-times`, `schedule.run-on-startup-if-missed` → `schedule.catch-up-on-startup`, `backup.max-copy-threads` → `backup.copy-threads`.

Downgrading is not supported. A `config.yml` whose `config-version` is newer than the running plugin supports is rejected, and the plugin disables itself rather than reinterpret settings it does not understand. Keep the `config.backup-*.yml` file if you may need to roll back.

An index written in the older v1 format, or one recovered from its `.bak` copy, is accepted but marked as needing a new baseline: the next unfiltered backup becomes a FULL. Existing snapshots are never rewritten by an upgrade.

## 8. Limitations and recovery boundaries

**In scope:** `.mca` files under `region`, `entities`, and `poi`, for loaded worlds that pass the world filter.

**Not backed up:** player data, `level.dat`, `session.lock`, world icons, datapacks, plugin data and configuration, server configuration, logs, JARs, or anything else on the server. Unloaded worlds are not backed up.

**Not provided:** online or in-place restore, automatic world replacement, compression, encryption, deduplication, cloud or remote upload, webhooks, external monitoring, a GUI, Folia support, and hot reload.

**Recovery boundaries.** A verified chain tells you the snapshot data is internally consistent and complete relative to its FULL base. It does not tell you the world was consistent at the moment of copy, that the rest of the server is recoverable, or that the storage medium holding the snapshots is healthy. Restoring region data alone can leave a world inconsistent with the player data and `level.dat` you restore alongside it — plan those together. Keep at least one verified copy on separate hardware, off-site; TimeMachine writes to the paths you configure and does nothing to protect against losing the machine.

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `/tm` runs another plugin | Alias collision. Use `/tmb` or `/timemachine`. |
| `TimeMachine storage is still initializing` | Storage and SQLite start in the background. Retry shortly. |
| `TimeMachine is not initialized` | Startup failed. Check the console for the rejected setting, fix `config.yml`, run `/tmb reload`. |
| Plugin disabled itself at startup | A storage path overlaps a world or another root, `config.yml` is invalid, or its `config-version` is newer than the plugin. The console names the reason. |
| `doctor` reports `FULL required` | No trusted baseline. Run `/tmb backup` with no world argument. |
| `doctor` reports chain `broken` | An active-chain snapshot is unreadable or gone. Restore or attach it and run `/tmb reconcile`, or run an unfiltered `/tmb backup` to start a new chain. |
| Filtered backup rejected | A global baseline is required first, or a world's storage layout changed. Run an unfiltered `/tmb backup`. |
| `Insufficient free space` | Usable space is below `backup.minimum-free-space-mib` plus the estimated copy size. Free space, prune, or lower the reserve. |
| `Previously tracked world is not loaded` | A tracked world is missing from the server. Load it, or set `backup.include-worlds` explicitly to confirm the new world set. |
| `SQLite metadata index is disabled` | `database.enabled` is `false`. `/tmb reconcile` needs it. |
| Snapshots moved and now `MISSING` | Add their directory to `storage.archive-roots`, `/tmb reload`, then `/tmb reconcile`. |
| Prune token does not match | The inventory changed after the preview. Run `/tmb prune` again. |
| `Retention warning` after a backup | Automatic retention aborted on a safety check. The backup succeeded and nothing was deleted; run `/tmb prune` to see the reason. |
| Reload rejected | Candidate settings failed validation. The previous runtime is still active; the console has the detail. |
| Export refuses the output path | The destination exists, overlaps backup storage, or contains a symbolic link. Choose a fresh directory elsewhere. |

## 10. Support and license

- [Operator guide](docs/usage.md) — full configuration reference and recovery drill
- [Architecture and safety boundaries](docs/architecture.md) — storage format, commit order, failure behavior
- [SAFE/FAST benchmark](docs/change-detection-benchmark.md)
- [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md) · [Security policy](SECURITY.md)

Report bugs and request features through this repository's issue tracker. For anything with security impact, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

### Building from source

```text
./gradlew clean build --console=plain
```

Use `.\gradlew.bat` on Windows. The distributable JAR is written to `build/libs/Timemachine-<version>.jar`.

### License

TimeMachine is released under the [MIT License](LICENSE). Bundled third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
