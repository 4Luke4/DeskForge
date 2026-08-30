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
- **Supply-chain replacement:** immutable SHA-256 manifests, Fedora OpenPGP verification, pinned
  Actions, dependency review, SBOM generation, and provenance.
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
