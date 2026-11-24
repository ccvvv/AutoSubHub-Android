# Runtime Execution Validation

## Implemented foundation

- `ProcessCommandRunner` launches an absolute executable with an argument vector. It never builds a shell command string.
- Standard output and standard error are drained concurrently and captured with per-stream memory limits.
- Every command has a timeout; timed-out processes are terminated and reported with bounded partial output.
- `RuntimeEnvironment.inspect()` reports Android API, target SDK, supported ABIs, native-library directory readability, app-files writability and the `extractNativeLibs` manifest flag.
- The home screen displays these diagnostics without claiming that an executable runtime is installed.

## Not yet proven

The presence of a writable app directory or a readable native-library directory does not prove that Android will allow a child ELF process to execute. No Bash, Python, Git, OpenSSH or Mihomo binary is bundled yet. The runtime report deliberately keeps `elfExecutionVerified=false`.

## Required next gate

Build a tiny ARM64 Android-native executable with a harmless `--self-test` mode, package it in the APK's native-library area, and execute it through the same process runner on a real Android device with the app's actual package ID and target SDK 35. Record:

- Android API level and ABI
- APK extraction behavior and actual executable path
- exact process exit code and stdout/stderr
- whether execution works after app update and process restart
- behavior when the executable is absent or denied

Only after this proof should the packaging strategy for Bash/Python/Git/OpenSSH/Mihomo be selected. Do not treat successful file extraction as successful execution.

## Process lifecycle caveat

Android does not manage arbitrary child processes as durable app work. Long-running probes will need an explicit foreground-task lifecycle, notification and restart/recovery strategy. The command runner is only the low-level execution primitive; it is not itself a background task manager.
