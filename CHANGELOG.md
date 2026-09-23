# Changelog

All notable changes to TimeMachine are recorded here. Versions match `plugin.yml` and the distributed JAR filename.

## 0.4.3 - 2026-09-23

- Configuration upgrades continue to preserve operator values and comments, back up the original file, and migrate supported legacy keys automatically.
- Malformed `config-version` values and section-type conflicts now stop migration without rewriting `config.yml`.
- Verified isolated startup, backup, chain verification, and offline export on Paper 26.2 build 111 and 26.3 build 35 alpha; 26.3 stable-build runtime compatibility remains unverified.

## 0.4.2 - 2026-08-30

- Schedule baselines now wait for the initial metadata reconciliation and include configured archive roots when SQLite is disabled, preventing stale startup catch-up decisions.
- Backups continue with filesystem history when the optional SQLite metadata index cannot be opened; `/tmb doctor` reports the degraded state and reconcile remains unavailable until reload or restart.
- Configuration validation now rejects SQLite paths that overlap the primary or archive storage roots.
- Backup and offline restore operations now reject insufficient free space before expensive copy work begins.
- Failed-staging inspection and recursive cleanup use bounded-memory file-tree traversal and respond to shutdown interruption.
- Scheduler shutdown now prevents an in-flight tick from starting a queued backup after shutdown begins.

## 0.4.1 - 2026-08-17

- Added `/tmb cleanup` preview and token-confirmed removal for failure-marked staging directories.
- `/tmb doctor` now reports failed staging directory count, file count, and disk usage.
- Cleanup ignores active staging directories and aborts if the previewed inventory changes before confirmation.

## 0.4.0 - 2026-08-13

First public release.

- Added English and Korean output for commands, help, progress, diagnostics, retention results, and the offline restore CLI.
- Added per-player automatic language selection (`language: auto`) alongside explicit `en` and `ko` modes.
- Added the grouped `timemachine.viewer`, `timemachine.operator`, and `timemachine.admin` permission roles on top of the existing individual nodes.
- Reconcile now records snapshots found in the primary store as `LOCAL`, snapshots found in archive roots as `ARCHIVED`, and snapshots recorded but found nowhere as `MISSING`, and validates the active chain before another incremental backup runs.
- Schedule catch-up now queues only the latest missed regular run and the latest missed FULL run instead of replaying every missed slot.
- A `config.yml` whose `config-version` is newer than the running plugin supports is rejected instead of being reinterpreted.
- Requires a Paper server on the `26.2` API line and Java 25 or newer.

## 0.3.2 - 2026-08-10

Previous baseline.

- A world-storage layout change promotes the next unfiltered backup to a new global FULL baseline.
- Existing snapshots remain unchanged until the new FULL commits successfully.
- World-filtered backups reject layout migration because they cannot create a complete global baseline.
