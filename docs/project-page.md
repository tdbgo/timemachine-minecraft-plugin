# TimeMachine project-page copy

English · [한국어](project-page.ko.md)

Paste-ready listing copy for Modrinth and GitHub, plus the submission checklist. Everything here is derived from the plugin's own descriptor, configuration, and source. Do not add a homepage, donation account, support address, benchmark, or compatibility claim that does not already exist and is not controlled by the project owner.

---

## Short summary

Paste into Modrinth's summary field (147 characters):

```text
Verifiable FULL and incremental region backups for Paper, with chain verification and an offline restore export tool built into the same JAR.
```

## Full description

Paste into the Modrinth description body.

```markdown
## TimeMachine

**Verifiable FULL and incremental region backups for Paper.**

TimeMachine by PLAYCITY BLOCK

TimeMachine copies the `.mca` files of your Paper worlds into snapshots you can check. A snapshot is a plain directory: the copied region files, a manifest of every entry, a SHA-256 checksum list, and the world mapping needed to put the data back. Nothing is packed into a proprietary container, so a snapshot stays readable even without the plugin.

The first unfiltered backup becomes a FULL baseline. Later backups store only the files that changed, forming a chain from that baseline. `/tmb verify` walks a chain back to its FULL base and re-checks sizes, hashes, manifests, and parent links, so "the backup exists" and "the backup restores" stay two separate questions you can answer before you need the answer.

Recovery is deliberately manual. The same JAR runs as an offline command-line tool that verifies a chain and exports it into a **new** directory, refusing an existing destination or one that overlaps your backup storage. TimeMachine never replaces a running world for you.

### What it backs up

For every loaded world, TimeMachine copies `.mca` files from `region` (chunks and block entities), `entities`, and `poi`. Worlds are tracked by UUID, so renaming a world does not break the chain.

**TimeMachine is not a whole-server backup.** Player data, `level.dat`, datapacks, plugin data, and server configuration are outside its scope. Keep a separate, tested off-site backup for those.

### Features

- FULL baselines and incremental snapshots, with automatic promotion to FULL when incremental continuity cannot be trusted
- SAFE change detection (SHA-256 of every tracked file) or optional FAST detection (size and modification time)
- Every copied file hashed and verified; copies retried when the source changes mid-copy
- `/tmb verify` checks the entire chain: paths, world mappings, sizes, hashes, checksum manifest, deletions, and recorded counts
- Chain-safe retention with pinning, a two-chain floor, and a preview-then-confirm token
- Offline `restore list`, `restore verify`, and `restore export` from the same JAR
- Local SQLite search index with filesystem reconciliation for snapshots moved to archive storage
- Live progress, `/tmb doctor` diagnostics, and daily or monthly schedules with burst-free catch-up
- Failure-marked staging detection with preview-and-confirm cleanup; active copies are excluded
- English and Korean output, following each player's client language by default

### Quick start

```text
/tmb backup      # first unfiltered backup becomes the FULL baseline
/tmb status
/tmb doctor
```

Enable nightly backups in `plugins/Timemachine/config.yml`, then run `/tmb reload`:

```yaml
schedule:
  enabled: true
  daily-times:
    - "04:00"
  timezone: "system"
```

### Offline restore

Stop the server, then run the same JAR:

```text
java -jar Timemachine-<version>.jar restore list
java -jar Timemachine-<version>.jar restore verify --snapshot <snapshotId>
java -jar Timemachine-<version>.jar restore export --snapshot <snapshotId> --output <new-directory>
```

Export verifies the whole chain, writes into a new directory only, re-checks the size and SHA-256 of every restored file, and refuses destinations that already exist, overlap backup storage, or contain symbolic links.

### Requirements

- Paper on the `26.2` API line (`api-version: '26.2'`, built against `paper-api 26.2.build.111-stable`)
- Java 25 or newer
- No client-side installation, no external dependencies, no database server

### Limitations

- Backs up only `.mca` files under `region`, `entities`, and `poi`, for loaded worlds
- No online or automatic in-place restore
- No compression, encryption, deduplication, cloud upload, or webhooks
- No Folia support and no hot reload
- Not a substitute for a separate verified off-site backup

### Permissions

`timemachine.viewer` (status, doctor, history) · `timemachine.operator` (viewer plus backup and verify) · `timemachine.admin` (everything, default op). Individual `timemachine.*` nodes remain available.

### Network behavior

TimeMachine performs no external network communication: no sockets, no remote service, no telemetry, no runtime downloads, and no remote database. SQLite is a local file accessed through the driver bundled in the JAR.

Licensed under MIT.
```

