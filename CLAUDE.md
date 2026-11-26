# Home App

Ad-free Android app (Material Design 3) that replaces Tuya's Smart Life app for controlling
an IR blaster (lamp + projector) from a Google Pixel 9.

The design lives in `docs/superpowers/specs/`, implementation plans in `docs/superpowers/plans/`.
Read the latest spec before touching code.

## Language
- Talk to the owner in French.
- Code, comments, commit messages, branch names, issues and PRs are in English.

## Workflow (superpowers)
1. New feature or behavior change → `superpowers:brainstorming` → design doc → owner approval.
2. `superpowers:writing-plans` → plan in `docs/superpowers/plans/`.
3. Implement with `superpowers:subagent-driven-development` (independent tasks in parallel,
   in separate git worktrees) and `superpowers:test-driven-development`.
4. Bugs → `superpowers:systematic-debugging` before any fix.
5. Before claiming done → `superpowers:verification-before-completion` (build + tests green).
6. `superpowers:requesting-code-review` before opening/merging a PR.

## Subagent model routing
- `haiku`: searching, reading logs, gathering info, mechanical edits.
- `sonnet`: bounded feature tasks, tests, reviews.
- `opus`: architecture, IR/network protocol, concurrency, data migrations.

## Git conventions
- `main` is protected by convention: never commit directly to it. Every change goes through a PR.
- One GitHub issue per feature/bug; the PR closes it (`Closes #N`).
- Branch names: `<type>/<issue-number>-<short-kebab-desc>`, e.g. `feat/12-device-switches`.
- Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `ci`, `build`, `perf`.
- Commits follow Conventional Commits: `feat(remote): add projector d-pad`.
- Small PRs, one concern each. Squash-merge into `main`.
- PRs need: summary, linked issue, how it was tested.
- The repo is PUBLIC: never commit secrets (Tuya local keys, device IDs, API keys,
  `local.properties`, keystores). Use git-ignored config.

## Build & test
_To be filled once the project is scaffolded (Gradle commands, emulator/device steps)._
