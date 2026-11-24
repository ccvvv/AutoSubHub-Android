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

## Runtime packaging gate

Before bundling executable tools, prove on Android 14–16 that the selected packaging and execution method works with this app's own package ID and target SDK. Do not assume that extracting an ELF into an app-writable directory makes it executable.

## Performance gate

The existing probe algorithm is the reference implementation. Compare old Termux and new app using the same device, candidate set and network. Record elapsed time, throughput, success count, real connection speed, CPU, memory, temperature and long-run stability. A 300-worker setting is a target, not a claim of verified capacity.
