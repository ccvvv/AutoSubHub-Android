# Backend Contract (Draft v1)

This document defines the boundary between the Android client and the backend. It deliberately avoids coupling the app to backend source paths.

## Client configuration

- `contract_version`: integer, currently `1`
- `repository_url`: HTTPS GitHub repository URL
- `data_branch`: backend-published data branch
- `client_version`: Android app version

## Contract rules

1. The client must reject unsupported major contract versions.
2. The backend remains the authority for candidate queues, receipts and published subscriptions.
3. The client must not infer success from a local upload attempt; it must verify the remote result.
4. Credentials are never embedded in contract documents or committed files.
5. Any schema change must be documented and tested before the client depends on it.

## Client-side port (implemented; transport adapter in progress)

The Android source defines a transport-neutral `BackendPort` with these operations:

- `getBackendCapabilities()`
- `fetchNextCandidateBatch(cursor)`
- `fetchCandidateSnapshot(batchId)`
- `publishReceipt(bundle)`
- `verifyPublishedReceipt(receiptId, contentSha256)`
- `readPublishedStatus()` returns the latest remote revision, receipt ID, candidate commit cursor and verified content hash.

The port uses typed batch and receipt bundle models. A receipt bundle must carry candidate and manifest sidecars and full candidate coverage. An upload acknowledgement is deliberately distinct from a verified publication proof.

A returned `RemotePublicationProof` is accepted only when it is valid and matches the configured backend identity (repository plus data branch), expected receipt ID and expected content hash. Contract version 1 is currently the only supported version.

## Initial integration status

The Kotlin interface, model validation and deterministic candidate cursor planner are implemented and covered by unit tests. A public-read `GitHubReadOnlyTransport` now downloads immutable candidate YAML and manifest files by commit SHA, verifies the candidate SHA-256 against the manifest, validates manifest node IDs/names, and reads the latest receipt through the GitHub Contents API. The connection check also verifies the configured repository branch before interpreting a missing receipt as an empty initial state. It deliberately has no upload or credential functionality. Candidate history enumeration and deterministic multi-snapshot merge still need the bundled Git transport; receipt upload, SSH key management, and remote bundle verification are not implemented yet. The adapter must preserve the path/history/receipt rules in [GitHub backend mapping](github-backend-mapping.md).
