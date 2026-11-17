---
name: tgx-upstream-sync
description: Integrate upstream branches, commits, or PRs into TGX-Next/Telegram-X, preserving fork adaptations, validating changes, and committing directly on local main, without opening a PR or pushing. Use for upstream merge, pull, synchronization, or cherry-pick requests in this repository.
---

# Integrate upstream into TGX-Next

Prepare a reviewable integration of the requested source. By default, merge entire branches
and cherry-pick individual commits or PRs. Integrate directly into local `main`, without a
separate integration branch or PR. Complete local commits after validation. Do not push
automatically. Explicit user instructions in the conversation may override these defaults;
loading this skill does not authorize publication.

## Identify the request and base

- Read `AGENTS.md`, `docs/ANDROID_13_ARM64.md`, `docs/CAMERAX_MIGRATION.md`,
  `docs/CODE_STYLE.md`, and, when applicable, `docs/PULL_REQUEST_TEMPLATE.md`.
  Migration documentation includes historical checks:
  consult the current checkout and `AGENTS.md` to determine which checks still exist.
- Inspect remotes, branch, worktrees, index, local changes, ongoing Git operations, and
  submodule state. Record the initial base SHA.
- Expected remotes are `origin` at `TGX-Next/Telegram-X` and `upstream` at
  `TGX-Android/Telegram-X`. Verify actual URLs; do not replace them automatically.
- Use the destination specified by the user. Otherwise, use local `main` and state that
  choice. Compare it with an updated `origin/main`; do not discard local commits or
  silently integrate additional changes from `origin`.
- Work directly on local `main`, or on the destination explicitly requested by the user.
  If that branch is checked out in another worktree, use that worktree rather than forcing
  a second checkout. Preserve existing work without automatic stashing, cleaning, or
  destructive resets. Do not interfere with an ongoing Git operation. If unrelated local
  changes prevent safe integration, ask for the necessary decision rather than silently
  switching to a separate branch or PR workflow.
- Resolve branches, SHAs, and URLs to concrete objects. Explicitly fetch required references
  rather than relying on `git pull` configuration. Deepen shallow history only as needed
  for analysis.
- For a PR number without a repository, use the verified upstream. Inspect metadata, state,
  base, head, commits, and diff through the GitHub connector when sufficient, or GitHub
  CLI/API. Pin the reviewed SHA; if the head changes, reassess before integrating.

## Define the integration scope

- For full synchronization, enumerate the commits the source adds to the base. Respect any
  build or SHA limit specified in the request.
- For selected commits, check ancestry, equivalent patches already applied, ordering, and
  dependencies. For PRs, obtain the actual commit list and diff against the PR base; do not
  treat every ancestor of the head as part of the PR.
- A merged PR may have been squashed or rebased: identify the effective representation of
  the change before selecting commits. Do not reapply equivalent changes. If only part is
  present, identify and explain the remainder.
- Inspect the entire diff, including changes without textual conflicts: resources, native
  code, build logic, dependencies, and gitlinks can also break fork adaptations.
- Briefly explain the source, base, strategy, and expected adaptations. Proceed with clear
  technical choices; ask when behavior is ambiguous or dependencies expand the request.
  Continue analysis that does not depend on the answer.

## Preserve the fork contract

Preserve platform adaptations and local product changes, including features already ported
from other PRs. Inspect the history of affected areas to understand their intent.

- The only flavor is `latest`; minimum Android is API 33, and the only Android ABI is
  `arm64-v8a`. Preserve version offsets, Gradle/CMake/Rust restrictions, and required pins.
- Shared code and resources belong in `main`. Adapt additions in obsolete source sets to
  current paths; do not reintroduce obsolete flavors, ABIs, or dependencies. Preserve guards
  for APIs introduced after API 33.
- Preserve the removal of multidex, Dalvik workarounds, ReLinker, the legacy transcoder,
  and old storage permissions. Host architecture does not determine the Android target.
- Preserve the CameraX-only internal camera contract in `docs/CAMERAX_MIGRATION.md`,
  including QR and round messages, preference migration, and the system-camera alternative.
  Port upstream Camera1 changes to the CameraX managers instead of restoring the removed
  backend. Preserve round rendering, audio, camera switching, and progressive output.
- Resolve conflicts according to the intent of both changes. Do not apply `ours`, `theirs`,
  or strategies that indiscriminately discard one side. Record adaptations and deliberate
  omissions with their rationale.
- Inspect `.gitmodules` and gitlinks. In the documented state, `tdlib`, `vkryl/core`,
  `vkryl/android`, and `vkryl/leveldb` use TGX-Next forks; verify current state and preserve
  their URLs and adaptations when integrating updates.
