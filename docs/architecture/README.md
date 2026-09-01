# Architecture

## Data flow

1. The application requests the immutable Fedora asset-pack set from Google Play.
2. The installer validates every declared part and the complete payload before extracting to private staging.
3. A successful extraction is selected through an atomic active-installation manifest.
4. `DeskForgeEngine` validates the installed PRoot size and SHA-256 before asking the native
   supervisor to launch the separately packaged executable.
5. PRoot starts the pinned Fedora TigerVNC host and XFCE inside the selected root filesystem.
6. Xvnc exposes RFB only through a mode-0600 socket bound from the unique app-private runtime directory.
7. The original native RFB client validates and renders framebuffer updates and forwards bounded input.
8. Android IME commits are converted into bounded Unicode keysyms; clipboard text crosses the RFB
   boundary only after the corresponding explicit Android action.
9. Native lifecycle results are converted into `SessionState`; only a positive supervised PID with a
   negotiated display connection is represented as `Running`.

## Ownership boundaries

- Kotlin/Compose owns application state, navigation, permissions, accessibility, and user consent.
- C++ owns capability probing, RFB parsing and presentation, and deterministic process-group cleanup.
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
The current qualified renderer is always the RFB software path. Clipboard exchange is manual,
plain-text-only, and bounded to 1 MiB; automatic synchronization and audio remain disabled.
