# Architecture

## Data flow

1. The application requests the immutable Fedora asset pack from Google Play.
2. The installer validates the expected payload and extracts it into a private staging directory.
3. A successful extraction is atomically activated as a distro instance.
4. `DeskForgeEngine` validates the installed PRoot size and SHA-256 before asking the native
   supervisor to launch the separately packaged executable.
5. PRoot starts XFCE inside the selected root filesystem under the application UID.
6. The planned display, input, clipboard, and audio bridges use instance-private sockets; those
   bridges are explicit release blockers and are not implemented by the foundation milestone.
7. Native lifecycle results are converted into `SessionState`; only a positive supervised PID is
   represented as `Running`.

## Ownership boundaries

- Kotlin/Compose owns application state, navigation, permissions, accessibility, and user consent.
- C++ owns capability probing and deterministic process-group cleanup. Native display surfaces and
  low-latency audio are the next integration phase.
- PRoot is an independent GPL-2.0-or-later executable and is not linked into DeskForge libraries.
- Fedora is an upstream operating-system payload, not DeskForge product code.

## Failure behavior

Missing or unverified runtime components fail closed. The PRoot executable and its loader remain in
Android's read-only native-library directory and are independently digest-checked. `codeCacheDir`
provides owner-only scratch space, which the Kotlin boundary clears without following symlinks.
Partial installations remain in uniquely named staging directories and are removed on error. Guest
process groups are terminated together.
Unsupported GPUs select the software renderer with a diagnostic reason; they do not silently claim
hardware acceleration.
