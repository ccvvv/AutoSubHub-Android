# AutoSubHub Android

Standalone native Android client for AutoSubHub-compatible backends.

## Project principles

- This repository owns the Android client and its runtime integration.
- The AutoSubHub backend remains independent; the app communicates through a versioned data contract.
- No backend source tree is vendored or copied into this repository.
- Secrets (SSH private keys, tokens, credentials) must never be committed.
- First supported ABI target: `arm64-v8a`.
- Preserve the existing probe behavior and validate 300-worker operation on-device before claiming performance parity.

## Current status

The client now includes a SQLite task/artifact journal, remote-proof-gated local cleanup, a versioned backend port, candidate-history cursor planning, and a bounded direct process runner. The home screen exposes storage and Android runtime diagnostics.

Embedded Python, Git, SSH and Mihomo are intentionally not bundled yet. The backend transport adapter is not connected, so no live candidate download or receipt upload occurs. Android executable loading and writable-directory restrictions must be validated on a real device before selecting the runtime packaging strategy.

## Build

The project uses Gradle, Android Gradle Plugin and JDK 17. CI builds a debug APK on pushes and pull requests.

## Planned modules

- `:app`: native Android UI and application lifecycle
- `:core:contract`: versioned backend protocol models
- `:core:security`: local SSH key lifecycle and encrypted backup boundary
- `:core:runtime`: runtime capability detection and process supervision
- `:core:tasks`: persistent task state and worker controls
- `:core:storage`: local artifact lifecycle, remote-confirmed cleanup and crash recovery

Modules will be introduced incrementally; the initial build keeps a single app module to reduce setup risk.

## Validation gates

- CI runs Kotlin unit tests before assembling the debug APK.
- Storage cleanup tests must prove that local probe completion or a Git push exit code alone cannot authorize deletion.
- The Android ELF execution gate is documented in [runtime execution validation](docs/runtime-execution.md); it is not yet passed.
- The backend port is defined, but the GitHub transport adapter and end-to-end workflow are still pending.

## Security

Do not add private keys, access tokens, subscription URLs containing credentials, or personal receipts to Git. Local secrets belong in Android Keystore-backed storage. Public keys may be displayed for the user to add to GitHub.

## License

License selection is pending before public distribution of third-party-derived runtime components.
