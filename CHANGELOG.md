# Changelog

All notable changes to DeskForge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- API 34 x86_64 tablet instrumentation on Ubuntu/KVM for fast pull-request feedback, with retained
  UI, logcat, and informational frame-statistics evidence.
- Checksum-pinned X.Org Server 21.1.24 and xorgproto 2025.1 source inputs, reproducible source
  evidence, and the accepted fail-closed DRI3/Present direct-display architecture for milestone 0.9.
- A debug-only, non-exported isolated display service with a native public-NDK hardware-buffer
  capability probe, bounded pixmap/byte/Present accounting, and rejection checks for protected,
  YUV, multi-layer, oversized, duplicate, and in-flight buffers.
- Physical ARM64 tablet qualification gate for graphics, audio, input, thermal behavior, and long sessions.
- Human-review gate for every non-English translation.
- Google Play policy, paid-listing, privacy, and signing release gates.

### Changed

- Debug artifacts now include an x86_64 native engine solely for KVM-backed QA; release artifacts
  and the supported product ABI remain ARM64-only.

### Fixed

- Renderer diagnostics now initialize their requested policy before constructing the initial
  fallback snapshot, preventing an early activity-start crash exposed by emulator instrumentation.

## [0.8.0] - 2026-09-03

### Added

- A native EGL presenter that imports a CPU-writable Android hardware buffer directly as an EGL
  image when the public Android and driver capabilities qualify.
- A portable damage-bounded EGL texture-upload path for devices that cannot import the requested
  hardware buffer, with the active path reported independently from the guest renderer.
- Persisted Native EGL and explicit compatibility RFB presentation policies, plus target/active
  refresh rate, submitted frame rate, p95 frame time, maximum frame time, and missed-budget
  diagnostics.

### Changed

- Surface frame-rate requests now use Android's default compatibility for interactive content and
  select only the active mode's platform-declared seamless alternative refresh rates.
- TigerVNC's pinned `FrameRate` limit now follows the selected 30–240 Hz display target instead of
  retaining its 60 fps default.
- Fedora workspaces now require integration 4 so the refresh-aware desktop bootstrap is installed
  transactionally.
- Renderer and presentation state are modeled independently, preventing an accelerated guest
  renderer from implying an accelerated Android presentation path.

### Security

- Native presentation fails closed on EGL or Surface loss. Compatibility RFB is never selected as
  an automatic fallback and remains an explicit between-session user choice.
- Hardware-buffer producer and GPU consumer access is serialized before buffer reuse; damaged
  rectangles and all framebuffer allocations remain bounded by the existing RFB limits.

### Known limitations

- The native presenter still consumes the app-private TigerVNC RFB stream. It removes CPU scaling
  and can eliminate the final texture copy on qualified drivers, but is not end-to-end X11 zero-copy
  and must not be described as direct scanout.
- Sustained 60/90/120 fps remains a production claim only after the documented multi-vendor physical
  device matrix passes. The portable EGL upload path is the compatibility target for GPUs that do
  not qualify for hardware-buffer import.

## [0.7.0] - 2026-09-03

### Added

- Source-pinned Venus support in the isolated virglrenderer runtime, including its separately
  executed render server and host Vulkan 1.1 extension qualification.
- Auto, Venus, VirGL, and llvmpipe renderer policies with app-private persistence and explicit
  failure for unavailable forced renderers.
- Highest same-resolution seamless Android refresh-rate requests and presentation-path diagnostics.

### Changed

- Auto renderer selection now qualifies Venus with Mesa Zink first, then VirGL, then llvmpipe.
- Fedora workspaces now require schema 5 and workspace integration 3, including the virtio Vulkan
  ICD used by Venus.
- Guest renderer readiness uses filesystem events instead of a fixed-interval polling loop.
- Matching RFB frames use RGBX row copies instead of per-pixel channel conversion and CPU scaling.

### Security

- Venus retains the isolated renderer UID, authenticated private socket, bounded commands and
  resources, process limits, and fail-closed forced-renderer behavior.

### Known limitations

- RFB remains the presentation path. This milestone does not claim direct GPU presentation or
  native 60/90/120 fps; those require a separate X server/presenter implementation and physical
  Adreno and Mali qualification.

## [0.6.0] - 2026-09-02

### Added

- Source-pinned, reproducibly built VirGL guest OpenGL runtime using virglrenderer 1.3.0 and libepoxy 1.5.10.
- A non-exported isolated renderer service with a pre-bound private socket, peer-credential validation, bounded protocol input, process limits, and hardware EGL/GLES self-test.
- Fedora VirGL guest-driver and `glxinfo` qualification with an explicit llvmpipe fallback before XFCE starts.

### Changed

- Graphics diagnostics now report structured guest OpenGL state instead of preliminary Vulkan-loader presence.
- Fedora workspaces now require schema 4 and workspace integration 2, including pinned graphics packages and Mesa driver validation.
- RFB remains the display transport; VirGL accelerates guest OpenGL and does not expose Android Binder or device nodes to the guest.

### Security

