# TimeMachine project-page copy

[한국어](project-page.ko.md) · English

Use this copy for GitHub, Modrinth, and release pages. Do not add a homepage, donation account, support address, or compatibility claim until it exists and is controlled by the project owner.

## Metadata

- Project title: `TimeMachine`
- Creator line: `TimeMachine by PLAYCITY BLOCK`
- Team: `PLAYCITY BLOCK`
- Suggested GitHub repository: `timemachine-minecraft-plugin`
- GitHub description: `Verifiable FULL and incremental region backups for Paper, with chain validation and offline restore export.`
- Suggested Modrinth slug: `timemachine`
- Alternative Modrinth slug: `playcity-block-timemachine`
- Platform and loader: Paper
- Supported server: Paper 26.2 build 111 or newer in the 26.2 line
- Java: 25
- License: MIT
- Environment: server required, client unsupported
- Required external dependencies: none
- Suggested release channel: beta
- Release file: `Timemachine-0.4.0.jar`
- Languages: English and Korean
- Icon: `assets/timemachine-icon.png`
- Cover: `assets/timemachine-cover.png`

## Listing copy

### One line

Verifiable FULL and incremental Paper region backups with offline restore export.

### Description

TimeMachine stores verifiable FULL and incremental snapshots of Paper `region`, `entities`, and `poi` data. SAFE mode hashes every tracked file, and every copied file is verified again.

Operators can inspect progress with `/tmb status`, diagnose storage and schedules with `/tmb doctor`, and reconcile snapshots moved to configured archive roots. Retention works on complete restore chains.

Recovery uses the same JAR in offline `restore verify` and `restore export` modes. Export writes only to a new directory and never replaces a live world automatically.

TimeMachine is not a complete server backup. Player data, `level.dat`, datapacks, plugin data, configuration, and other files need a separate verified off-site backup.

## Key features

- FULL baselines and incremental snapshots
- SAFE SHA-256 or optional FAST metadata change detection
- Source-change detection and bounded copy retries
- File, mapping, deletion, checksum, and parent-chain verification
- Live progress and operator diagnostics
- Chain-safe retention with pins and a two-chain minimum
- Offline `restore list`, `verify`, and `export`
- SQLite search index with filesystem reconciliation
- English and Korean output

## Quick example

```text
/tmb backup
/tmb status
/tmb doctor
```

```yaml
schedule:
  enabled: true
  daily-times:
    - "04:00"
  timezone: "system"
```

## Images

- Icon: `assets/timemachine-icon.png`
- Gallery cover: `assets/timemachine-cover.png`
- Suggested title: `Verified snapshot timeline`
- Suggested caption: `FULL and incremental region snapshots form a verifiable recovery chain.`

Add real operator screenshots only after capturing them on a test or production-like server. Useful subjects are `/tmb status`, `/tmb doctor`, offline export, and the compact default configuration. Do not simulate a GUI that the plugin does not provide.

## Limitations to display

- Backs up only `.mca` files under `region`, `entities`, and `poi`
- Does not support Folia or hot reload
- Does not provide online or automatic in-place restore
- Does not provide compression, encryption, cloud upload, or webhooks
- Requires a separate verified off-site backup