- Gitlinks must reference existing, accessible commits compatible with the fork. Update only
  necessary submodules, including LFS when applicable; do not use
  `submodule update --remote` indiscriminately.
- If a required submodule needs its own integration, preserve its fork adaptations, validate
  it, and create the necessary local commit before updating the parent gitlink. Record
  which submodule commits remain local. Do not push them automatically; explain that they
  must be published before the parent can be reproduced from a fresh clone. Never invent
  SHAs or claim an unpublished pin is remotely available.

## Integrate and commit locally

The default destination is local `main`. The integration request authorizes completing local
commits after validation; do not ask again merely to commit. It does not authorize pushing,
opening a PR, or publishing submodule commits.

- Entire branch: use `git merge --no-commit --no-ff <verified-sha>` on the clean destination
  branch, resolve conflicts, validate, then create the merge commit. `--no-ff` prevents
  advancing the branch before validation. If already integrated, report without forcing changes.
- Selected commits or PRs: apply changes with `git cherry-pick --no-commit <sha>` in
  dependency order. Preserve the upstream commit boundaries and authorship where practical.
  Validate each independently buildable change before recording its local commit. If a series
  requires later commits to build, prepare and validate the complete logical unit before
  committing it; explain any necessary grouping and preserve all source SHAs and attribution.
- Do not apply a merge commit without inspecting its parents and justifying the mainline
  (`-m`). Prefer the actual PR commits when they represent the scope correctly; do not
  include unrelated changes through merge ancestry.
- After a conflict during a cherry-pick without committing, resolve and stage only affected
  paths. Do not use `cherry-pick --continue` before validation, because it can create a
  commit. Verify there are no unmerged entries and, if sequencer state exists, use
  `cherry-pick --quit` to clear it while preserving the index and files. Track remaining
  SHAs explicitly; quitting the sequencer does not apply them.
- For merges, retain `MERGE_HEAD` until the validated merge commit is created. Do not remove
  metadata manually or accidentally record the merge as an ordinary single-parent commit.
- Before omitting an empty cherry-pick, determine whether the change already exists or was
  erased by conflict resolution. Record omitted commits and the rationale.
- Preserve imported provenance and authorship; rules for new contributions do not justify
  rewriting upstream authorship. When committing a prepared single cherry-pick, reuse its
  author and message, and include `(cherry picked from commit <full-source-sha>)`. If the
  user explicitly chooses a workflow using `cherry-pick` with immediate commits, use `-x`.
  Verify source references after conflict resolution. Attribute all sources in grouped commits.
- For original adaptations, follow current `@MadeByLLM`, exact model, and `Context-Id`
  trailer rules without inventing model identification. Use imperative titles within 65
  characters for new integration commits, and explain material adaptations and validation.
- If validation fails or a behavioral decision remains unresolved, leave the affected work
  uncommitted, report the incomplete state, and do not push or silently undo user changes.
- If the user explicitly requests no commits for a particular task, retain the prepared
  changes without committing; preserve pending merge state and report it accurately.

## Validate and deliver

- Review the entire diff against the initial SHA, including additions, removals, index, and
  submodules. Run `git diff --check` and `git diff --cached --check`; check for unmerged
  entries and conflict markers in affected files.
- With JDK 25 and the current README prerequisites, run:

  ```bash
  ./gradlew :app:assembleLatestDebug
  ./gradlew :app:lintLatestDebug
  ```

- Fix integration regressions within scope. Distinguish new failures, preexisting problems,
  and environment blockers. When necessary, compare against the base in an isolated
  checkout without changing the destination branch to measure the base.
- Test infrastructure is external and enabled when `tests/.git` exists. Do not create tests
  in this checkout by default. Choose additional checks according to the change. If an
  Android 13+ arm64 device is available, verify affected flows; obtain visual evidence for
  UI changes when possible. Report what was not verified.
- Before committing, verify that only intended changes are staged. After committing, inspect
  the history and cumulative diff from the initial SHA; confirm the destination is `main`
  unless explicitly overridden and that no operation remains unfinished. Do not push or
  open a PR. When no-commit mode was explicitly requested, confirm `HEAD` is unchanged.
- Report in Portuguese: branch and worktree path, base and source SHAs/links, integrated or
  omitted changes, conflict resolutions, submodules, checks, and limitations. State pending
  merges, staged changes, and blockers. Include created local commit SHAs and explicitly
  state that no push occurred. If checks or decisions are missing, describe the work as
  partial, not validated.

## Reference documentation

Consult as needed without copying tutorials into the skill:

- [Git merge](https://git-scm.com/docs/git-merge)
- [Git cherry-pick](https://git-scm.com/docs/git-cherry-pick)
- [Checking out PRs locally on GitHub](https://docs.github.com/en/pull-requests/how-tos/review-pull-requests/checking-out-pull-requests-locally)
