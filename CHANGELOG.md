# Changelog

All notable changes to TimeMachine are recorded here. Versions match `plugin.yml` and the distributed JAR filename.

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
