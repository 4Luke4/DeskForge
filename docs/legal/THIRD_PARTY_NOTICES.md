# Third-Party Notices

DeskForge does not relicense third-party components. The release workflow must generate a complete
SBOM and license inventory for the exact artifacts placed in an Android App Bundle.

## PRoot

- Project: <https://github.com/proot-me/proot>
- Initial version: 5.4.0
- License: GPL-2.0-or-later
- Integration: separately executed binary; never linked into a DeskForge proprietary library
- Source and DeskForge patches: must accompany every binary distribution through a durable source offer

## talloc

- Project: <https://talloc.samba.org/>
- Version: 2.4.3
- License: LGPL-3.0-or-later
- Integration: statically linked only into the separately executed GPL PRoot binary
- Source: official Samba release archive retained with PRoot's corresponding-source bundle

## Fedora Linux and XFCE

- Fedora Project: <https://fedoraproject.org/>
- Initial qualified image: Fedora XFCE 44 AArch64
- Source: official release image verified against Fedora's OpenPGP-signed checksum manifest
- Licensing: Fedora is a collection of independently licensed packages; the generated SBOM and
  corresponding-source record are authoritative for a release

Fedora and XFCE names remain the property of their respective owners. DeskForge is not affiliated
with or endorsed by the Fedora Project or XFCE project.

## TigerVNC

- Project: <https://github.com/TigerVNC/tigervnc>
- Fedora package: 1.16.2-4.fc44
- License: GPL-2.0-or-later with separately identified X.Org and other component terms
- Integration: separately executed `Xvnc` inside the Fedora guest; no TigerVNC code is linked into DeskForge
- Source: exact Fedora source RPM retained with every prepared payload and release candidate

## PipeWire and WirePlumber

- Projects: <https://pipewire.org/> and <https://pipewire.pages.freedesktop.org/wireplumber/>
- Integration: separately executed Fedora guest services; no PipeWire or WirePlumber code is linked into DeskForge
- Transport: built-in PipeWire Pulse pipe modules over app-private PCM FIFOs
- Source and licensing: exact package identities are retained with the payload; the verified Fedora image and release SBOM remain authoritative

## virglrenderer

- Project: <https://gitlab.freedesktop.org/virgl/virglrenderer>
- Version: 1.3.0
- License: MIT
- Integration: statically linked into the isolated `libdeskforge_graphics.so` Android runtime; its
  Venus render-server entry point is packaged as the separately executed
  `libdeskforge_venus_server.so` companion artifact
- Source and patches: exact upstream archive and DeskForge transport patches are retained with release evidence

## libepoxy

- Project: <https://github.com/anholt/libepoxy>
- Version: 1.5.10
- License: MIT
- Integration: statically linked into the isolated graphics runtime for EGL/GLES dispatch
- Source: exact upstream archive is retained with the graphics corresponding-source bundle

## Mesa demos / glx-utils

- Fedora package: `glx-utils-9.0.0-11.fc44.aarch64`
- License: MIT
- Integration: separately executed `glxinfo` inside Fedora before the desktop starts
- Source: exact Fedora `mesa-demos` source RPM is retained with every prepared payload

## X.Org Server and xorgproto

- Projects: <https://www.x.org/> and <https://gitlab.freedesktop.org/xorg/proto/xorgproto>
- Pinned design inputs: X.Org Server 21.1.24 and xorgproto 2025.1
- License: upstream MIT-style X.Org terms; exact X.Org Server text is retained with the source bundle
- Current integration: source and license evidence only; no Android display-server binary ships yet
- Planned integration: separately isolated Android display service with an original DeskForge
  KDrive/DDX; no Termux:X11 code is copied or linked
