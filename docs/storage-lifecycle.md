# Storage Lifecycle and Safe Cleanup

## Goal

Prevent completed probe batches and uploaded payloads from accumulating indefinitely on the phone, without losing unuploaded results or the ability to recover from interrupted uploads.

## Core safety rule

**A file is not deletable merely because probing finished or an upload command returned success.** Large task artifacts become cleanup-eligible only after the backend adapter independently verifies that the complete receipt bundle exists remotely and matches the expected content hash.

## Artifact classes

| Artifact | Before remote confirmation | After remote confirmation |
| --- | --- | --- |
| Candidate YAML and manifest/metadata | Keep; required for retry and receipt validation | Delete according to cleanup policy |
| Full receipt JSON and sidecars | Keep; never discard an unconfirmed result | Delete payload after audit record is safely stored |
| Temporary Mihomo config and generated runtime files | Remove after process shutdown and task finalization, unless needed for diagnosis | Remove |
| Probe scratch files and transient downloads | Remove when no process is using them | Remove |
| Debug logs | Keep while task is active or failed | Retain bounded recent logs; older logs may be pruned |
| Task summary / audit record | Keep | Keep; small and durable |
| SSH private key and credentials | Never include in task cleanup | Never include in task cleanup |

## Explicit state machine

```text
QUEUED
  -> DOWNLOADED
  -> INPUT_VERIFIED
  -> PROBING
  -> PROBE_COMPLETED_LOCAL
  -> UPLOAD_PENDING
  -> UPLOADING
  -> REMOTE_VERIFYING
  -> PUBLISHED_CONFIRMED
  -> CLEANUP_ELIGIBLE
  -> CLEANED
```

Failure states such as `PROBE_FAILED`, `UPLOAD_FAILED`, `REMOTE_VERIFY_FAILED` and `CANCELLED_WITH_DATA` are not cleanup-eligible by default. They preserve the files needed for retry or recovery.

Only the Backend Adapter may assert `PUBLISHED_CONFIRMED`. It must verify the remote receipt identifier, expected hash, required sidecars/full coverage and the accepted remote commit or equivalent backend acknowledgement. A local Git command exit code alone is insufficient.

## Cleanup behavior

1. The Task Coordinator receives a verified publication result.
2. It writes and fsyncs a compact local audit record containing task ID, batch ID, receipt ID, content hash, remote commit/acknowledgement, timestamp, candidate count, success count and cleanup result.
3. It atomically marks the payload `CLEANUP_ELIGIBLE`.
4. Storage Manager deletes only the payload files associated with that task ID, never by broad filename patterns.
5. It verifies that the files are absent and records `CLEANED` in the audit record.
6. If deletion is interrupted, the next app start resumes cleanup idempotently. If the audit record cannot be safely written, do not delete the payload.

## User controls and defaults

- Provide a Storage page showing used space by category and per-task artifact sizes.
- Provide **Clean confirmed uploads** as a manual action.
- Provide an optional automatic cleanup setting. Recommended default: enabled for temporary scratch files; ask the user before enabling deletion of full receipts/candidate bundles in the first public release.
- Allow a configurable retention count for recent diagnostic logs.
- Never clean active tasks, paused tasks, failed uploads, unverified receipts, SSH keys, user configuration or runtime packages.
- Offer a per-task **Keep local copy** override. When enabled, preserve the full verified bundle.
- Before manual cleanup, show the number of files and estimated space to be reclaimed.

## Recovery and edge cases

- Network loss after push but before acknowledgement: do not delete. On retry, query the backend for the receipt ID/hash; if found and valid, mark confirmed and continue cleanup.
- Remote commit exists but sidecar/hash verification fails: preserve all local files and report a verification error.
- App process killed during cleanup: resume from the durable cleanup journal; repeated deletion must be safe.
- User changes backend repository or branch: cleanup decisions remain tied to the original task's recorded backend identity and remote receipt proof.
- Device runs low on storage: may prune regenerable scratch/cache files first, but must never sacrifice unconfirmed receipt data.
- App uninstall/data clear: Android may remove app-private files; this is outside the app's cleanup guarantee. Users who need long-term local copies must export/backup them.

## Important compatibility note

The current Termux uploader intentionally retains the local receipt and sidecars. Do not change that existing workflow as part of this Android client design. The Android app may implement this safer post-confirmation cleanup policy independently, after parity tests prove the receipt has been accepted remotely.
