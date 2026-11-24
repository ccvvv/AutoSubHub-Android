# AutoSubHub GitHub Backend Mapping

This mapping is based on the current `ccvvv/AutoSubHub` main-branch implementation. It is the implementation reference for the first `BackendPort` adapter; it does not change the existing backend.

## Configured data target

- Repository default: `ccvvv/AutoSubHub`
- Data branch: `published-subscriptions`
- Candidate snapshot path: `candidate/subscription.yaml`
- Candidate manifest path: `candidate/manifest.json`
- Latest phone receipt: `receipts/latest.json`
- Uploaded candidate sidecar: `receipts/candidate.yaml`
- Uploaded manifest sidecar: `receipts/manifest.json`

All repository names, branch names and paths belong inside the GitHub adapter. The app UI and probe engine must not hard-code them.

## Cloud-prepared pending queue

The cloud owns candidate history, cursor reconciliation, immutable snapshot downloads, canonical fingerprint deduplication, merge order, and provenance. The Android client must not enumerate candidate history or merge candidate snapshots.

The cloud publishes three files on the configured data branch:

- `candidate/pending.yaml`: cumulative, deduplicated proxy list ready for phone probing.
- `candidate/pending-manifest.json`: node IDs/names and SHA-256 of the exact YAML bytes.
- `candidate/pending-meta.json`: latest candidate commit, ordered source commits, batch count, node count, generation time and matching SHA-256.

`build-pending-queue.yml` runs when `candidate/subscription.yaml` or `receipts/latest.json` changes on `published-subscriptions`. It uses the existing cloud-side `scripts/candidate_queue.py` implementation to reconcile the remote receipt cursor, fetch immutable snapshots, verify each snapshot, and merge by canonical fingerprint. It then publishes the prepared queue. If no untested batches remain, it removes stale pending files so the phone cannot accidentally test an already-consumed queue.

The Android client only downloads these three files, verifies their hashes, manifest node identities and metadata counts, and stores the exact bytes locally. It does not run Git history, YAML merge, or fingerprint logic. A missing, malformed or unverifiable cloud queue must stop the task without advancing any cursor or deleting local data.

## Receipt validation before upload

The current uploader requires:

- Receipt JSON schema version 1 and `status=completed`.
- A valid 40-character candidate commit and candidate commit list.
- A valid candidate SHA-256 matching both candidate YAML bytes and manifest.
- Candidate proxy names and manifest node names to match exactly, with unique node IDs.
- Exactly one result for every manifest node ID; no duplicates or unknown IDs.
- Queue count and receipt count to match when those fields are present.
- Candidate commit to equal the newest commit in the receipt's candidate commit list.
- Candidate and manifest sidecars to exist beside the receipt.

An interrupted or partial receipt is not uploadable and must not advance the queue cursor.

## Upload and remote confirmation

1. Upload over GitHub SSH on port 443 using `ssh://git@ssh.github.com:443/owner/repository.git`. Never embed a token or private key in source.
2. Require the configured data branch to exist. Do not create it from `main` or a new unrelated history.
3. Stage exactly the configured receipt path plus `receipts/candidate.yaml` and `receipts/manifest.json`.
4. Commit and push normally. On a concurrent update, fetch and fast-forward rebase, retry at most three times. Never force-push.
5. After GitHub accepts the push, fetch the remote branch again and verify that the receipt and both sidecars are present at the accepted remote revision and match their expected hashes/coverage.
6. Only then persist the local cursor in SQLite and allow Storage Manager cleanup. The cursor policy requires the proof backend identity and remote revision to match the configured backend and accepted receipt commit.

A successful `git push` exit code is an upload acknowledgement, not by itself a verified publication proof. If remote verification fails or the network is interrupted, retain all local files and retry verification/upload safely.

## Receipt identity

The current Termux receipt schema does not contain a `receipt_id` field. Until the backend schema is versioned to add one, the Android client derives a stable receipt identity as SHA-256 of the exact `receipts/latest.json` bytes. This identity is separate from `candidate_sha256` and from the aggregate bundle hash. Do not rewrite or re-serialize the receipt before deriving this identifier.

## Hash distinction

The receipt's `candidate_sha256` identifies the candidate YAML snapshot; it is not a hash of the receipt bundle. The Android adapter must calculate and persist a separate deterministic bundle hash covering the receipt JSON and both sidecars. The same canonical bundle hash must be used for upload verification and `RemotePublicationProof.contentSha256`.

## Source files reviewed

- `download_candidates.py`
- `scripts/candidate_queue.py`
- `termux_probe.py`
- `upload_receipt.py`
- `scripts/finalize_phone_receipt.py`

The original Termux uploader intentionally keeps the local receipt and sidecars. This Android client may clean its own task payloads only after the remote verification gate above.
