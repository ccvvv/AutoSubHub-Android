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

This is the initial application skeleton. Embedded Python, Git, SSH and Mihomo are intentionally not bundled yet. Android executable loading and writable-directory restrictions must be validated on modern Android before selecting the runtime packaging strategy.

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

## Security

Do not add private keys, access tokens, subscription URLs containing credentials, or personal receipts to Git. Local secrets belong in Android Keystore-backed storage. Public keys may be displayed for the user to add to GitHub.

## License

License selection is pending before public distribution of third-party-derived runtime components.
