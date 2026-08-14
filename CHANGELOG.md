# Changelog

## 0.4.0 - 2026-08-13

First public release.

- Added English and Korean messages for commands, diagnostics, retention, and the offline CLI.
- Added per-player automatic language selection and explicit `en` or `ko` modes.
- Added simple viewer, operator, and administrator permission roles.
- Reconcile now tracks moved snapshots as `ARCHIVED`, missing snapshots as `MISSING`, and validates the active chain before another incremental backup.
- Schedule catch-up coalesces missed runs instead of starting a backup storm.
- Configuration created by a newer unsupported plugin version is rejected safely.
- Requires Paper 26.2 build 111 or newer in the 26.2 line and Java 25.

## 0.3.2 - 2026-08-10

Previous production baseline.

- A world-storage layout change promotes the next unfiltered backup to a new global FULL baseline.
- Existing snapshots remain unchanged until the new FULL commits successfully.
- Filtered backups reject layout migration because they cannot create a complete global baseline.
