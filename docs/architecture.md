# Architecture

## Product boundary

The Android client is a standalone product. It must not import, build, or vendor the AutoSubHub backend repository.

The client connects to a configurable backend through a versioned contract. AutoSubHub remains authoritative for candidate data, receipt validation and final subscription publication.

## High-level system

```text
Android UI
  └─ Task Coordinator ── Persistent Task Store
       ├─ Backend Port ── GitHub read / SSH upload adapter
       ├─ Probe Port ──── Probe Engine
       │                    ├─ Python + packages
       │                    ├─ Mihomo ARM64 process
       │                    └─ receipt / diagnostics
       └─ Storage Manager ── retention / safe cleanup

Runtime Manager: Python, Git, OpenSSH, Mihomo, minimal shell
Security Manager: SSH keys, credentials, encrypted user-controlled backup
```

The UI sends typed commands to the Task Coordinator. The Probe Engine does not read GitHub credentials or decide which repository/branch to use. The Storage Manager deletes files only after receiving verified remote-publication proof.

## Candidate snapshot lifecycle

1. Validate the configured repository and data branch.
2. Resolve the latest commit touching `candidate/subscription.yaml`.
3. Download the YAML and `candidate/manifest.json` from that immutable commit.
4. Verify exact YAML SHA-256, manifest schema, node identities and candidate count.
5. Store both files atomically as one immutable local generation.
6. Pass the exact candidate commit and snapshot to the Probe Engine.

The app does not build a parallel candidate-history merger or cursor. The existing Termux workflow remains the operational fallback until the Android runtime and receipt uploader are proven compatible.

## Probe and receipt lifecycle

The Task Coordinator supplies an immutable job input, candidate snapshot, manifest, and probe options including user-selected concurrency. The Probe Engine returns one result per candidate plus receipt JSON, candidate/manifest sidecars, hashes and diagnostics. It does not upload receipts or store Git credentials.

An interrupted task is not a completed receipt. A complete local probe is not equivalent to a published receipt. Upload acknowledgement is not remote verification. Local artifacts become cleanup-eligible only after the remote receipt and sidecars are independently verified.

## Logical modules

The first Gradle build may remain a single Android module. Split modules only when useful.

| Module | Responsibility | Must not own |
| --- | --- | --- |
| `app` | Screens, navigation, user actions, status | Git commands, probe algorithm |
| `core:task` | Queue, pause/resume/cancel, recovery | GitHub-specific paths |
| `core:backend-api` | Backend ports and versioned contract | Probe process execution |
| `backend:github-git` | Git fetch/push, branch mapping, remote verification | UI and probe algorithm |
| `core:probe` | Input validation, probe orchestration, receipt creation | GitHub credentials and upload |
| `core:runtime` | Tool validation, process launch, logs, cleanup | Candidate business rules |
| `core:security` | SSH key lifecycle and secret protection | Receipt lifecycle |
| `core:storage` | Local files, task database, retention and safe cleanup | Publishing decisions |

## Performance gate

Concurrency is a user setting (default 50, configurable from 1 to 300). The selected value must be passed to the actual probe scheduler; storing or displaying the value alone does not prove concurrency support. Compare Termux and Android on the same device, candidate set and network. Record elapsed time, throughput, success count, CPU, memory, temperature and long-run stability. 300 is a configurable ceiling, not a verified performance claim.
