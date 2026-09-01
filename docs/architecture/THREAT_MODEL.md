# Threat Model

## Protected assets

- Android application-private data and user-approved shared documents
- User-approved Android and Linux clipboard text
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
- **Unauthorized audio capture:** no microphone permission or guest capture bridge is present in this
  milestone; a future host must require Android permission and explicit per-session consent.
- **GPU incompatibility:** preliminary Vulkan-loader probing with an explicit software fallback.
  Device/driver self-tests remain a production release gate.
- **Malformed desktop transport:** Xvnc listens only on a private Unix socket; the RFB client caps
  allocations and text, validates rectangle arithmetic and negotiated security, and terminates the
  session on malformed or unsupported protocol input.
- **Clipboard exfiltration or injection:** extended RFB clipboard exchange advertises zero unsolicited
  text capacity. Android and guest text cross the boundary only after separate visible actions, accept
  one plain UTF-8 item up to 1 MiB, and reject rich or provider-backed content. DeskForge clears its
  transfer buffers after use; guest text is marked sensitive when written to Android's clipboard.
- **Sensitive diagnostics:** structured messages without user document contents, environment
  secrets, or signing data.

## Release validation

The production threat model must be revisited after accelerated display, audio, and the
physical-device matrix are complete. Google Play executable-code treatment and every copyleft distribution
obligation require explicit review before publication.
