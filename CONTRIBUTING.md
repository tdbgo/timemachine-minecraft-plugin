# Contributing to TimeMachine

Bug reports and focused pull requests are welcome. For behavior changes or new storage formats, open an issue before doing substantial implementation work so compatibility and recovery requirements can be agreed first.

## Development setup

- JDK 25
- A checkout with the included Gradle Wrapper

Run the full local check before submitting a change:

```powershell
.\gradlew.bat clean build --console=plain
```

On Linux or macOS, use `./gradlew clean build --console=plain`.

## Safety requirements

Changes must preserve these invariants:

- Bukkit world access stays on the Paper server thread.
- JDBC, hashing, archive scanning, and file copies stay off the server thread.
- A failed backup never replaces the last committed index.
- Cleanup and quarantine operations remain confined to TimeMachine-owned storage paths.
- Retention removes only complete restore chains and never crosses its two-chain safety floor.
- Live world files are never deleted or overwritten.
- Restore remains an offline operator action; do not introduce hot reload or online restore behavior.
- New configuration keys require defaults, validation, upgrade behavior, and documentation.

Add regression tests for storage formats, failure paths, and data-preservation behavior. Do not commit build output, local databases, server worlds, logs, or IDE state.

## License

Unless explicitly agreed otherwise, contributions are provided under the repository's MIT License.
