# Security Policy

## Supported versions

DeskForge has not reached a production release. Security fixes are currently provided only for the
latest `0.2.x` development line.

| Version | Supported |
| --- | --- |
| 0.2.x | Yes |
| 0.1.x | No |
| Earlier or unreleased snapshots | No |

## Reporting a vulnerability

Do not disclose suspected vulnerabilities in a public issue, discussion, or pull request. Submit a
private report through [GitHub Security Advisories](https://github.com/4Luke4/DeskForge/security/advisories/new).

Include the affected version, Android version and device class, reproducible steps, impact, and any
proof of concept that is safe to share. Do not include personal data or access systems you do not
own or have explicit permission to test.

The maintainer will acknowledge a complete report within five business days and provide status
updates at least every ten business days while remediation is active. Disclosure timing is agreed
with the reporter after a fix and update path are available.

## Security boundaries

PRoot fake-root is a compatibility mechanism, not an Android security boundary. Guest processes
run with the DeskForge application UID and can reach only application-private files and documents
the user deliberately shares. A vulnerability that crosses those boundaries, bypasses explicit
microphone consent, executes an unverified payload, or escapes archive validation is in scope.
