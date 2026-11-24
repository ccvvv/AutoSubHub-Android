# Backend Contract (Draft v1)

This document defines the boundary between the Android client and its backend. Candidate history and deduplication are cloud responsibilities; the Android client consumes only a prepared pending queue.

## Client configuration

- `contract_version`: integer, currently `1`
- `repository_url`: HTTPS GitHub repository URL
- `data_branch`: backend-published data branch
- `client_version`: Android app version

## Cloud-owned queue files

The cloud publishes these files on the configured data branch:

- `candidate/pending.yaml`: cumulative, deduplicated proxy list to be tested by the phone.
- `candidate/pending-manifest.json`: exact candidate YAML SHA-256 plus node IDs and names.
- `candidate/pending-meta.json`: latest candidate commit, ordered source commits, batch count, node count, generation time and matching SHA-256.

The cloud builder uses the existing AutoSubHub history/cursor/merge logic. It is triggered when a new candidate snapshot or latest phone receipt is published. If no untested batches remain, it removes the pending files rather than leaving a stale queue available.

## Contract rules

1. The client rejects unsupported contract versions.
2. The backend is authoritative for queue selection, history, deduplication, receipts and published subscriptions.
3. The Android client downloads the three queue files as one logical generation and verifies exact YAML bytes against both manifest and metadata SHA-256.
4. Manifest node IDs and names must be unique; metadata counts and candidate commit history must be internally consistent.
5. Missing, malformed, partially updated or unverifiable queue files stop the task. They must not advance a cursor or authorize cleanup.
6. Credentials are never embedded in contract documents or committed files.
7. A local upload attempt is not success; the client independently verifies the remote receipt and sidecars.

## Client-side port

The transport-neutral `BackendPort` exposes:

- `getBackendCapabilities()`
- `fetchPendingQueue()`
- `publishReceipt(bundle)`
- `verifyPublishedReceipt(receiptId, contentSha256)`
- `readPublishedStatus()`

`PendingQueueBundle` contains the latest candidate commit, ordered source commits, the three verified local files, the exact candidate SHA-256 and node count. There are no client-side methods for listing candidate history or fetching/merging individual historical snapshots.

A returned `RemotePublicationProof` is accepted only when it is valid and matches the configured backend identity (repository plus data branch), expected receipt ID and expected content hash. Contract version 1 is currently the only supported version.

## Initial integration status

The Android read-only transport now verifies the configured GitHub repository/data branch, downloads the cloud-prepared queue through the Contents API, validates the manifest node IDs/names, checks exact YAML SHA-256 against both manifest and metadata, and stores all three files locally. The UI exposes a cloud queue download action.

Receipt upload, SSH key management, full remote bundle verification and the bundled Python/SSH/Mihomo runtime are not implemented yet. The Android app must not grow its own candidate history or deduplication implementation.
