# Repository Guidelines

TGX-Next is a Telegram X fork built with Gradle, Java/Kotlin, and C/C++.
Use [README.md](README.md) for setup, [code style](docs/CODE_STYLE.md) for conventions,
and the [PR checklist](docs/PULL_REQUEST_TEMPLATE.md) for contribution requirements.
Keep this file focused on durable repository rules; put task procedures in project skills.

## Working agreements

- Read additional `AGENTS.md` or `AGENTS.override.md` files along the path to files you edit.
  More specific instructions apply within their directory tree.
- Keep changes scoped and preserve unrelated work in the checkout.
- Submodules are separate repositories. Change their contents or pins only when required by
  the task; preserve unrelated URLs, branches, and revisions and TGX-Next fork adaptations.
- Edit generator sources or inputs rather than generated files under build directories.
- Never commit `local.properties`, signing keys, API credentials, or account snapshots.
  Keep snapshots outside the checkout.

## Platform contract

- Support Android 13+ (API 33+) and `arm64-v8a` only, with `latest` as the only flavor.
- Keep shared application code and resources in the `main` source set.
- Preserve version guards for APIs introduced after API 33.
- Do not reintroduce removed legacy flavors, ABIs, dependencies, or storage permissions.
- Read [migration notes](docs/ANDROID_13_ARM64.md) before changing platform support,
  native build configuration, or submodule pins.

## Camera contract

- CameraX is the only internal camera backend, including QR and round video messages.
  Preserve the system-camera alternative and migration of stored camera preferences.
- Read [camera migration notes](docs/CAMERAX_MIGRATION.md) before changing camera backends
  or integrating upstream camera changes. Do not reintroduce the removed Camera1 backend.

## Repository map

- `app/src/main/java`, `app/src/main/kotlin`: application code.
- `app/src/main/res`, `app/src/main/assets`: resources and assets.
- `app/jni`: native application code.
- `tdlib/`, `tgcalls/`, `vkryl/`, `extension/`: supporting modules.
- `buildSrc/`: Gradle build logic.

## Development setup

Follow the [build setup](README.md#build) for prerequisites and pinned toolchains.
Run commands from the repository root and use the Gradle wrapper.
Install on a connected ARM64 device with `./gradlew :app:installLatestDebug -Pabi=arm64`.

Avoid `clean` during normal development so Gradle and native builds can reuse outputs.

## Code style

- Follow [`.editorconfig`](.editorconfig) and [code style](docs/CODE_STYLE.md). Use Android
  Studio formatting, two-space indentation, 100-character lines, and descriptive names.
- Use PascalCase classes, camelCase methods, and braces around control-flow bodies.
  Java method declarations have a space before `(`; Kotlin has no trailing commas.
- Add Kotlin only in new modules outside `org.thunderdog`, tests, or build code;
  keep it Java-interoperable or private.
- Copy neighboring license headers for new app files. Introduce no warnings.
- Reuse existing helpers and avoid allocations during drawing, animations, or scrolling.

## Validation

- For code, resources, dependencies, or Android build configuration changes, run both
  `./gradlew :app:assembleLatestDebug` and `./gradlew :app:lintLatestDebug`.
- For documentation-only or skill-instruction changes, verify paths, links, commands, and
  formatting against the checkout, and run any applicable skill validator. APK build and
  Android lint are not required.
- For script changes, run the relevant syntax check and exercise affected behavior when
  feasible. Also run build and lint when the script affects the Android build.
- Reproduce bugs before fixing them when feasible. Verify affected behavior manually on
  Android 13+ and arm64 when a device is available.
- Report checks and results, failures, environment blockers, checks not run, and coverage
  gaps. Distinguish existing findings from regressions introduced by the change.

Tests and benchmarks are maintained outside the main repository. Gradle includes the external
test infrastructure only when `tests/.git` exists. Check which Gradle tasks are available in the
current checkout before running tests or benchmarks.

## Project skills

Task workflows live in [`.agents/skills/`](.agents/skills). Use each skill's description to
select the relevant workflow, read its `SKILL.md` before use, and load supporting files only
as needed.

## Upstream integration

For upstream merge, pull, synchronization, or cherry-pick requests, follow
[tgx-upstream-sync](.agents/skills/tgx-upstream-sync/SKILL.md).

- Integrate directly into local `main`, validate, and complete local commits.
- Merge entire upstream branches; cherry-pick selected commits or PRs.
- Preserve unrelated local work and fork changes.
- Do not create an integration branch, open a PR, or push unless the user requests it.
- This workflow does not authorize automatic commits for unrelated development tasks.

## Commit and pull request instructions

- Use imperative commit titles within 65 characters, matching history, and explain why.
- Follow the [PR checklist](docs/PULL_REQUEST_TEMPLATE.md): one scoped change, latest
  `main` base, linked issue and issue number in bug-fix titles, affected flavors,
  before/after checks, and coverage gaps.
- Include screenshots or recordings for UI changes and measurements for optimizations.
- Follow the checklist's `@MadeByLLM` annotation and `Context-Id` trailer requirements
  where applicable.
