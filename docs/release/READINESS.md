# Release Readiness

DeskForge 0.5.0 is a development milestone. Merging local audio integration does not authorize a
production build or Google Play publication. Every production gate below requires retained evidence
and an explicit maintainer decision.

| Gate | Required evidence | Merge blocker | Production blocker |
| --- | --- | --- | --- |
| Pull-request verification | Required CI, CodeQL, and dependency review jobs pass | Yes | Yes |
| Emulator verification | Manually dispatched API 34/latest-stable ARM64 emulator jobs pass on protected `main` | No | Yes |
| Runtime packaging | Two byte-identical API 34 ARM64 builds; ELF/APK/AAB digest checks; corresponding PRoot/talloc source and notices; protected-main emulator smoke evidence | No | Yes |
| Fedora delivery | Signed-image/RPM verification and every generated Play asset pack below its enforced limit | No | Yes |
| Desktop integration | XFCE frame, touch, mouse, physical/software keyboard, manual clipboard, playback route changes, and permission-gated microphone evidence | No | Yes |
| Physical qualification | Completed device matrix covering graphics, audio, input, thermals, lifecycle, and long sessions | No | Yes |
| Localization | Native-speaker approval recorded for every non-English resource set | No | Yes |
| Guest security boundary | Adversarial validation finds no same-UID Android IPC, inherited-descriptor, procfs, or device-node path around microphone consent | No | Yes |
| Legal and Play policy | Copyleft offer, trademark review, privacy disclosure, paid-listing review, and Data safety answers | No | Yes |
| Signing | Protected GitHub environment approval and ephemeral production keystore secrets | No | Yes |

The release workflow intentionally prepares and retains a signed App Bundle but does not publish it.
It requires the run ID of a successful Fedora-asset workflow for the exact tagged commit, restores
that run's multi-pack payload and corresponding TigerVNC source, and revalidates every part and the
aggregate digest before signing. A missing or mismatched payload, digest, source artifact, or
separately packaged PRoot executable fails closed. Google Play upload remains a deliberate
human-controlled operation outside this milestone.

Audio merge evidence requires the exact-commit Fedora asset workflow to validate the PipeWire
package inventory and schema-3 guest configuration. GitHub's no-audio emulator can verify permission,
foreground-service, and failure behavior but cannot qualify acoustic playback or capture. Physical
tablets must retain route, focus, denial, one-time grant, persistent grant, revocation, background
capture, notification stop, and post-disable silence evidence before production approval.

Fedora asset preparation is manually dispatched and has a 35-minute hard timeout. It verifies the
pinned image before mounting it read-only, applies signed RPMs through an ephemeral writable overlay,
and streams the merged tree directly into parallel gzip. The workflow logs each expensive stage and
uploads the already-compressed payload without artifact recompression; a preparation exceeding the
30-minute operational target requires investigation rather than a timeout increase.
