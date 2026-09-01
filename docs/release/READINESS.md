# Release Readiness

DeskForge 0.4.0 is a development milestone. Merging text-input and clipboard integration does not authorize a
production build or Google Play publication. Every production gate below requires retained evidence
and an explicit maintainer decision.

| Gate | Required evidence | Merge blocker | Production blocker |
| --- | --- | --- | --- |
| Pull-request verification | Required CI, CodeQL, and dependency review jobs pass | Yes | Yes |
| Emulator verification | Manually dispatched API 34/latest-stable ARM64 emulator jobs pass on protected `main` | No | Yes |
| Runtime packaging | Two byte-identical API 34 ARM64 builds; ELF/APK/AAB digest checks; corresponding PRoot/talloc source and notices; protected-main emulator smoke evidence | No | Yes |
| Fedora delivery | Signed-image/RPM verification and every generated Play asset pack below its enforced limit | No | Yes |
| Desktop integration | XFCE frame, touch, mouse, physical/software keyboard, manual clipboard, playback, and permission-gated microphone evidence | No | Yes |
| Physical qualification | Completed device matrix covering graphics, audio, input, thermals, lifecycle, and long sessions | No | Yes |
| Localization | Native-speaker approval recorded for every non-English resource set | No | Yes |
| Legal and Play policy | Copyleft offer, trademark review, privacy disclosure, paid-listing review, and Data safety answers | No | Yes |
| Signing | Protected GitHub environment approval and ephemeral production keystore secrets | No | Yes |

The release workflow intentionally prepares and retains a signed App Bundle but does not publish it.
It requires the run ID of a successful Fedora-asset workflow for the exact tagged commit, restores
that run's multi-pack payload and corresponding TigerVNC source, and revalidates every part and the
aggregate digest before signing. A missing or mismatched payload, digest, source artifact, or
separately packaged PRoot executable fails closed. Google Play upload remains a deliberate
human-controlled operation outside this milestone.
