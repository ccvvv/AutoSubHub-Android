# Runtime Execution Validation

## Implemented foundation

- `ProcessCommandRunner` launches an absolute executable with an argument vector. It never builds a shell command string.
- Standard output and standard error are drained concurrently and captured with per-stream memory limits.
- Every command has a timeout; timed-out processes are terminated and reported with bounded partial output.
- `RuntimeEnvironment.inspect()` reports Android API, target SDK, supported ABIs, native-library directory readability, app-files writability and the `extractNativeLibs` manifest flag.
- A tiny ARM64 PIE executable (`libruntime_probe.so`) now has a harmless `--self-test` mode. It is built with the NDK and placed in the APK native-library packaging directory.
- `RuntimeExecutionProbe` invokes that packaged executable through `ProcessCommandRunner`; the home screen has a one-tap diagnostic and reports success only for the exact expected output and exit code.
- CI installs the NDK/CMake toolchain and checks the ELF header and APK entry. This proves build/package structure only, not that Android will execute it on a real phone.

## Not yet proven

The presence of a writable app directory, a readable native-library directory, or a correctly packaged ELF does not prove that Android will allow a child ELF process to execute. The on-device self-test has not yet been run on the target phone. No Bash, Python, Git, OpenSSH or Mihomo binary is bundled yet. The runtime report deliberately keeps `elfExecutionVerified=false` until the self-test actually succeeds on-device.

## Required next gate

Install the debug APK on a real Android device and tap **Execute ELF self-test** in the runtime section. Record:

- Android API level and ABI
- APK extraction behavior and actual executable path
- exact process exit code and stdout/stderr
- whether execution works after app update and process restart
- behavior when the executable is absent or denied

Only after this proof should the packaging strategy for Bash/Python/Git/OpenSSH/Mihomo be selected. Do not treat successful file extraction as successful execution.

## Process lifecycle caveat

Android does not manage arbitrary child processes as durable app work. Long-running probes will need an explicit foreground-task lifecycle, notification and restart/recovery strategy. The command runner is only the low-level execution primitive; it is not itself a background task manager.
