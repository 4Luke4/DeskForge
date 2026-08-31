# GitHub Automation Instructions

These instructions extend the repository root instructions for files under `.github/`.

- Treat workflow files as production automation. Keep permissions least-privilege at workflow and job
  scope, add concurrency where duplicate runs are unsafe or wasteful, and use explicit timeouts for
  expensive jobs.
- Pin every third-party action to a full commit SHA and retain an adjacent release-version comment.
  Confirm upgrades from the action's official repository.
- Never execute untrusted pull-request code with write tokens or secrets. In particular, do not add a
  pull-request head checkout to a `pull_request_target` workflow.
- Source Android toolchain values through the local export action backed by
  `config/android/toolchain.properties`; do not duplicate versions in workflow YAML.
- Keep production signing in the protected `production` GitHub Environment. Secrets must be
  materialized only for the signing step, written to runner-temporary storage where possible, and
  excluded from artifacts and logs.
- Identify official Dependabot contributions from authenticated pull-request actor metadata, not
  commit author names or email addresses, which are spoofable.
- Use repository scripts for non-trivial validation logic instead of duplicating shell fragments
  across workflows. Shell scripts must enable strict error handling and quote expansions.
- All workflow validation, dependency resolution, builds, tests, CodeQL analysis, and emulator work
  must run on GitHub-hosted workflows, never as local verification.
- Keep ARM64 emulator QA manually dispatched and guard its resource-intensive jobs to the protected
  `main` branch. Pull requests use the standard CI and security workflows as their merge evidence.
