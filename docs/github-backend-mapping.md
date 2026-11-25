# AutoSubHub GitHub Backend Mapping

This mapping reflects the current AutoSubHub repository data contract.

## Configured data target

- User input: one GitHub repository URL only (for example `https://github.com/ccvvv/AutoSubHub`).
- The Android client locks the data branch internally to `published-subscriptions`; users do not enter or select a branch.
- Repository default: `ccvvv/AutoSubHub`
- Data branch: `published-subscriptions`
- Candidate snapshot: `candidate/subscription.yaml`
- Candidate manifest: `candidate/manifest.json`
- Latest phone receipt: `receipts/latest.json`
- Uploaded candidate sidecar: `receipts/candidate.yaml`
- Uploaded manifest sidecar: `receipts/manifest.json`

## Candidate snapshot download

The Android client must read the original candidate pool rather than the removed pending-queue files.

1. Verify the configured repository and data branch exist.
2. Query the latest commit touching `candidate/subscription.yaml`.
3. Download `candidate/subscription.yaml` and `candidate/manifest.json` at that exact commit SHA, not at a moving branch name.
4. Verify manifest schema version, SHA-256 of the exact YAML bytes, unique node names/IDs, and the number of top-level proxy entries against the manifest `nodes` array length. The `unique_candidates` field describes the earlier globally deduplicated source pool, not the current published YAML count; it must not be compared with `nodes.length`.
5. Store the YAML and manifest together as an immutable local generation.

The immutable commit pin prevents a branch update between two downloads from mixing a YAML file from one generation with a manifest from another. The app does not enumerate candidate history, merge snapshots, or implement a second candidate cursor.

## Receipt validation before upload

The current backend receipt contract expects:

- Receipt JSON schema version 1 and `status=completed`.
- A valid 40-character candidate commit and candidate commit list.
- A valid candidate SHA-256 matching both candidate YAML bytes and manifest.
- Candidate proxy names and manifest node names to match exactly, with unique node IDs.
- Exactly one result for every manifest node ID; no duplicates or unknown IDs.
- Candidate count and receipt coverage to match.
- Candidate commit to equal the newest commit in the receipt candidate commit list.
- Candidate and manifest sidecars beside the receipt.

An interrupted or partial receipt is not uploadable and must not advance any cursor.

## Upload and remote confirmation

1. Upload over GitHub SSH on port 443 using `ssh://git@ssh.github.com:443/owner/repository.git`. Never embed a token or private key in source.
2. Require the configured data branch to exist. Do not create it from `main` or an unrelated history.
3. Stage exactly the receipt path plus `receipts/candidate.yaml` and `receipts/manifest.json`.
4. Commit and push normally. On a concurrent update, fetch and fast-forward rebase, retry at most three times. Never force-push.
5. Fetch the remote branch again and verify the receipt and both sidecars at the accepted remote revision.
6. Only after verification may the local task become cleanup-eligible.

A successful `git push` exit code is an upload acknowledgement, not a verified publication proof. If verification fails or the network is interrupted, retain all local files and retry safely.

## Runtime and compatibility boundary

The existing Termux uploader remains the known-good operational path. The Android app must not replace it until real device tests prove candidate download, real proxy probing, receipt compatibility, SSH upload, remote verification, and recovery after interruption.
