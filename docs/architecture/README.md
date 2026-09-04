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
   non-exported isolated renderer service. The service authenticates guest peers with `SO_PEERCRED`
   and never receives document, audio, or signing access. It starts the pinned virglrenderer runtime
   in Venus or VirGL mode according to the user policy and host capability probe.
8. Venus qualification requires Android Vulkan 1.1 and the external-memory, DRM-format-modifier,
   and foreign-queue-family extensions declared in `config/graphics/runtime.json`. The render-server
   worker remains a separately executed, app-private native artifact. VirGL uses surfaceless EGL/GLES.
9. Fedora qualifies the selected path before XFCE: Zink over Venus first in automatic mode, then
   VirGL, then llvmpipe. Forced renderer policies fail closed instead of silently changing backend.
   The private Xvnc/RFB transport is unchanged in every renderer mode.
10. The native RFB client validates framebuffer updates, requests Android-native RGBA byte order,
    and forwards bounded input. In the default presentation policy it transfers only accumulated
    damage to a dedicated EGL thread.
11. The EGL presenter first qualifies a public `AHardwareBuffer` with CPU-write and GPU-sampled-image
    usage. A qualifying buffer is imported as an EGL image; drivers that reject the allocation or
    import use a bounded `glTexSubImage2D` damage upload instead. EGL initialization or runtime loss
    fails the session rather than silently switching to the compatibility RFB presenter.
12. The Android surface requests the highest platform-declared seamless alternative for the active
    display mode using interactive/default frame-rate compatibility. Target and active rates remain
    separate diagnostics because the request is not a guarantee.
13. Android IME commits are converted into bounded Unicode keysyms; clipboard text crosses the RFB
   boundary only after the corresponding explicit Android action.
14. Fedora PipeWire writes and reads fixed PCM through mode-0600 FIFOs in the same private runtime
   mount. Native workers bridge those FIFOs to bounded AAudio callback buffers.
15. Playback begins only after Android audio focus. Microphone samples enter the guest only after
   runtime permission and per-session consent; disabling capture drains and zeroizes native state.
16. Native lifecycle results are converted into `SessionState`; only a positive supervised PID with a
   negotiated display connection is represented as `Running`.

This milestone separates guest rendering from Android presentation. `NATIVE_HARDWARE_BUFFER` means the decoded
desktop buffer is sampled directly by EGL without a texture upload; it does not mean DRI3 zero-copy
from a guest application or direct display scanout. Xvnc/RFB remains the private X-server transport.
End-to-end X11 buffer sharing is a later architecture milestone and sustained 60/90/120 Hz remains
gated on the physical-device protocol.

The accepted DeskForge 0.9 target is documented in
[DRI3/Present Direct-Display Architecture](DIRECT_DISPLAY.md). Its source manifest and security
limits are reviewable now, but they do not change the active Xvnc/RFB runtime until the isolated
server, DDX, Fedora integration, and qualification stages land.

## Ownership boundaries

- Kotlin/Compose owns application state, navigation, permissions, accessibility, and user consent.
- C++ owns capability probing, bounded graphics and RFB protocol handling, EGL presentation, and deterministic process-group cleanup.
- PRoot is an independent GPL-2.0-or-later executable and is not linked into DeskForge libraries.
- Fedora is an upstream operating-system payload, not DeskForge product code.

## Failure behavior

Missing or unverified runtime components fail closed. The PRoot executable and its loader remain in
Android's read-only native-library directory and are independently digest-checked. `codeCacheDir`
provides owner-only scratch space, which the Kotlin boundary clears without following symlinks.
Partial installations remain in uniquely named staging directories and are removed on error. Guest
process groups are terminated together.
Automatic mode selects llvmpipe with a structured diagnostic reason when neither accelerated path
qualifies; forced modes fail rather than silently changing renderer. An accelerated renderer process
lost during a session fails the session so the next launch can select software deterministically.
The Android surface receives the highest platform-declared seamless alternative refresh-rate hint,
but this is a scheduling request rather than evidence that the pipeline sustains that rate. Native
EGL failure ends the session; the legacy CPU presenter is entered only through the explicit RFB
setting. Clipboard exchange
is manual, plain-text-only, and bounded to 1 MiB. Audio uses fixed 48 kHz signed 16-bit PCM with
bounded buffers; transport or route failures remain visible and do not silently claim readiness.
