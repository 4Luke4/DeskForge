# Physical ARM64 Tablet Qualification

GitHub-hosted ARM64 emulator QA is a release-readiness requirement. Because provisioning is
resource-intensive, maintainers dispatch it manually and its jobs are restricted to the protected
`main` branch. Pull requests rely on the standard CI and security checks instead. Emulator QA does
not substitute for production qualification on physical tablets. Record model identifiers and
evidence links; do not store device serial numbers or user data in the repository.

| Area | Required scenarios | Acceptance evidence |
| --- | --- | --- |
| Installation | Fresh install, interrupted download, insufficient space, retry, application update | Screen recording and sanitized diagnostics |
| Graphics | Native hardware-buffer and EGL-upload paths, explicit RFB recovery, Venus/Zink and VirGL `glxinfo`, llvmpipe, strict overrides, rejected peer, service death, resize, rotation, suspend/resume, every exposed 60/90/120 Hz seamless alternative | Host and guest renderer identities, target and active refresh, submitted fps, missed budgets, p95/max frame time, fallback reason, frame timeline, Perfetto trace, screenshots, CPU/memory/thermal summary |
| Input | Touch, stylus where supported, hardware/software keyboard, composition, shortcuts, mouse buttons, wheel | Completed interaction checklist |
| Clipboard | Manual transfer in both directions, denial, rich-content rejection, size limit, rotation | Sanitized state log without clipboard contents |
| Audio | Built-in, USB, and Bluetooth playback; focus loss/recovery; microphone denial, one-time grant, grant, background continuation, notification stop, and revocation | Sanitized route and bounded underrun/overflow log, consent recording, and proof of silence after disable |
| Lifecycle | Background/foreground, screen lock, process recreation, graceful and forced stop | PID/process-group and UI-state evidence |
| Reliability | Two-hour interactive session, thermal load, low-memory recovery | Crash-free logcat, thermal and memory summary |
| Accessibility | TalkBack, keyboard-only navigation, large font, display scaling | Annotated screenshots and reviewer sign-off |

At minimum, qualification must cover three API 34–37 ARM64 driver stacks: Qualcomm Adreno, ARM
Mali/Immortalis, and one non-Adreno/non-Mali implementation. For every exposed 60/90/120 Hz mode,
run the controlled desktop-motion workload for a 10-minute warm-up followed by a retained 30-minute
measurement. Submitted frames must reach at least 95% of the active refresh rate, p95 frame time must
not exceed 1.5 display periods, and no stall may exceed 100 ms. Repeat lifecycle transitions during
a separate two-hour reliability session. Any workaround must be capability-driven and documented;
model-name allowlists, unreported upload fallbacks, and silent RFB downgrades are not acceptable.
