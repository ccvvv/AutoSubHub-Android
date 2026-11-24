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

## Candidate history algorithm

1. Fetch the configured data branch. If the local Git repository is shallow, deepen/unshallow it before relying on full path history.
2. List only commits that add or modify `candidate/subscription.yaml` (Git path history with added/modified filtering). A commit that only deletes the path is not a candidate batch.
3. Read the local uploaded cursor and the remote `receipts/latest.json` `candidate_commit`.
4. If either cursor is present but is absent from candidate history, stop and preserve state. Never silently skip history.
5. If both cursors exist, choose the more advanced cursor (the one closer to the newest end of newest-first history). If no cursor exists, bootstrap a bounded latest window of 20 candidate commits.
6. Return pending commits oldest-to-newest so snapshot merge order is deterministic.
7. For every pending commit, download its immutable `candidate/subscription.yaml` and `manifest.json` by commit SHA. Try GitHub Raw and the configured mirrors in order; verify the candidate SHA-256 against the manifest before accepting either file.
8. Merge snapshots by canonical proxy fingerprint: JSON-encode proxy configuration with sorted keys after removing the display `name`, then SHA-256. Keep the first proxy configuration/name and record all source commits/names. The manifest node ID is that fingerprint. Resolve duplicate display names with the fingerprint suffix.

The Kotlin `CandidateQueuePlanner` already implements cursor reconciliation and order selection. `BackendCursorPolicy` validates that cursor advancement is bound to the verified backend and remote receipt commit; `TaskStore` persists that cursor only after this policy succeeds. Snapshot download, hash verification and deterministic merge remain to be implemented in the GitHub adapter/probe input pipeline.

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

## Hash distinction

The receipt's `candidate_sha256` identifies the candidate YAML snapshot; it is not a hash of the receipt bundle. The Android adapter must calculate and persist a separate deterministic bundle hash covering the receipt JSON and both sidecars. The same canonical bundle hash must be used for upload verification and `RemotePublicationProof.contentSha256`.

## Source files reviewed

- `download_candidates.py`
- `scripts/candidate_queue.py`
- `termux_probe.py`
- `upload_receipt.py`
- `scripts/finalize_phone_receipt.py`

The original Termux uploader intentionally keeps the local receipt and sidecars. This Android client may clean its own task payloads only after the remote verification gate above.
