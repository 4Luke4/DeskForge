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
