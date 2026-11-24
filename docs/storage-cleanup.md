# Local Storage Cleanup Policy

## Goal

Automatically reclaim phone storage after a probe batch has finished and its complete result bundle has been confirmed on the remote backend. Cleanup applies only to the Android app's private local workspace. It never deletes files from GitHub or the backend's published data branch.

## The two required conditions

A local test artifact is eligible for automatic deletion only when both are true:

1. **Probe completed:** the batch reached a terminal successful state, the receipt is complete, every expected node has a result, and the receipt references the exact candidate snapshot and manifest used for that run.
2. **Remote upload verified:** the backend accepted the upload, the client can read back the remote commit or equivalent immutable publication identifier, and all expected remote files are present with matching SHA256 values.

A successful local Git push exit code or a UI message saying "uploaded" is not enough by itself. The app must verify remote state.

## File lifecycle

| Local file category | Delete automatically? | Rule |
| --- | --- | --- |
| Candidate YAML and its manifest/metadata snapshot | Yes | Only after the completed batch's full receipt bundle is verified remotely |
| Completed probe receipt and its candidate/manifest sidecars | Yes | Only after remote read-back, hashes and node coverage all pass |
| Temporary Mihomo config, generated proxy lists and per-run scratch files | Yes | After the associated process has stopped and the batch has reached a terminal state |
| Temporary logs and diagnostic traces | Yes, bounded | Delete after successful completion; retain recent failure diagnostics for troubleshooting |
| Untested candidates | No | Keep until tested or explicitly discarded by the user |
| Test results waiting for upload | No | Keep and retry upload; never clean on network failure |
| Failed, interrupted or incomplete receipts | No | Keep for recovery or manual review |
| SSH private keys, tokens and credentials | No | Managed separately by the security module |
| App settings, backend configuration and queue cursor | No | Persistent application state |
| Compact batch completion marker | Keep | Small local record prevents duplicate work and supports recovery |

## Safe cleanup transaction

1. Mark a batch as `PROBE_COMPLETE` only after the probe process exits and the receipt passes local validation.
2. Mark it as `UPLOAD_PENDING`; preserve every input and output artifact.
3. Upload the complete bundle. Use an idempotent batch identifier so retries do not create ambiguous duplicate receipts.
4. Read back the remote publication. Verify the commit/publication identifier, expected paths, file hashes, receipt schema, candidate/manifest hashes and complete node coverage.
5. Atomically persist a local `REMOTE_VERIFIED` state containing the remote commit identifier and verified file hashes.
6. Stop all child processes and release file handles.
7. Delete only the exact eligible files listed in the batch manifest, after confirming each path remains inside the app-private workspace.
8. Persist a compact `CLEANED` marker and report reclaimed bytes.

If the app crashes at any point, recovery resumes from the last durable state. Re-running cleanup must be safe. If any verification step fails, files remain on disk.

## User controls

- Show total app workspace usage and a breakdown by candidates, completed batches, temporary runtime data and logs.
- Default policy: automatically remove verified uploaded batch files after successful remote verification.
- Offer a manual "clean verified files" action.
- Offer a configurable diagnostic-log retention period.
- Show what will be deleted and the expected space reclaimed before a manual cleanup.
- Never provide a "clean everything" operation that can remove untested candidates or pending uploads without explicit, separate confirmation.

## Important distinction

"Tested" does not mean "uploaded". A completed test whose receipt is not yet verified remotely must remain on the phone. "Uploaded" does not mean "safe to delete" until the complete remote bundle is read back and validated.
