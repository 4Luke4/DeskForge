# Changelog

All notable changes to DeskForge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Physical ARM64 tablet qualification gate for graphics, audio, input, thermal behavior, and long sessions.
- Human-review gate for every non-English translation.
- Google Play policy, paid-listing, privacy, and signing release gates.

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

[Unreleased]: https://github.com/4Luke4/DeskForge/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/4Luke4/DeskForge/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/4Luke4/DeskForge/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/4Luke4/DeskForge/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/4Luke4/DeskForge/releases/tag/v0.1.0
