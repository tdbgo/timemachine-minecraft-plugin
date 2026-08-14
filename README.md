# TimeMachine

**Verifiable region backups for Paper.**

TimeMachine by PLAYCITY BLOCK.

[한국어](README.ko.md) · English

![TimeMachine backup timeline](assets/timemachine-cover.png)

TimeMachine stores FULL and incremental snapshots of Paper world region data. It favors verifiable recovery over opaque archives: every copied file is hashed, restore chains can be checked, and an offline export tool is included in the plugin JAR.

## Quick start

Requirements: Paper 26.2 build 111 or newer in the 26.2 line, running on Java 25.

1. Put `Timemachine-<version>.jar` in the server's `plugins` directory.
2. Start the server normally. Safe defaults are ready immediately; no setup wizard is required.
3. Run `/tmb backup`. The first unfiltered backup automatically becomes a FULL baseline.
4. Run `/tmb status` and `/tmb doctor`.
5. To enable daily automatic backups, edit the generated config and run `/tmb reload`.

The default config contains only the everyday choices:

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

All storage, SQLite, scope, copy-thread, retention, and advanced scheduling options keep safe built-in defaults. Existing advanced configuration keys remain supported.

## English and Korean

Commands, help, progress, diagnostics, retention results, and the offline CLI are available in English and Korean. `language: auto` follows each player's client language and uses the server JVM language for console commands. Set `language: en` or `language: ko` to force one language for everyone, then run `/tmb reload`.

## Everyday commands

```text
/tmb backup
/tmb status
/tmb doctor
/tmb help
```

`/timemachine` is the canonical command and `/tmb` is the recommended short alias. The legacy `/tm` alias remains available, but another plugin such as FAWE may claim it depending on the server's command registrations. Use `/tmb` when plugins are combined.

Use `/tmb help advanced` for history, verification, pruning, reconciliation, reload, world filters, FULL requests, and backup messages.

## SAFE and FAST scanning

| Mode | Unchanged-file scan | Detects content changes with preserved size and timestamp | Recommended use |
|---|---|---|---|
| `safe` | Reads and hashes every tracked file | Yes | Default and integrity-first operation |
| `fast` | Reads file size and modification time | No | Very large stores where scan time has been measured |

Copied files are SHA-256 verified in both modes. In a historical local warm-cache Java 21 benchmark, FAST reduced the unchanged-file scan stage by 4.3× to 16.8× depending on file size and count; Java 21 is not a supported runtime for this release. This is not a whole-backup speed guarantee; see the [method and results](docs/change-detection-benchmark.md).

## Restore

Stop the server before a recovery drill, then run the plugin JAR as a command-line tool:

```powershell
java -jar Timemachine-<version>.jar restore list
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <new-directory>
```

Add `--lang ko` or `--lang en` anywhere in a CLI command to select its output language.

Export verifies the complete chain, writes only to a new directory, verifies each restored file, and rejects storage overlap or symbolic-link paths. Snapshots recorded from Paper 26.1+ are exported under `world/dimensions/<namespace>/<dimension>`; older snapshots keep their legacy one-directory-per-world layout. It never replaces a live world automatically.

When Paper changes a world's physical storage layout, the next unfiltered manual or scheduled backup is automatically promoted to a new global FULL baseline. Existing chains are not rewritten. A world-filtered backup cannot perform this migration and is rejected with an actionable message.

## Simple permission roles

| Role | Access |
|---|---|
| `timemachine.viewer` | Status, doctor, history |
| `timemachine.operator` | Viewer access, backup, verification |
| `timemachine.admin` | Every TimeMachine command |

Server operators already receive all individual permissions. Existing `timemachine.*` nodes remain available for custom roles.

## Scope and limitations

TimeMachine protects `.mca` files from the `region`, `entities`, and `poi` directories. It does not back up player data, `level.dat`, datapacks, plugin data, configuration, or other server files. Keep a separate verified off-site backup for those files and for disaster recovery.

Folia, hot reload, online restore, compression, encryption, cloud upload, and webhook notifications are not supported.

## Documentation

- [Operator guide](docs/usage.md)
- [Architecture and safety boundaries](docs/architecture.md)
- [SAFE/FAST benchmark](docs/change-detection-benchmark.md)
- [Project-page copy and metadata](docs/project-page.md)
- [Sponsorship and paid support plan](docs/sustainability.md)
- [Release process](docs/releasing.md)
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)

## Build and license

Run `./gradlew clean build --console=plain` (`.\gradlew.bat` on Windows). The distributable JAR is written to `build/libs/Timemachine-<version>.jar`.

TimeMachine is available under the [MIT License](LICENSE). Existing legal attribution is preserved in that file.
