# Display runtime inputs

`runtime.json` is the source and boundary manifest for the DeskForge 0.9 direct-display work tracked
in issue #12. It pins the upstream X.Org inputs selected for the Android KDrive/DDX port and records
the protocol limits that later implementation changes must preserve.

This directory does not yet declare a shippable display runtime. `plannedBinary` is intentionally
descriptive until CI can build the original DeskForge DDX reproducibly, verify the resulting ELF,
and compare its packaged bytes. Xvnc/RFB remains the only active desktop transport meanwhile.

Every additional build input and every DeskForge patch must be added to this manifest before it is
downloaded by automation. Do not import Termux:X11 source: its GPL-3.0 license is incompatible with
linking that code into DeskForge's proprietary Android libraries. Independently written DeskForge
code may use the documented X.Org and Android interfaces described in the architecture contract.
