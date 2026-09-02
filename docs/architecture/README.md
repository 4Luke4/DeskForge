# Architecture

## Data flow

1. The application requests the immutable Fedora asset-pack set from Google Play.
2. The installer validates every declared part and the complete payload before extracting to private staging.
3. A successful extraction is selected through an atomic active-installation manifest.
4. `DeskForgeEngine` validates the installed PRoot size and SHA-256 before asking the native
   supervisor to launch the separately packaged executable.
5. PRoot starts the pinned Fedora TigerVNC host and XFCE inside the selected root filesystem.
   Its device view is allowlisted to basic character devices, PTYs, and private shared memory; host
   Binder, raw audio, input, and other Android device nodes are not bound into the guest.
6. Xvnc exposes RFB only through a mode-0600 socket bound from the unique app-private runtime directory.
7. The main process pre-binds a second private socket and transfers only its descriptor to a
   non-exported isolated renderer service. The service authenticates guest peers with `SO_PEERCRED`,
   runs VirGL over surfaceless EGL/GLES, and never receives Binder, document, audio, or signing access.
8. Fedora validates VirGL with `glxinfo` before XFCE; any unavailable or software-only host renderer
   selects llvmpipe. Xvnc/RFB presentation is unchanged in both modes.
9. The original native RFB client validates and renders framebuffer updates and forwards bounded input.
10. Android IME commits are converted into bounded Unicode keysyms; clipboard text crosses the RFB
   boundary only after the corresponding explicit Android action.
11. Fedora PipeWire writes and reads fixed PCM through mode-0600 FIFOs in the same private runtime
   mount. Native workers bridge those FIFOs to bounded AAudio callback buffers.
12. Playback begins only after Android audio focus. Microphone samples enter the guest only after
   runtime permission and per-session consent; disabling capture drains and zeroizes native state.
13. Native lifecycle results are converted into `SessionState`; only a positive supervised PID with a
   negotiated display connection is represented as `Running`.

This milestone accelerates guest OpenGL through VirGL. It does not enable Venus, guest Vulkan, or a
Vulkan-backed Android RFB presenter.

## Ownership boundaries

- Kotlin/Compose owns application state, navigation, permissions, accessibility, and user consent.
- C++ owns capability probing, bounded graphics and RFB protocol handling, presentation, and deterministic process-group cleanup.
- PRoot is an independent GPL-2.0-or-later executable and is not linked into DeskForge libraries.
- Fedora is an upstream operating-system payload, not DeskForge product code.

## Failure behavior

Missing or unverified runtime components fail closed. The PRoot executable and its loader remain in
Android's read-only native-library directory and are independently digest-checked. `codeCacheDir`
provides owner-only scratch space, which the Kotlin boundary clears without following symlinks.
Partial installations remain in uniquely named staging directories and are removed on error. Guest
process groups are terminated together.
Unsupported GPUs select llvmpipe with a structured diagnostic reason; they do not silently claim
hardware acceleration. An accelerated renderer process lost during a session fails the session so
the next launch can select software deterministically. Clipboard exchange is manual,
plain-text-only, and bounded to 1 MiB. Audio uses fixed 48 kHz signed 16-bit PCM with bounded buffers;
transport or route failures remain visible and do not silently claim readiness.
