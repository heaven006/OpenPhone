# Changelog

All notable OpenPhone-owned changes should be documented here.

OpenPhone uses semantic versioning for public project releases. Early releases
below `1.0.0` are developer previews and may change architecture, APIs, build
flow, and device support.

## [Unreleased]

### Added

- User-facing OpenPhone Assistant control surface.
- Active-agent status indication and assistant-owned cursor overlay.
- Development voice capture and OpenAI transcription path.
- Assistant-side trajectory logging for agent runs, including screenshot file
  extraction from framework screenshot payloads.
- Assistant-side accessibility UI tree capture merged into model screen
  context requests.
- Initial eval task definitions for the CUA-informed phone agent loop.
- Planning cleanup for the CUA-informed agent work and public GitHub project
  setup.
- Initial GitHub CI and release documentation.
- Pixel 9a DTB preparation and generated `vendor_kernel_boot.img`
  verification for `openphone_tegu` and `openphone_tegu_smoke` target-files/OTA
  builds.
- OpenPhone Assistant package version bumped to `0.1.1-dev` / `37` so
  system-package reparsing picks up newly declared privileged components during
  development OTAs.
- OpenPhone Assistant package version bumped to `0.1.2-dev` / `38` for the
  cancellation, structured local-result, and accessibility re-enable OTA.
- OpenPhone Assistant package version bumped to `0.1.3-dev` / `39` with a
  packaged parse marker so development OTAs force PackageManager to reparse the
  system APK when deterministic timestamps and similar APK sizes would otherwise
  keep stale metadata.
- OpenPhone Assistant package version bumped to `0.1.4-dev` / `40` for the
  trajectory export build.
- OpenPhone Assistant package version bumped to `0.1.12-dev` / `48` for the
  model disclosure and trajectory metadata build.
- Settings/About phone OpenPhone version and support rows.
- Settings homepage OpenPhone dashboard with assistant, task-grant,
  audit-evidence, and support entry points.
- Settings-hosted OpenPhone task-grant explainer and audit status pages.
- Assistant model provider, model name, cloud/local mode, and privacy
  disclosure shown in the UI and written to trajectory start events.

### Changed

- The OpenPhone framework service SELinux label now lives in private platform
  policy, and the service is listed in the sepolicy fuzzer-binding map so full
  Pixel 9a OTA builds pass release hygiene gates.
- OpenPhone Assistant can export the latest trajectory as a zip file under
  `Downloads/OpenPhone`, giving physical evals a non-root evidence path.
- `docs/PLAN.md` is now the canonical active plan.
- `docs/ROADMAP.md` is now the short public roadmap.
- Stopping an active agent run now cancels the model adapter, interrupts the
  run thread, prevents stale generations from updating the UI, and treats
  disconnected OpenAI requests as cancellation rather than network failure.
- The local heuristic adapter now emits structured JSON results that the
  user-facing task surface can interpret cleanly.
- The assistant now attempts to re-enable its accessibility service on resume
  as well as at first launch, which helps after fresh OTAs or onboarding resets.

## [0.0.1] - Unreleased

Initial developer preview target.

Planned scope:

- Source-available OpenPhone repository.
- Pixel 9a `tegu` development target documentation.
- Privileged OpenPhone Assistant app.
- Hidden framework service for task, screen, action, policy, confirmation, and
  audit plumbing.
- Lightweight CI checks for repository hygiene.
- Release notes, changelog, and release process documentation.
