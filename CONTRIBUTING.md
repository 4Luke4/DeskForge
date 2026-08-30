# Contributing

DeskForge accepts focused, reviewable changes through pull requests. Discuss major product,
runtime, licensing, or architecture changes in an issue before implementation.

## Change requirements

- Use Conventional Commit subjects and keep commits independently understandable.
- Treat `VERSION` as the single version source and update `CHANGELOG.md` for user-visible changes.
- Add or update automated coverage, but run builds and tests through the repository's GitHub Actions
  workflows rather than presenting local execution as verification.
- Preserve ARM64, API 34 minimum, 720dp tablet, accessibility, keyboard, mouse, and localization
  requirements.
- Pin downloaded inputs by digest and update `THIRD_PARTY_NOTICES.md` for dependency or license changes.
- Never commit signing keys, credentials, generated Fedora payloads, or unreviewed production claims.

All required checks must pass before merge. Physical-device, localization, legal, policy, and
production-signing gates are tracked separately in [release readiness](docs/release/READINESS.md).
