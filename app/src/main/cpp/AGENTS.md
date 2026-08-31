# Native Runtime Instructions

These instructions extend the root and `app/` instructions for native C++ code. Kotlin-specific
implementation guidance does not apply inside this directory.

- Use portable C++20 accepted by the pinned Android NDK and keep the existing warning-as-error policy.
  Avoid compiler-specific behavior unless repository evidence establishes that it is required.
- Keep JNI entry points small. Validate Java inputs before native use, translate failures into stable
  Kotlin-visible results, and release local/global references and native resources deterministically.
- Treat every guest argument, path, environment value, descriptor, and process identifier as
  untrusted. Reject invalid state explicitly rather than truncating or reinterpreting it.
- Launch PRoot only as the separately packaged executable. Preserve process-group ownership,
  `--kill-on-exit`, synchronous reap, stale-session recovery, and cleanup on every partial failure.
- Do not weaken application-private path confinement, checksum verification, user-approved document
  sharing, or microphone consent to make a runtime path succeed.
- Keep capability probing deterministic. Unsupported graphics paths must select the documented
  software fallback with a safe diagnostic instead of claiming acceleration.
- Add focused native or instrumentation coverage for changed lifecycle and JNI behavior, then run it
  only through GitHub Actions.
