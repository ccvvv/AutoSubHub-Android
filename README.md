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

The Android client currently has a native UI, a SQLite task/artifact journal, remote-proof-gated local cleanup, a versioned backend boundary, a bounded process runner, and a basic ARM64 execution probe.

The candidate download path is being aligned with the original AutoSubHub data contract: the app reads `published-subscriptions/candidate/subscription.yaml` directly. It must not depend on the removed `candidate/pending.yaml`, `pending-manifest.json`, or `pending-meta.json` files.

The app now exposes a locally persisted probe-concurrency setting (default 50, allowed range 1–300). This setting is configuration only until the real probe engine is integrated; it is not yet evidence that the phone can execute that many concurrent probes.

Receipt upload, remote receipt verification, SSH key management, and the bundled Python/SSH/Mihomo runtime are still incomplete. The existing Termux workflow remains the operational fallback until the new app passes a full on-device end-to-end test.
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
- The GitHub read transport now verifies and downloads the cloud-prepared queue; receipt upload and full end-to-end verification remain pending.

## Security

Do not add private keys, access tokens, subscription URLs containing credentials, or personal receipts to Git. Local secrets belong in Android Keystore-backed storage. Public keys may be displayed for the user to add to GitHub.

## License

License selection is pending before public distribution of third-party-derived runtime components.