## GitHub repository metadata

- Repository description: `Verifiable FULL and incremental region backups for Paper, with chain verification and offline restore export.`
- Suggested repository slug: `timemachine-minecraft-plugin`
- Topics: `minecraft`, `paper`, `paper-plugin`, `backup`, `incremental-backup`, `sqlite`, `java`

---

## Modrinth submission checklist

Fill these fields exactly. Confirm every line before publishing.

### Project identity

| Field | Value |
|---|---|
| Project type | Plugin |
| Title | `TimeMachine` |
| Slug | `timemachine` (fall back to `playcity-block-timemachine` only if taken) |
| Publisher / team | `PLAYCITY BLOCK` |
| Byline used in copy | `TimeMachine by PLAYCITY BLOCK` |
| Summary | The short summary above |
| Categories | Utility, Management |

### Environment

| Field | Value | Evidence |
|---|---|---|
| Client side | Unsupported | No client code, resources, or protocol use |
| Server side | Required | `plugin.yml` main class; all functionality is server-side |
| Loaders | Paper | `plugin.yml` `api-version: '26.2'` |
| Game versions | The Minecraft versions served by the Paper `26.2` API line | Do not list versions the build does not target |
| Java version note (in description) | Java 25 or newer | Gradle `options.release = 25` |

### Dependencies

| Field | Value |
|---|---|
| Required dependencies | None |
| Optional dependencies | None |
| Bundled libraries | Xerial SQLite JDBC (Apache-2.0 / BSD-2-Clause) and SQLite (public domain), shaded into the JAR |
| Paper API | Compile-only; not bundled |

### License

| Field | Value |
|---|---|
| License | MIT |
| License file | `LICENSE`, copyright holder unchanged |
| Third-party notices | `THIRD_PARTY_NOTICES.md` must ship with, or be linked from, any redistribution |

### Version upload

| Field | Value |
|---|---|
| Version number | Must match `plugin.yml` and the JAR filename |
| File | `Timemachine-<version>.jar` |
| Release channel | Beta while the project is below `1.0.0` |
| Changelog | Copy the matching section of `CHANGELOG.md` verbatim; do not invent history |

### Content disclosures (Content Rules, 2026-08-13)

| Disclosure | Answer | Reason |
|---|---|---|
| Contains AI-generated content | **Yes** | The project-page description text is generated content. |
| Advertising | No | The plugin contains no advertising or promotional placement. |
| Paid features | No | The plugin has no paywall, paid feature, or donation prompt. |
| Telemetry | No | The plugin sends no usage data or analytics. |
| External system interactions | **Yes** | The plugin reads and writes configured server filesystem paths, local archive roots, a local SQLite index, and offline export destinations. It does not communicate over a network. |
| Derivative content | No | This project is not a fork or a substantial redistribution of another project. |
| Photosensitivity warning | No | The plugin has no flashing visual content or client UI. |
| Archived project | No | This is an active project, not an archived listing. |

**Images.** AI-generated and AI-derived images must not be used anywhere on the Modrinth project page — not as the icon, not in the gallery, and not inside the description body. The repository's current `assets/timemachine-icon.png` and `assets/timemachine-cover.png` are not eligible for Modrinth and must not be uploaded. Screenshots must be captured from a real server; do not compose a GUI the plugin does not have.

### Media

| Item | Value |
|---|---|
| Icon | Leave empty until a human-created, non-AI asset is available. |
| Gallery cover | Leave empty until an eligible non-AI asset or genuine server screenshot is available. |
| Suggested screenshots | Real server console output for `/tmb status` and `/tmb doctor`, a real offline `restore export` run, and the actual default configuration file. |

### Links

Set only links that exist and are controlled by the project owner. Leave the rest empty rather than pointing at a placeholder.

| Field | Value |
|---|---|
| Source | This repository, once public |
| Issues | This repository's issue tracker |
| Wiki / Discord / Donation | Leave empty until a real, owned destination exists |

### Final checks before publishing

- [ ] Title, summary, description, requirements, and limitations match `README.md`.
- [ ] The description states plainly that this is a region-data backup, not a whole-server backup.
- [ ] Recovery instructions lead with the offline export workflow and a fully stopped server.
- [ ] Retention is described as disabled by default and chain-based.
- [ ] No invented URL, support channel, account, benchmark number, or release entry appears anywhere.
- [ ] The uploaded JAR is byte-identical to the one that passed release validation.
- [ ] All content disclosures above are answered as specified.
