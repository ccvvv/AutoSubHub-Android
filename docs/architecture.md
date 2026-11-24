# Architecture

## Product boundary

The Android client is a standalone product. It must not import, build, or vendor the AutoSubHub backend repository.

The client connects to a configurable backend through a versioned contract. Backend implementation details remain behind that contract. The backend remains the authority for candidate queues, receipts and published subscriptions.

## High-level system

```text
Android UI
  └─ Task Coordinator ── Persistent Task Store
       ├─ Backend Port ── GitHub Git Adapter (first backend)
       ├─ Probe Port ──── Probe Engine
       │                    ├─ Python + packages
       │                    ├─ Mihomo ARM64 process
       │                    └─ receipt / diagnostics
       └─ Storage Manager ── retention / safe cleanup

Runtime Manager: Python, Git, OpenSSH, Mihomo, minimal shell
Security Manager: SSH keys, credentials, encrypted user-controlled backup
```

The UI never launches shell commands directly. It sends typed commands to the Task Coordinator. The Probe Engine never reads GitHub credentials or decides which repository/branch to use. The Storage Manager never decides whether a receipt was uploaded; it only deletes files after receiving verified cleanup eligibility from the Task Coordinator.

## Logical modules

The first Gradle build may remain a single Android module. These are logical boundaries; split Gradle modules only when useful.

| Module | Responsibility | Must not own |
| --- | --- | --- |
| `app` | Screens, navigation, user actions, status | Git commands, probe algorithm |
| `core:domain` | Task models, state machine, result types | Android widgets, backend paths |
| `core:task` | Queue, pause/resume/cancel, recovery | GitHub-specific paths |
| `core:backend-api` | Backend ports and versioned contract | Probe process execution |
| `backend:github-git` | Git fetch/push, branch mapping, remote verification | UI and probe algorithm |
| `core:probe` | Input validation, probe orchestration, receipt creation | GitHub credentials and upload |
| `core:runtime` | Tool validation, process launch, logs, cleanup | Candidate business rules |
| `core:security` | SSH key lifecycle and secret protection | Receipt lifecycle |
| `core:storage` | Local files, task database, retention and safe cleanup | Publishing decisions |

## Stable component boundaries

### BackendPort

The Task Coordinator calls transport-neutral operations:

- `getBackendCapabilities()`
- `fetchNextCandidateBatch(cursor)`
- `fetchCandidateSnapshot(batchId)`
- `publishReceipt(receiptBundle)`
- `verifyPublishedReceipt(receiptId, contentHash)`
- `readPublishedStatus()`

The first adapter uses GitHub Git and maps these operations to configured repository and data branch. All branch names, Git history traversal, repository paths, SSH/HTTPS transport and remote commit verification stay inside the adapter. A future HTTP backend can implement the same port without changing the UI or Probe Engine.

### ProbePort

The Task Coordinator supplies an immutable job input: contract version, batch ID, candidate YAML, matching manifest/metadata, expected hashes and probe options.

The Probe Engine returns receipt JSON, candidate/manifest sidecars, hashes, coverage summary and structured diagnostics. It does not download candidates, upload receipts, advance backend cursors or store Git credentials.

### StoragePort

The Task Coordinator records each artifact's type, task ID, path, size, hash, creation time and lifecycle state. Storage Manager performs cleanup only for artifacts explicitly marked eligible. It must never infer upload success from a filename, timestamp, UI label or local Git push exit code alone.

## Task lifecycle

1. Connect and validate backend identity, contract version and permissions.
2. Fetch candidate batch and required historical snapshot data.
3. Verify manifest, metadata, hashes and candidate coverage before testing.
4. Run the existing probe behavior through the local runtime.
5. Atomically save the complete receipt and its candidate/manifest sidecars.
6. Upload the complete bundle through the backend adapter.
7. Independently verify the remote receipt and its content hash/commit.
8. Only after remote verification succeeds, mark the task `PUBLISHED_CONFIRMED`, advance the local cursor and make its uploaded artifacts eligible for cleanup.
9. Storage Manager removes eligible large files according to the retention policy, while retaining a small local audit record.

If any step fails, preserve the candidate inputs, receipt, sidecars and diagnostics needed to retry or investigate. A completed local probe is not equivalent to a published receipt.

## Storage and cleanup

The Storage Manager is a first-class subsystem. See [storage lifecycle](storage-lifecycle.md). Its safety invariant is: **never delete the only copy of a result that has not been verified on the backend**.

## Runtime packaging gate

Before bundling executable tools, prove on Android 14–16 that the selected packaging and execution method works with this app's own package ID and target SDK. Do not assume extracting an ELF into an app-writable directory makes it executable.

## Performance gate

The existing probe algorithm is the reference implementation. Compare old Termux and new app on the same device, candidate set and network. Record elapsed time, throughput, success count, real connection speed, CPU, memory, temperature and long-run stability. A 300-worker setting is a target, not a claim of verified capacity.
