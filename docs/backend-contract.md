# Backend Contract (Draft v1)

This document defines the boundary between the Android client and the existing AutoSubHub backend.

## Client configuration

- `contract_version`: integer, currently `1`
- `repository_url`: HTTPS GitHub repository URL
- `data_branch`: backend data branch (default: `published-subscriptions`)

## Candidate snapshot input

The phone consumes the original published candidate snapshot; it does not depend on a separate cloud-generated pending queue.

- `candidate/subscription.yaml`: candidate proxy list.
- `candidate/manifest.json`: schema version, exact candidate YAML SHA-256, unique candidate count, and per-node names/IDs.

Each manifest `node_id` is the full 64-character lowercase SHA-256 fingerprint of that proxy's canonical configuration: serialize the proxy object after excluding its mutable `name`, with recursively sorted object keys, compact JSON separators, UTF-8 encoding, then SHA-256. If present, `fingerprint` must equal `node_id`. The Android client recomputes this fingerprint when downloading (including cached snapshots), before probing, and again before receipt upload. Legacy 20-character IDs are rejected; names are display labels only and are never node identity.

The client resolves the latest commit touching `candidate/subscription.yaml` first, then downloads both files by that immutable commit SHA. It verifies the manifest schema, SHA-256, node identity uniqueness, and that YAML proxy-entry count equals the manifest node count. The snapshot is stored as one immutable local generation.

The client does not enumerate historical candidate commits, merge snapshots, or advance the backend candidate cursor. Those responsibilities remain in the existing AutoSubHub/Termux flow until a separately versioned mobile receipt contract is implemented.

## Receipt upload and confirmation

A complete phone receipt must include the exact candidate commit, candidate YAML and manifest sidecars, and one result per manifest node ID. Partial or interrupted tasks are not uploadable.

A successful upload acknowledgement is not proof of publication. The client must independently verify the receipt and sidecars at the remote revision before marking local artifacts cleanup-eligible. No local data may be deleted based only on a local probe result or a successful push exit code.

Credentials and private keys must never be embedded in source or committed files.

## Current implementation status

The Android client currently supports public-read connection checks and immutable candidate snapshot download/verification. The persisted concurrency setting (default 50, allowed 1–300) is UI configuration only until the real phone probe engine consumes it.

SSH key management, receipt upload, remote receipt verification, Python/SSH/Mihomo runtime packaging, and full on-device end-to-end operation remain incomplete. The existing Termux workflow remains the operational fallback.
