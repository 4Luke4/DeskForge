# DeskForge

DeskForge is an original Android implementation of a local Linux desktop environment for
64-bit ARM tablets. The application combines an adaptive Kotlin/Jetpack Compose product surface
with a C++ runtime boundary and a separately executed upstream PRoot binary.

> [!IMPORTANT]
> Version 0.3.0 adds the interactive Fedora XFCE display and input vertical slice. It is not
> approved for production distribution. Physical tablet, translation, Google Play policy, and
> third-party licensing gates remain mandatory before release.

## Product contract

- Application ID: `com.deskforge.app`
- Android: API 34 minimum; API 37 compile and target
- Devices: `arm64-v8a` and at least 720dp smallest width
- Qualified preset: official Fedora XFCE 44 AArch64
- Distribution: paid Google Play listing with Play Asset Delivery
- Rendering: qualified RFB software path; capability-based acceleration remains planned
- Audio: playback and permission-gated microphone integration remain disabled

DeskForge-owned code, product integration, design, assets, and documentation are proprietary and
original. Fedora, PRoot, X.Org, Mesa, and every other third-party component retain their upstream
licenses and identities.

## Architecture

The Compose application owns onboarding, workspace management, permissions, lifecycle state, and
diagnostics. `DeskForgeEngine` is the stable Kotlin boundary to the C++ supervisor. The native
engine probes host capabilities and provides a fail-closed launch contract for the separately
packaged, exact-digest PRoot process; GPL PRoot code is never linked into a proprietary DeskForge
library.

Fedora is sourced only from the official signed release image. CI validates Fedora's OpenPGP
checksum, creates a root filesystem payload, enforces Play asset-pack size limits, and builds the
Android App Bundle. Installation is staged and atomically activated so an interrupted extraction
cannot replace a working workspace.

See [Architecture](docs/architecture/README.md), [Security model](docs/architecture/THREAT_MODEL.md),
[release readiness](docs/release/READINESS.md), and [third-party notices](docs/legal/THIRD_PARTY_NOTICES.md).

## Verification

Repository policy requires verification through GitHub Actions. The workflows perform reproducible
PRoot builds, ELF and package-integrity checks, Android and native builds, static analysis, unit tests,
ARM64 emulator QA on API 34 and the latest available
stable image (currently API 36), dependency review,
CodeQL analysis, artifact checks, and supply-chain validation. Generated screenshots, UI trees,
and logcat records are retained with emulator jobs. Because emulator provisioning is
resource-intensive, maintainers dispatch emulator QA manually and only against the protected
`main` branch; pull requests use the standard CI and security checks as merge evidence.

No signing material belongs in the repository. The release workflow accepts ephemeral signing
secrets only after Play and legal release gates have been approved.

## Status

DeskForge is under active initial development. The verified PRoot runtime can now launch an
interactive Fedora XFCE framebuffer through deterministic multi-pack delivery and an app-private
RFB transport. A release remains deliberately blocked until post-merge runtime evidence,
accelerated graphics, clipboard and audio integration, and physical-device qualification are complete.

## License

Copyright © 2026 4Luke4. All rights reserved. See [LICENSE.md](LICENSE.md). Third-party components
are excluded from that proprietary grant and are documented separately.
