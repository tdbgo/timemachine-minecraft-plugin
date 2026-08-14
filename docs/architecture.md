# TimeMachine architecture and safety boundaries

[한국어](architecture-ko.md) · English

This document describes the current storage format, thread boundaries, commit order, and failure behavior.

## Backup scope

TimeMachine handles `.mca` files from three directories in each loaded Paper world:

- `region`: chunks and block entities
- `entities`: entity data
- `poi`: points of interest

The world UUID is the persistent identity. Names and NamespacedKeys are stored for display and restore mapping. Player data, `level.dat`, datapacks, plugin data, and server configuration are outside the backup scope.

## Thread boundaries

The Paper server thread performs only Bukkit world operations:

- resolve loaded worlds and paths
- remember and pause autosave when configured
- call `world.save(true)`
- restore autosave state

Plugin-owned workers perform filesystem scans, hashing, copies, manifests, index updates, SQLite work, reconciliation, and chain verification. Startup initializes storage and SQLite in the background. Commands do not mutate data until initialization completes.

## Backup transaction

Only one operation runs at a time.

1. Plan a `FULL`, `INCREMENTAL`, or `SCOPED_FULL` snapshot.
2. Resolve world identity, path, and autosave state on the server thread.
3. Save the selected worlds and pause autosave when configured.
4. Scan the configured scopes in a staging directory.
5. FULL copies every target. INCREMENTAL compares SHA-256 in SAFE mode or size and modification time in FAST mode.
6. Retry a copy up to three times if source size, modification time, or file identity changes.
7. Restore autosave.
8. Write snapshot metadata, mappings, entries, deletions, and checksums.
9. Move staging into the final snapshot directory.
10. Commit `state/current-index.tsv` through a temporary file and atomic replacement when supported.
11. Update the optional SQLite search index.

SQLite failure after step 10 does not invalidate the committed snapshot. If the snapshot move succeeds but the current-index commit fails, TimeMachine quarantines the new snapshot when possible and requires a new global FULL before another incremental.

A physical world-path change promotes the next unfiltered backup to a new global FULL. Existing snapshots are not rewritten. A filtered request is rejected because it cannot create a complete global baseline.

## Snapshot types

- `FULL`: global baseline with no parent
- `INCREMENTAL`: changed and deleted entries since the previous commit
- `SCOPED_FULL`: complete data for selected worlds, retained as a child of the current global chain

An incompatible index, recovered fallback index, or missing active-chain snapshot blocks incrementals. An unfiltered backup can establish a new FULL. If a moved active chain becomes available through an archive root, reconcile can resume the recorded chain.

## Storage layout

```text
plugins/Timemachine/
  config.yml
  timemachine-meta.db
  backups/
    state/current-index.tsv
    state/current-index.tsv.bak
    staging/
    retention-trash/
    snapshots/YYYY/<snapshot-id>/
      files/worlds/<world-uuid>/<scope>/r.<x>.<z>.mca
      snapshot.properties
      worlds.tsv
      entries.tsv
      deletions.tsv
      checksums.sha256
      restore-notes.txt
```

Configuration may place the backup root, archive roots, and SQLite file elsewhere.

## Verification and restore

`/timemachine verify <snapshotId>` follows the parent chain to its FULL base and checks:

- cycles, missing parents, and base references
- format version and FULL parent rules
- world UUID, source path, and mapping consistency
- duplicate or escaping entry paths
- scope and region-coordinate identity
- file size and SHA-256
- entry and checksum manifests
- recorded counts

Symbolic links are rejected. A valid chain proves internal consistency, not that unrelated server data is backed up.

The offline `restore export` command verifies the full chain, computes the target state, and writes into a new directory. It rejects existing destinations, path overlap, and symbolic links. Each restored file is checked before the temporary output is moved into place. It never writes directly into a live world.

## Retention and reconciliation

Retention is disabled by default and removes only complete chains.

- Broken inventories stop the entire prune.
- The active chain and pinned chains are protected.
- At least two chains remain.
- Preview tokens expire when the inventory changes.
- Retained leaves are verified before deletion.
- Targets move into plugin-owned retention trash first and roll back if the move fails.

SQLite is a rebuildable search index. Reconcile marks snapshots in the primary store as `LOCAL`, snapshots in configured archive roots as `ARCHIVED`, and undiscovered records as `MISSING`. Missing rows remain for audit and later rediscovery. Reconcile also validates the active parent chain before incrementals continue.

## Reload and shutdown

`/timemachine reload` initializes candidate settings and storage before replacing the active runtime. A failed reload leaves the current runtime in place.

Shutdown blocks new work, interrupts active workers, restores autosave on the server thread, and closes plugin-owned executors. Paper reload commands, hot-reload tools, and online restore are unsupported.

## Deliberate exclusions

- online or automatic restore
- compression, encryption, or object deduplication
- remote-store upload
- webhooks or external monitoring
- Folia region scheduling

These features require separate safety, credential, and consistency designs.
