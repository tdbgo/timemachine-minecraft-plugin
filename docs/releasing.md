# Release and publication process

## Public metadata

Use these values consistently on GitHub, Modrinth, release notes, and other public listings:

- Project title: `TimeMachine`
- Creator line: `TimeMachine by PLAYCITY BLOCK`
- Team/publisher: `PLAYCITY BLOCK`
- `plugin.yml` description (do not change without a matching descriptor edit): `Verifiable incremental region backups for Paper.`
- GitHub repository description: `Verifiable FULL and incremental region backups for Paper, with chain verification and offline restore export.`
- Suggested GitHub repository: `timemachine-minecraft-plugin`
- Suggested Modrinth slug: `timemachine`; use `playcity-block-timemachine` only when the shorter slug is unavailable

English listing copy is maintained in `docs/project-page.md`. The Korean version is in `docs/project-page.ko.md`.

The project icon and wide cover are maintained under `assets/`. They are brand illustrations, not simulated server screenshots.

Do not invent a homepage, donation URL, organization account, or support address. Add those only after the corresponding destination exists and is controlled by the project owner.

## Compatibility-sensitive identifiers

Public branding must not migrate runtime identity. Before every release, confirm all of the following remain unchanged:

- `plugin.yml` name: `Timemachine`
- main class and Java package: `dev.playcity.timemachine.TimeMachinePlugin` and `dev.playcity.timemachine.*`
- command and aliases: canonical `/timemachine`, recommended `/tmb`, and legacy `/tm`
- permission namespace: `timemachine.*`
- plugin data folder and defaults: `plugins/Timemachine`
- storage/archive configuration keys and persisted snapshot paths
- SQLite default path, schema/table names, and `tm_` default prefix
- JAR filename: `Timemachine-<version>.jar`

Changing any item above is a data migration project, not a branding edit.

## Candidate validation

Run every automated candidate check below, and repeat the disposable-server drill whenever backup, retention, scheduling, or restore behavior changes.

1. Confirm the requirements table and the unsupported cases in `README.md` are accurate for this candidate.
2. Confirm `CHANGELOG.md`, both READMEs, configuration examples, and CLI help match the candidate.
3. Run the production build on JDK 25 against the pinned Paper 26.2 build 111 API:

   ```powershell
   .\gradlew.bat clean build --console=plain
   ```

4. Confirm the explicit release target resolves and passes independently:

   ```powershell
   .\gradlew.bat clean check "-PjavaVersion=25" "-PreleaseVersion=25" "-PpaperApiVersion=26.2.build.111-stable" --console=plain
   ```

5. When backup, retention, scheduling, or restore behavior changes, test the candidate on a disposable Paper 26.2 build 111 server. Exercise first start, rejected reload, FULL and incremental backup, no-change backup, automatic unfiltered FULL promotion after a world-layout change, filtered layout-migration rejection, `status`, `doctor`, history, reconcile, verification, prune preview, shutdown during a copy, and an offline export/restore drill covering the `<world-root>/dimensions/<namespace>/<dimension>` layout.
6. Run the candidate JAR directly and exercise `--help`, `restore list`, `restore verify`, and `restore export` against disposable data.
7. Inspect the final JAR:

   - ZIP integrity passes.
   - Filename is `Timemachine-<version>.jar`.
   - `plugin.yml` contains name `Timemachine`, the expected main class, and the release version.
   - Manifest title is `TimeMachine`, vendor is `PLAYCITY BLOCK`, and version matches the descriptor.
   - The plugin main class, offline CLI, SQLite driver, and native libraries are present.
   - Paper/Bukkit API classes, local databases, local workspace metadata, IDE state, and build notes are absent.
   - Class major version is 69 (Java 25).

8. Record the final filename, byte size, and SHA-256 in the release notes.
9. Remove the `-SNAPSHOT` suffix only for an actual release candidate. Tag and publish the exact JAR that passed validation.
10. Keep test worlds and databases disposable. Never run the release drill against the only copy of production data.

## Publication checklist

- GitHub project and release headings use `TimeMachine`; the repository slug may be `timemachine-minecraft-plugin`. Creator attribution is `by PLAYCITY BLOCK` in copy, not prepended to the title.
- Modrinth title, description, slug, supported Paper versions, Java requirement, and limitations match the README.
- Modrinth environment is server-required/client-unsupported, loader is Paper, and no external dependency is required.
- Set `Contains AI-generated content` to yes and `External system interactions` to yes; keep advertising, paid features, telemetry, derivative content, photosensitivity warning, and archived project set to no.
- Do not upload `assets/timemachine-icon.png`, `assets/timemachine-cover.png`, or any other AI-generated or AI-derived image to Modrinth. Leave media empty until an eligible human-created asset or genuine server screenshot is available.
- Release notes clearly state that this is a region-data backup, not a complete server backup.
- Recovery instructions lead with the offline export workflow and require a fully stopped server.
- Retention is described as disabled by default and chain-based.
- No release, upload, commit, tag, remote push, or production deployment is performed until the owner explicitly authorizes it.
