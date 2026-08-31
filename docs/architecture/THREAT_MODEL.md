# Threat Model

## Protected assets

- Android application-private data and user-approved shared documents
- Microphone consent and captured audio
- Fedora and PRoot supply-chain integrity
- Session isolation, diagnostics, and signing credentials

## Trust boundaries

Linux guest code is untrusted and shares the DeskForge Android UID. PRoot path translation is not a
sandbox. The application therefore exposes no credentials to the guest, binds only explicitly
approved documents, uses private Unix sockets, and exports no Android service or provider.

## Principal threats and controls

- **Archive traversal:** normalized relative paths, checksum-validated headers, deferred and
  root-confined links, no writes through symlink ancestors, rejection of device nodes, and
  transactional activation.
- **Supply-chain replacement:** immutable source and executable SHA-256 manifests, reproducible
  PRoot builds, packaged-byte comparison, Fedora OpenPGP verification, pinned Actions, dependency
  review, SBOM generation, corresponding source, and provenance.
- **Runtime replacement or loader persistence:** the installed PRoot ELF and loader are independently
  size- and digest-checked before inspection and every launch. Both execute only from Android's
  read-only native-library directory; app-private scratch storage is cleaned on launch failure, stop,
  and stale-session recovery without following links.
- **Guest persistence after stop:** a dedicated process group, `--kill-on-exit`, synchronous reap,
  and stale-session recovery.
- **Unauthorized audio capture:** microphone disabled by default, Android runtime permission,
  per-session environment exposure, and a visible enabled state.
- **GPU incompatibility:** preliminary Vulkan-loader probing with an explicit software fallback.
  Device/driver self-tests remain a production release gate.
- **Sensitive diagnostics:** structured messages without user document contents, environment
  secrets, or signing data.

## Release validation

The production threat model must be revisited after the display/audio hosts and physical-device
matrix are complete. Google Play executable-code treatment and every copyleft distribution
obligation require explicit review before publication.
