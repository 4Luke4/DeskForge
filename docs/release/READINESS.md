# Release Readiness

DeskForge 0.1.0 is a development milestone. Merging engineering foundations does not authorize a
production build or Google Play publication. Every production gate below requires retained evidence
and an explicit maintainer decision.

| Gate | Required evidence | Merge blocker | Production blocker |
| --- | --- | --- | --- |
| GitHub verification | Required CI, CodeQL, dependency review, and API 34/latest-stable ARM64 emulator jobs pass | Yes | Yes |
| Runtime packaging | Reproducible, checksum-pinned ARM64 PRoot executable; corresponding GPL source and notices | No | Yes |
| Fedora delivery | Signed-image verification and every generated Play asset pack below its enforced limit | No | Yes |
| Desktop integration | XFCE frame, keyboard, mouse, touch, clipboard, playback, and permission-gated microphone evidence | No | Yes |
| Physical qualification | Completed device matrix covering graphics, audio, input, thermals, lifecycle, and long sessions | No | Yes |
| Localization | Native-speaker approval recorded for every non-English resource set | No | Yes |
| Legal and Play policy | Copyleft offer, trademark review, privacy disclosure, paid-listing review, and Data safety answers | No | Yes |
| Signing | Protected GitHub environment approval and ephemeral production keystore secrets | No | Yes |

The release workflow intentionally prepares and retains a signed App Bundle but does not publish it.
It fails closed when the verified Fedora payload, digest, or separately packaged PRoot executable is
absent. Google Play upload remains a deliberate human-controlled operation outside this milestone.
