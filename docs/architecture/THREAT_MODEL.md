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
  review, SBOM generation, corresponding source, and provenance. Fedora images are mounted during
  asset preparation only after signature and exact-digest verification, as a read-only, no-execute
  lower layer; signed RPM changes are confined to an ephemeral writable overlay.
- **Runtime replacement or loader persistence:** the installed PRoot ELF and loader are independently
  size- and digest-checked before inspection and every launch. Both execute only from Android's
  read-only native-library directory; app-private scratch storage is cleaned on launch failure, stop,
  and stale-session recovery without following links.
- **Guest persistence after stop:** a dedicated process group, `--kill-on-exit`, synchronous reap,
  and stale-session recovery.
- **Unauthorized audio capture:** every session starts with capture disabled. The foreground service
  rechecks Android permission, requires a visible per-session action, exposes a persistent stop
  control, and stops and zeroizes capture on disable, revocation, teardown, or partial failure. The
  PRoot device allowlist removes direct guest paths to Android Binder and raw audio nodes instead of
  carrying the host's complete device tree into the guest.
- **Malformed or replaced audio transport:** the native supervisor creates owner-only FIFOs through
  a trusted directory descriptor, rejects links and non-FIFOs, retains validated descriptors, and
  bounds all PCM buffering. PipeWire exposes no audio TCP listener.
- **Malformed graphics transport:** the vtest parser runs in a non-exported isolated service and
  receives only a pre-bound Unix listener descriptor. `SO_PEERCRED` restricts peers to the DeskForge
  guest UID; client, command, address-space, descriptor, and core-dump limits bound compromise impact.
- **GPU incompatibility:** an EGL/GLES self-test rejects SwiftShader, llvmpipe, and softpipe as host
  acceleration. Venus additionally requires Vulkan 1.1 plus external-memory dma-buf, DRM-format-
  modifier, and foreign-queue extensions. Fedora qualifies Zink/Venus and then VirGL before XFCE;
  Auto selects llvmpipe on failure, while forced modes fail explicitly. Device/driver self-tests
  remain a production release gate because the advertised Venus extensions are necessary but not
  sufficient on every Android driver.
- **Malformed desktop transport:** Xvnc listens only on a private Unix socket; the RFB client caps
  allocations and text, validates rectangle arithmetic and negotiated security, and terminates the
  session on malformed or unsupported protocol input.
- **Malformed presentation buffers:** framebuffer geometry and damaged rectangles are validated
  before allocation or copy. Hardware-buffer usage is limited to CPU write and GPU sampling through
  public NDK APIs. The single producer/consumer buffer is not reused until GPU sampling completes;
  an EGL, fence, or Surface failure ends the session instead of entering an unreported fallback.
- **GPU presentation incompatibility:** allocation and EGL-image import are capability-tested rather
  than selected by GPU model. Failure selects the visible native EGL upload path. Compatibility RFB
  is a persisted, explicit user policy and is never an automatic fallback.
- **Clipboard exfiltration or injection:** extended RFB clipboard exchange advertises zero unsolicited
  text capacity. Android and guest text cross the boundary only after separate visible actions, accept
  one plain UTF-8 item up to 1 MiB, and reject rich or provider-backed content. DeskForge clears its
  transfer buffers after use; guest text is marked sensitive when written to Android's clipboard.
- **Sensitive diagnostics:** structured messages without user document contents, environment
  secrets, or signing data.

## Direct-display milestone boundary

The accepted 0.9 architecture introduces an isolated X.Org display service, DRI3 hardware-buffer
imports, Present fences, and a SurfaceControl child layer. Before that path can become active, its
implementation must preserve the authenticated private-listener model and add fail-closed format,
geometry, allocation, queue-depth, and fence-ownership validation from
`config/display/runtime.json`. Protected, YUV, multi-planar, oversized, or unsynchronized buffers
remain outside the trust boundary. Direct-path failure must end the session; RFB remains an explicit
next-session recovery choice rather than an automatic downgrade. The complete target and teardown
contract is in `docs/architecture/DIRECT_DISPLAY.md`.

## Release validation

The production threat model must be revisited after the physical-device matrix is complete. Google
Play microphone disclosure and executable-code treatment, plus every copyleft distribution
obligation require explicit review before publication.

PRoot and the device allowlist are not a UID or kernel sandbox. Before production, adversarial guest
testing must confirm that Android IPC, inherited descriptors, procfs paths, and same-UID behavior do
not provide an alternate microphone path outside the consented bridge; otherwise microphone capture
must remain disabled or move behind a stronger process boundary.
