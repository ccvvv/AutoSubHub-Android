# Architecture

## Boundary

The Android client is a separate product. It must not import, build, or vendor the AutoSubHub backend repository.

The client connects to a configurable backend through a versioned contract. Backend implementation details remain behind that contract.

## Planned responsibilities

| Area | Responsibility |
| --- | --- |
| Android UI | Configuration, status, logs and user controls |
| Runtime | Installation and validation of packaged command-line tools |
| Security | SSH key generation, local protection and user-controlled backup |
| Task engine | Foreground execution, persisted state, pause/resume/stop |
| Backend adapter | Versioned API and Git transport |
| Storage manager | Local file inventory, remote-confirmed cleanup, recovery journal and storage limits |

## Runtime packaging gate

Before bundling executable tools, prove on Android 14–16 that the selected packaging and execution method works with this app's own package ID and target SDK. Do not assume that extracting an ELF into an app-writable directory makes it executable.

## Local storage lifecycle

The storage manager may delete only app-private working files whose lifecycle is complete and whose remote publication has been independently verified. A successful local Git push command alone is not sufficient evidence. The client must verify the remote commit and the complete expected receipt bundle (receipt, candidate snapshot and manifest, including hashes and node coverage) before marking those local artifacts as cleanup-eligible.

Completed-and-uploaded test artifacts and disposable temporary runtime files can be removed. Untested candidates, test results awaiting upload, failed/incomplete receipts, SSH private keys, user settings, and recovery metadata must never be removed by automatic cleanup. The backend's published data branch is never modified by local cleanup.

Cleanup must be idempotent and crash-safe: persist a cleanup journal, re-check eligibility before deletion, delete only files under the app's private workspace, and record a compact local completion marker after deletion. If remote verification or deletion fails, retain the files and report the reason. Provide a manual cleanup action and a storage usage view.

## Performance gate

The existing probe algorithm is the reference implementation. Compare old Termux and new app using the same device, candidate set and network. Record elapsed time, throughput, success count, real connection speed, CPU, memory, temperature and long-run stability. A 300-worker setting is a target, not a claim of verified capacity.
