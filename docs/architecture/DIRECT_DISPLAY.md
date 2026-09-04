# DRI3/Present Direct-Display Architecture

Status: accepted target for DeskForge 0.9.0; implementation is tracked in issue #12. The
debug-only isolated service and public-NDK buffer probe exist, but no X server or guest-facing
direct-display transport is active yet.

## Outcome and terminology

DeskForge will replace the default Xvnc/RFB pixel path with an Android-hosted X.Org server using an
original KDrive/DDX integration. Eligible X11 pixmaps will cross DRI3/Present as Android hardware
buffers and be submitted on a SurfaceControl child layer. This removes RFB encoding, decoding, and
CPU pixel transport from the default path.

The implementation may report a buffer as **direct-scanout eligible** after format, usage, fence,
and device qualification. It must not report guaranteed direct scanout: SurfaceFlinger and the
hardware composer retain the final decision to use an overlay or GPU composition for each frame.
Guest renderer and Android presentation path remain independent diagnostics.

## Process and descriptor boundaries

```text
Compose / session state
        |
        | bind explicit non-exported service; pass owned descriptors
        v
isolated DisplayServerService ---- SurfaceControl child layer
        |                                      ^
        | inherited mode-0600 filesystem       | AHardwareBuffer + fences
        | Unix listener (no TCP/abstract)       |
        v                                      |
PRoot guest / XFCE -------- X11 + DRI3/Present-+
```

The main process creates the runtime directory and listener without following links, validates the
result, and transfers only the minimum descriptors to the isolated service. The service never
receives user documents, signing material, credentials, microphone descriptors, or unrestricted
filesystem access. X11 accepts no TCP or abstract-namespace listener. Expected peers are checked
with `SO_PEERCRED`; an unexpected peer or inherited descriptor terminates startup.

X.Org Server 21.1.24 and xorgproto 2025.1 are immutable inputs in
`config/display/runtime.json`. The Android DDX is DeskForge-owned code. Termux:X11 is a useful
behavioral reference only; its GPL-3.0 source must not be copied or linked into proprietary
DeskForge libraries.

## Buffer contract

The DDX accepts only single-layer `R8G8B8A8_UNORM` and `R8G8B8X8_UNORM` hardware buffers using
public NDK APIs. It rejects protected content, YUV, multi-planar layouts, integer overflow, invalid
stride or geometry, duplicate ownership, and any import that exceeds the manifest limits. Private
native-handle fields and undocumented SurfaceFlinger interfaces are forbidden.

Before import, the service accounts for the full allocation and reserves capacity atomically. A
client is limited to 128 MiB per buffer, 64 imported pixmaps, 512 MiB aggregate imported memory,
and 3 queued Present operations. Reservations are released on every error and only after the final
buffer reference is dropped. The service must remain within its process address-space, descriptor,
and core-dump limits even when guest code intentionally withholds resources.

## Mandatory allocation proof

DRI3 transports operating-system descriptors, while Android's documented Unix-socket helpers send
and receive an opaque `AHardwareBuffer` between Android-native endpoints. The public NDK does not
document a dma-buf descriptor extractor or authorize parsing the underlying native handle. Before
the DDX or Fedora integration proceeds, an executable spike must therefore prove how an accelerated
Fedora Mesa client and the isolated Android X server refer to the same allocation without relying on
private handle layout.

An acceptable design may extend the existing virglrenderer transport so the guest retains an opaque
resource identity while Android endpoints exchange the corresponding hardware buffer through the
public NDK API. Any such extension must remain bounded, authenticated, renderer-independent at the
presentation boundary, and reproducibly built. Treating the first native-handle descriptor as a
dma-buf, importing undocumented gralloc metadata, or presenting a CPU copy as DRI3 direct display is
not acceptable. If the spike cannot establish this path, issue #12 must return to architecture
review before an Android DDX is implemented.

Primary API contracts: [X.Org DRI3](https://cgit.freedesktop.org/xorg/proto/xorgproto/tree/dri3proto.txt),
[X.Org Present](https://cgit.freedesktop.org/xorg/proto/presentproto/tree/presentproto.txt), and
[Android native hardware buffers](https://developer.android.com/ndk/reference/group/a-hardware-buffer).

## Synchronization and presentation

Every Present operation has exactly one acquire-fence ownership transfer. The consumer waits on or
passes that fence to the Android transaction; it never samples an unsignaled buffer. Android's
release callback returns the release fence to the owning pixmap before reuse. Fence descriptors are
closed exactly once on success, rejection, surface loss, disconnect, and service teardown.

The queue retains at most three pending presents. New work beyond the cap is rejected or applies
protocol back-pressure; it never grows an unbounded queue. Presentation timestamps and completion
events remain monotonic. Surface loss ends the direct session, drains callbacks, releases buffers,
and reports a stable failure reason.

## Input, resize, and clipboard

The Android surface maps touch, stylus, mouse buttons/wheel, physical keyboard events, and explicit
IME commits into XInput devices. RandR owns desktop size changes after validated Android bounds. A
resize is committed only after both server and Android layer state agree; old buffers stay valid
until their release fences complete.

Clipboard exchange remains a visible, manual action in each direction. Only one plain UTF-8 item of
at most 1 MiB crosses the boundary. Automatic selection monitoring, rich content, URIs, provider
objects, and clipboard contents in diagnostics remain prohibited.

## Policy and failure state machine

Presentation preferences become `DIRECT`, `RFB_EGL`, and `RFB`. Existing `NATIVE` preferences
migrate to `DIRECT` only when the direct path is qualified; persisted `RFB` remains unchanged.
Numeric presentation-path telemetry is append-only so existing values are never reinterpreted.

```text
Stopped -> Starting -> Negotiating -> Running
              |             |            |
              +-------------+------------+
                            v
                          Failed -> Stopping -> Stopped
```

Only a supervised guest PID, authenticated X11 peer, live Android surface, and completed display
negotiation may enter `Running`. Any malformed buffer, service death, fence failure, surface loss,
guest exit, or timeout enters `Failed` and performs deterministic cleanup. Direct-path failure never
silently selects RFB. The user may choose explicit RFB recovery for the next session.

## Rollout and evidence

1. Pin and retain upstream source; validate this contract in CI.
2. Build the isolated server and buffer transport behind a test-only gate.
3. Add DRI3, Present, XInput, and RandR behavior plus adversarial instrumentation.
4. Integrate Fedora workspace schema 6 while retaining TigerVNC recovery.
5. Run informational pacing comparisons on the x86_64 KVM lane.
6. Qualify Adreno, Mali/Immortalis, and a third ARM64 driver family at 60/90/120 Hz.
7. Make `DIRECT` the default and release 0.9.0 only after all documented gates pass.

The x86_64 emulator answers whether a DeskForge change regressed CPU cost, lifecycle, or frame
pacing under a stable accelerated VM. It does not model tablet GPU drivers, SurfaceFlinger overlay
decisions, thermals, or production ARM64 performance. Physical evidence remains authoritative.
