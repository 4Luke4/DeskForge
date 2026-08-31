# Android Application Instructions

These instructions extend the repository root instructions for the Android application module.

- Keep `DeskForgeEngine` as the stable Kotlin boundary to native runtime behavior. UI code must not
  directly manage PRoot processes, native descriptors, or installation paths.
- Model lifecycle and failure states explicitly. Represent a session as running only after the native
  supervisor returns a positive supervised PID, and preserve deterministic stop and recovery paths.
- Compose UI must remain adaptive for supported tablets and operable through touch, mouse, keyboard,
  and accessibility services. Provide meaningful semantics, focus order, and visible state feedback.
- Keep microphone access disabled by default and require both Android runtime permission and explicit
  per-session user consent. Playback support must not implicitly authorize capture.
- Preserve transactional Fedora installation: validate before extraction, reject unsafe archive
  entries, write into unique staging storage, and atomically activate only a complete installation.
- Never log user document contents, environment secrets, signing values, or other guest-sensitive
  data. Diagnostics should be structured, actionable, and safe to retain.
- Put reusable UI text in resources and update every supported locale together. Do not hardcode
  user-visible strings, dimensions that encode product policy, or toolchain values in Kotlin.
- Add JVM tests for pure Kotlin logic and instrumentation tests for Android, Compose, permission, or
  filesystem behavior. Execute them only through the applicable GitHub Actions workflow.
