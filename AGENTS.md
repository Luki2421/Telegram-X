# Repository Guidelines

Keep these instructions focused on durable repository rules. Use [README.md](README.md)
for full setup instructions, [code style](docs/CODE_STYLE.md) for detailed conventions,
and the [PR checklist](docs/PULL_REQUEST_TEMPLATE.md) for contribution requirements.

## Project Structure & Module Organization

Telegram X uses Gradle, Java/Kotlin, and C/C++. Application code lives in
`app/src/main/java` and `app/src/main/kotlin`; resources and assets are in `app/src/main/res`
and `app/src/main/assets`. Native code is in `app/jni`.
The only supported flavor is `latest` (Android 13+, arm64); shared application
code and resources live in the `main` source set. Supporting modules live in `tdlib/`,
`tgcalls/`, `vkryl/`, and `extension/`. `buildSrc/` contains Gradle logic.
Tests and benchmarks are maintained outside this checkout,
as in upstream Telegram X.

## Fork Compatibility

Preserve the Android 13+ and arm64-only contract, the `latest` flavor, and shared source sets
in `main`. Keep guards for APIs introduced after API 33. Do not reintroduce removed legacy
flavors, ABIs, dependencies, or storage permissions. Read the
[migration notes](docs/ANDROID_13_ARM64.md) before changing platform support, native build
configuration, or submodule pins; preserve adaptations in the TGX-Next submodule forks.

## Build, Test, and Development Commands

Run commands from the repository root using JDK 25, Git LFS, initialized submodules, and the
Gradle wrapper. Use the SDK/NDK/Rust versions pinned by the repository.

- `git submodule update --init --recursive`: initialize submodules after cloning if needed.
- `scripts/setup-rust.sh`: install the pinned Rust toolchain and Android target with rustup.
  Set `rust.dir` in `local.properties` to the value printed by the script; rerun it when
  `version.rust` in `version.properties` changes.
- `scripts/setup.sh`: configure Android SDK, NDK, and local properties.
- `./gradlew :app:assembleLatestDebug`: build the latest-flavor debug APK.
- `./gradlew :app:installLatestDebug -Pabi=arm64`: install on a connected ARM64 device.
- `./gradlew :app:lintLatestDebug`: run Android lint checks.

Avoid `clean` during normal development so Gradle and native builds can reuse their outputs.

## Change Boundaries

Keep changes scoped and preserve unrelated work already present in the checkout.
Check for additional `AGENTS.md` instructions in the directories you modify.

Submodules are separate repositories pinned by this checkout. Change their contents or pins
only when required by the task; preserve unrelated URLs, branches, and revisions.
Edit generator sources or inputs instead of generated files under build directories.

## Coding Style & Naming Conventions

Follow `.editorconfig` and [code style](docs/CODE_STYLE.md): two-space indentation,
100-character lines, descriptive names, and braces around control-flow bodies. Use PascalCase
classes and camelCase methods; Kotlin has no trailing commas. Java method declarations use
a space before `(`. Use Android Studio formatting; introduce no warnings.
Add Kotlin only in new modules outside `org.thunderdog`, tests, or build code; keep it
Java-interoperable or private. Copy neighboring license headers for new app files.
Reuse existing helpers and avoid allocations during drawing, animations, or scrolling.

## Testing Guidelines

The checkout does not include test sources, benchmarks, or `checkMinSdk`. Gradle enables
external test infrastructure only when `tests/.git` exists, following upstream. Do not assume
test tasks or `checkMinSdk` are available based on historical validation notes.

For code, resources, dependencies, or Android build configuration changes, run
`./gradlew :app:assembleLatestDebug` and `./gradlew :app:lintLatestDebug`.
For documentation-only or skill-instruction changes, verify paths, links, commands, and
formatting against the checkout, and run any applicable skill validator; an APK build and
Android lint are not required. For script changes, run the relevant syntax check and exercise
the affected behavior when feasible; run build/lint as well if the script affects the Android
build.

Reproduce bugs before fixing them when feasible and verify affected behavior manually on
Android 13+ and arm64 when a device is available. Report checks and results, failures,
environment blockers, checks not run, and coverage gaps. Distinguish existing findings from
regressions introduced by the change.

## Upstream Integration

For upstream merge, pull, synchronization, or cherry-pick requests, follow
[tgx-upstream-sync](.agents/skills/tgx-upstream-sync/SKILL.md). The default workflow integrates
directly into local `main`, validates, and completes local commits. Do not create a separate
integration branch, open a PR, or push unless the user requests it. Merge entire upstream
branches; cherry-pick selected commits or PRs. Preserve unrelated local work and fork changes.
This workflow does not authorize automatic commits for unrelated development tasks.

## Commit & Pull Request Guidelines

Use imperative commit titles within 65 characters, matching history, and explain why.
When opening a PR, follow the [PR checklist](docs/PULL_REQUEST_TEMPLATE.md): one scoped change,
latest `main` base, linked issue and issue number in bug-fix titles, affected flavors,
before/after checks, and coverage gaps.
Include screenshots or recordings for UI changes and measurements for optimizations.
Follow its `@MadeByLLM` annotation and `Context-Id` trailer requirements where applicable.

## Security & Configuration

Never commit `local.properties`, signing keys, API credentials, or account snapshots.
Keep snapshots outside the checkout.
