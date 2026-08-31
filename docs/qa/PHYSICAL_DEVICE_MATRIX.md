# Physical ARM64 Tablet Qualification

GitHub-hosted ARM64 emulator QA is a release-readiness requirement. Because provisioning is
resource-intensive, maintainers dispatch it manually and its jobs are restricted to the protected
`main` branch. Pull requests rely on the standard CI and security checks instead. Emulator QA does
not substitute for production qualification on physical tablets. Record model identifiers and
evidence links; do not store device serial numbers or user data in the repository.

| Area | Required scenarios | Acceptance evidence |
| --- | --- | --- |
| Installation | Fresh install, interrupted download, insufficient space, retry, application update | Screen recording and sanitized diagnostics |
| Graphics | Accelerated probe, software fallback, resize, rotation, suspend/resume | Renderer identity, screenshots, 30-minute trace |
| Input | Touch, stylus where supported, hardware keyboard, shortcuts, mouse buttons, wheel | Completed interaction checklist |
| Audio | Playback route changes; microphone denial, one-time grant, grant, and revocation | Sanitized audio route log and consent recording |
| Lifecycle | Background/foreground, screen lock, process recreation, graceful and forced stop | PID/process-group and UI-state evidence |
| Reliability | Two-hour interactive session, thermal load, low-memory recovery | Crash-free logcat, thermal and memory summary |
| Accessibility | TalkBack, keyboard-only navigation, large font, display scaling | Annotated screenshots and reviewer sign-off |

At minimum, qualification must cover two API 34–37 ARM64 tablets from different system-on-chip
families. Any device-specific workaround must be capability-driven and documented; model-name
allowlists or silent renderer downgrades are not acceptable.
