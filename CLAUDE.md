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
- Small PRs, one concern each. Squash-merge into `main`. Claude may merge its own PRs once checks pass.
- No `Co-Authored-By` or "Generated with Claude Code" lines in commits or PRs.
- PRs need: summary, linked issue, how it was tested.
- The repo is PUBLIC: never commit secrets (Tuya local keys, device IDs, API keys,
  `local.properties`, keystores). Use git-ignored config.

## Build & test
Modules: `:tuya` (pure Kotlin/JVM library, no Android dependency, JUnit 5) and `:app` (Android,
Compose + Material 3 + Hilt, Robolectric unit tests). Versions live in `gradle/libs.versions.toml`.

Requirements: Android SDK with platform 37 (path in git-ignored `local.properties`: `sdk.dir=...`),
JDK 17 toolchain (`jvmToolchain(17)`), Gradle launched with any JDK 17+ (e.g. Android Studio's JBR).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :tuya:test                 # library unit tests (JUnit 5)
./gradlew :app:testDebugUnitTest     # app unit/UI tests (Robolectric + Compose UI test)
./gradlew :app:assembleDebug         # debug APK -> app/build/outputs/apk/debug/
./gradlew :tuya:test :app:testDebugUnitTest :app:assembleDebug lint   # full CI check
./gradlew :app:installDebug          # install on a connected Pixel / emulator
```

Notes:
- Gradle does not auto-detect Homebrew JDKs; if the JDK 17 toolchain is not found, add
  `org.gradle.java.installations.paths=<jdk17 home>` to `~/.gradle/gradle.properties`.
- Robolectric tests run on API 35 (`app/src/test/resources/robolectric.properties`): its API 36
  sandbox needs Java 21 and there is no API 37 yet.
- compileSdk/targetSdk are 37; Compose BOM is pinned to 2026.09.00 because 2026.11.01 needs
  platform 37.1, which is not installed.
- CI (`.github/workflows/ci.yml`) runs the full check on pushes to `main` and on pull requests.
