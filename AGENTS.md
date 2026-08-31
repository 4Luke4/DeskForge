# DeskForge Repository Instructions

## Working standard

- Be professional, precise, and evidence-driven. Inspect the repository before proposing or making
  changes, and ask for clarification when product intent cannot be established from repository
  evidence.
- Never speculate about APIs, platform behavior, dependency versions, release state, or security
  properties. Use authoritative project files and upstream primary documentation.
- Preserve unrelated worktree changes. Do not rewrite, delete, or reformat files outside the
  requested scope.
- Add concise inline comments for non-obvious security boundaries, lifecycle behavior, compatibility
  decisions, and workflow constraints. Do not add comments that merely restate the code.
- Keep changes focused and reviewable. Avoid unrelated cleanup, premature abstractions, and copied
  implementations whose licensing or provenance is unclear.

## Verification policy

- Never run Gradle tasks, tests, builds, Android tooling, emulators, or packaging commands locally.
  Use the repository's GitHub Actions workflows for all executable verification.
- Local work is limited to read-only inspection and non-executing checks such as reviewing diffs,
  file modes, and repository status.
- Do not present a change as verified until the relevant GitHub Actions jobs pass. Record unavailable
  or intentionally skipped workflow coverage explicitly in the pull request.

## Sources of truth

- `VERSION` is the application version source. Keep it consistent with `CHANGELOG.md` and localized
  resources through the existing validation scripts.
- `config/android/toolchain.properties` owns Android SDK, build tools, NDK, CMake, ABI, Java, and
  tablet-width requirements. Do not duplicate these values in workflows or build scripts.
- `gradle/libs.versions.toml` owns declared Gradle library and plugin versions. Security overrides for
  vulnerable transitive build tooling belong in `config/build-tool-security.versions`.
- `config/proot/version.json` and `config/distros/fedora-xfce-44.json` own upstream artifact URLs,
  versions, sizes, and digests. Never introduce an unpinned downloaded input.
- `SECURITY.md`, `docs/architecture/THREAT_MODEL.md`, and `docs/release/READINESS.md` define the
  security and release gates. Update them when a change alters a documented boundary.

## Product and architecture invariants

- DeskForge targets ARM64 tablets only, with API 34 minimum, the configured API 37 toolchain, and a
  720dp minimum tablet width.
- Kotlin and Jetpack Compose own Android UI, permissions, application state, lifecycle integration,
  and accessibility. C++ owns native capability probing and supervised process lifecycle.
- PRoot remains a separately executed GPL-2.0-or-later binary. Never link PRoot code into proprietary
  DeskForge libraries or describe fake root as an Android sandbox.
- Fedora and every other upstream payload must retain its upstream identity, license obligations,
  verified origin, and checksum-pinned packaging path.
- Guest code is untrusted and shares the application UID. Do not expose credentials, signing data,
  unrestricted Android components, or user documents that were not explicitly approved.
- Archive installation, runtime activation, microphone access, and process shutdown must continue to
  fail closed and preserve their transactional or explicit-consent behavior.

## Change and review conventions

- Use Conventional Commits with the repository's allowed types and a subject no longer than 100
  characters. Keep commits independently understandable.
- Update automated coverage when behavior changes. Use the closest existing unit or instrumentation
  test style and execute it only through GitHub Actions.
- Preserve every supported locale when changing user-visible strings. UI changes must account for
  accessibility, keyboard, mouse, touch, and adaptive tablet layout behavior.
- Keep GitHub Actions permissions minimal, pin third-party actions by full commit SHA, and never put
  credentials or signing material in the repository, logs, artifacts, or pull request text.
- Update third-party notices when shipped dependency or licensing obligations change. Build-only
  dependency remediation does not alter the application bundle's notice inventory.
