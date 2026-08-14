# SAFE/FAST change-detection benchmark

[한국어](change-detection-benchmark.ko.md) · English

This benchmark helps operators choose `backup.change-detection`. It does not predict total backup time.

## Results

The figures are medians from synthetic local data on 2026-07-22 with a warm filesystem cache.

| Dataset | Total size | FAST | SAFE | SAFE/FAST ratio |
|---|---:|---:|---:|---:|
| 512 files × 1 MiB | 512 MiB | 11.2 ms | 188.6 ms | 16.8× |
| 4,096 files × 64 KiB | 256 MiB | 87.2 ms | 378.3 ms | 4.3× |

Environment: Windows, Eclipse Temurin 21.0.9, and two SHA-256 worker threads. Each result is the median of five measured runs after one warm-up. SAFE used the plugin's `FileHashes.sha256` implementation and checked file attributes before and after hashing. FAST read size and modification time.

Java 21 was used only for this historical microbenchmark. The current plugin requires Java 25.

## Interpretation

- FAST is affected mainly by file count and filesystem metadata latency.
- SAFE is also affected by total tracked size and storage read speed.
- Both modes hash and verify files that they copy.
- World-save time and changed-file copies can reduce the difference in total backup time.
- Cold caches, HDDs, network storage, and active servers can produce different results.

The 4.3× to 16.8× range applies only to scanning unchanged files in this local benchmark.

## Choosing a mode

Keep `safe` unless the hashing phase is a measured bottleneck. SAFE detects content changes even when file size and modification time are preserved.

Consider `fast` only when all of these are true:

- `/tmb status` shows hashing as the operating bottleneck.
- The storage or synchronization layer updates modification times reliably.
- Missing same-size, same-time changes is an accepted risk.
- Regular FULL backups and offline restore checks are performed separately.

```yaml
backup:
  change-detection: fast
```

Run `/tmb reload` and `/tmb doctor` after changing the mode.