- Renderer protocol handling runs under an isolated Android UID with 768 MiB address-space, 256-descriptor, zero-core, client, and command limits.
- Renderer startup fails over to llvmpipe without weakening installation, runtime activation, microphone consent, or process-shutdown boundaries.

### Known limitations

- VirGL and llvmpipe behavior still require the physical ARM64 tablet matrix before production distribution.
- Protected-main emulator, native-speaker translation, legal, and Google Play review remain required.

## [0.5.0] - 2026-09-01

### Added

- Local Fedora playback through a bounded private PipeWire-to-AAudio bridge.
- Permission-gated Android microphone capture with explicit per-session consent and notification control.
- Audio-focus handling, route recovery diagnostics, and stale-workspace upgrade enforcement.

### Changed

- Fedora workspaces now require the schema-3 audio integration and are upgraded transactionally.
- The foreground session declares media-playback behavior and adds microphone service ownership only after consent.

### Security

- Microphone samples remain native, are zeroized when capture ends, and are never stored in Kotlin state or logs.
- Guest audio uses mode-0600 FIFOs inside the existing app-private runtime mount with no network listener.
- Guest device bindings now exclude Android Binder, raw audio, input, and unrelated host device nodes.

### Known limitations

- Accelerated rendering and physical ARM64 tablet audio qualification remain release blockers.
- Protected-main emulator, native-speaker translation, legal, and Google Play review remain required.

## [0.4.0] - 2026-09-01

### Added

- Explicit Android software-keyboard input with bounded Unicode RFB key forwarding.
- Manual plain-text clipboard transfers from Android to Linux and from Linux to Android.
- Extended RFB clipboard capability negotiation with bounded UTF-8 and compressed payload validation.

### Changed

- TigerVNC exposes only its clipboard selection; X11 primary-selection mirroring remains disabled.
- Guest clipboard text reaches Android only after a user-requested transfer and is marked sensitive.

### Known limitations

- Audio playback and capture and accelerated rendering remain release blockers.
- Protected-main emulator and physical ARM64 tablet qualification remain required before production distribution.

## [0.3.0] - 2026-08-31

### Added

- Interactive Fedora XFCE display over an application-private TigerVNC Unix socket.
- Direct touch, mouse button and wheel, and physical-keyboard forwarding through a bounded RFB client.
- Foreground-service session ownership with persistent status and explicit notification stop control.
- Deterministic multi-pack Fedora delivery with transactional upgrades from legacy workspaces.
- Checksum-pinned Fedora TigerVNC binaries and retained corresponding source.

### Changed

- Renderer reporting now identifies the qualified RFB software path instead of treating Vulkan-loader presence as acceleration.
- Microphone permission requests are deferred until a permission-gated audio host is implemented.

### Known limitations

- Clipboard, audio playback and capture, software-keyboard text input, and accelerated rendering remain release blockers.
- Protected-main emulator and physical ARM64 tablet qualification remain required before production distribution.

## [0.2.0] - 2026-08-31

### Added

- Reproducible, checksum-pinned PRoot 5.4.0 build for API 34 ARM64 Android.
- Exact installed-runtime integrity checks before capability reporting and every guest launch.
- Read-only packaged PRoot loader execution with app-private scratch cleanup and stale-state recovery.
- Independent ELF, APK, AAB, CodeQL, and ARM64 emulator verification paths.
- Retained PRoot and talloc corresponding-source bundle with exact license and patch provenance.

### Known limitations

- The desktop display, input forwarding, clipboard, and audio bridges remain integration boundaries.
- Fedora payload partitioning must be completed before the Play Asset Delivery size gate can pass.
- Production distribution remains blocked on post-merge emulator evidence, human translation review,
  physical tablet qualification, and legal and Play policy approval.

## [0.1.0] - 2026-08-30

### Added

- Android 14–17 ARM64 tablet project with an adaptive refined-industrial Compose interface.
- Fedora XFCE 44 Play asset-pack prototype and OpenPGP-verified preparation pipeline.
- Kotlin runtime contracts and a hardened C++ PRoot process supervisor.
- Transactional USTAR extraction with path-traversal and symlink-ancestor defenses.
- Capability diagnostics, accelerated/software renderer decisions, audio probing, and opt-in microphone permission.
- English, Italian, Russian, Norwegian Bokmål, Spanish, French, German, Brazilian Portuguese,
  Simplified Chinese, Japanese, and Turkish resources.
- Repository governance, dependency automation, security analysis, release preparation, and ARM64 emulator QA workflows.

### Known limitations

- The audited Android PRoot executable is not yet packaged; runtime startup fails closed when it is absent.
- The desktop display, input forwarding, clipboard, and audio bridges are integration boundaries only.
- Fedora payload partitioning must be completed before the Play Asset Delivery size gate can pass.
- Production distribution remains blocked on human translation review and physical tablet qualification.

[Unreleased]: https://github.com/4Luke4/DeskForge/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/4Luke4/DeskForge/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/4Luke4/DeskForge/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/4Luke4/DeskForge/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/4Luke4/DeskForge/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/4Luke4/DeskForge/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/4Luke4/DeskForge/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/4Luke4/DeskForge/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/4Luke4/DeskForge/releases/tag/v0.1.0
