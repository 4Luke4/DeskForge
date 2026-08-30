# Changelog

All notable changes to DeskForge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Physical ARM64 tablet qualification gate for graphics, audio, input, thermal behavior, and long sessions.
- Human-review gate for every non-English translation.
- Google Play policy, paid-listing, privacy, and signing release gates.

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

[Unreleased]: https://github.com/4Luke4/DeskForge/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/4Luke4/DeskForge/releases/tag/v0.1.0
