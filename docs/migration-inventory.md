# Existing probe migration inventory

Reviewed against the current `ccvvv/AutoSubHub` main branch. This is an inventory, not a copy of backend code.

## Runtime dependencies found

| Component | Current implementation | Android client implication |
| --- | --- | --- |
| Python | `termux_probe.py`, `download_candidates.py`, `upload_receipt.py` | Package a compatible ARM64 Python runtime and its required standard library |
| Python packages | `requests`, `PyYAML` | Bundle/install pinned wheels or build them into the runtime |
| Internal Python modules | `scripts.candidate_queue`, `scripts.streaming_checker` | Migrate these with the probe as a versioned probe bundle |
| Git CLI | Candidate history fetch, remote inspection, receipt push | Needed unless Git operations are deliberately replaced with a separately validated implementation |
| SSH client | Git transport to `ssh.github.com:443` | Must support the generated/imported key and Git's SSH transport |
| Mihomo | Separate Android ARM64 executable | Must be packaged for the app's own Android execution model; Linux CI binaries are not suitable |
| Android shell wrapper | `app_control.sh` | Do not copy as-is: it hardcodes Termux paths and process lookup |

## Probe execution model

The existing probe is not just a Python HTTP script:

1. Load a local candidate YAML together with its manifest and metadata, and verify matching SHA256 values.
2. Validate candidate names and manifest node IDs.
3. Generate a temporary Mihomo config with one selector group and one local mixed listener per worker.
4. Validate and launch Mihomo as a child process.
5. Poll its local controller API and inspect proxy/selector membership.
6. Use a Python `ThreadPoolExecutor` with the requested worker count. Each worker tests the selected node through its corresponding local listener.
7. Write progress atomically and preserve pause/stop marker behavior.
8. Save a complete receipt and matching candidate/manifest sidecars.

The current CLI validates worker counts from 1 through 300. This is an existing supported configuration range, not proof that every Android device can sustain 300 workers.

## Candidate download and receipt upload

- Candidate download uses Git history on `published-subscriptions`, requests, PyYAML, and a local cursor file. The app adaptation must replace hard-coded repository/branch assumptions with explicit configuration.
- Receipt upload validates the completed receipt and its candidate/manifest sidecars, verifies hashes and full node coverage, then pushes the bundle to `published-subscriptions` through Git SSH-over-port-443.
- The uploader advances the local queue cursor only after GitHub accepts the bundle. It intentionally keeps the local receipt and sidecars.
- Do not weaken these validation and recovery rules when changing the transport or UI.

## Migration sequence

1. Keep the current probe scripts as the behavioral reference; do not rewrite the algorithm during the first port.
2. Create an app-private workspace and adapt paths/configuration without assuming `$HOME/AutoSubHub` or Termux's fixed prefix.
3. Prove the Android 14–16 execution mechanism for the app's own package ID before bundling Python, Git, SSH or Mihomo.
4. Run a small local-candidate test on device and compare its receipt structure with the existing Termux implementation.
5. Increase worker count gradually, then run the same candidate/network benchmark at 300 workers.
6. Only after parity and stability checks should the standalone app become the recommended client.

## Important separation

The Android app repository owns the client, runtime packaging and transport adapter. The AutoSubHub repository remains the backend/data authority. No backend workflow or published data is modified by this inventory.
