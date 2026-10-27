# Hatchlybox

Hatchlybox is an Android app: a home screen (launcher) with widgets and themes, and a place to make apps, games
and widgets by describing them. AI helpers (Claude Code, Codex, OpenCode) do the making inside the app's own
Linux (Alpine, under proot), driven by Hatchlybox Engine.

## Layout

- `app/src/main/java/com/hatchlybox/app/`: the app (Kotlin, Jetpack Compose, Material 3).
  - `MainActivity.kt`, `Launcher.kt`, `Home.kt`, `Look.kt`, `Themed.kt`: the home screen, pages and themes.
  - `Widgets.kt`, `WidgetCanvas.kt`, `WidgetRuntime.kt`: home-screen widgets (specs in `assets/widgets/WIDGETS.md`).
  - `MakerModel.kt`, `MakerScreen.kt`, `MakerRuntime.kt`, `EngineState.kt`: creating, the library and settings,
    on top of the engine's `hatchlybox` command.
  - `Linux.kt`, `Setup.kt`, `Term.kt`, `TermSignIn.kt`: the app's Linux, its first setup, terminals and sign-ins.
  - `Hands.kt`, `HandsService.kt`, `PhoneBridge.kt`, `ActionReview.kt`: phone actions and their review.
  - `DeviceSync.kt`: sync with the user's other devices.
- `app/src/main/assets/maker/`: Hatchlybox Engine as the app runs it. Don't edit it here: change the engine's
  repository and copy it in with its `sync-hatchlybox.sh`.
- `app/src/main/assets/widgets/`, `themes/`, `paintings/`, `agents/`: bundled widgets, themes, backgrounds and
  the AI helpers' instructions.
- `art-src/`: the brand (`art-src/brand`) and how the backgrounds were made.

## Building

JDK 17, Gradle through `./gradlew`: `./gradlew --console=plain -q assembleRelease`. `gradle.properties` is shared by every machine:
nothing secret or machine-specific in it. The toolchain is fixed
(AGP 8.7.3, Kotlin 2.0.21, compileSdk and targetSdk 36): keep the versions in `gradle/libs.versions.toml`.

## Rules

- Users don't code: every message is plain words, every failure says what happened and what to do next.
- Nothing personal in the code: no keys, tokens, names, emails or paths from a developer's machine.

## Sending work to the maintainers

You may be working on someone's computer with `gh` signed in to the HatchlyBox organization. Everything goes through
GitHub for the maintainers to approve; nothing reaches `main` or `nightly` without it.

- **Never push to `main` or `nightly`, never merge, never force-push, never delete branches or tags you didn't make.**
- A problem or an idea: `gh issue create -R HatchlyBox/hatchlybox-android --template "Bug report"` (or `"Idea"`), the template
  filled in, labels `from-agent` and the device's (`phone`, `linux`, `macos`, `windows`). Engine problems go to
  HatchlyBox/hatchlybox instead.
- A change: a branch from an up-to-date `main` (`fix/...`, `idea/...`), a build that succeeds, then
  `gh pr create -R HatchlyBox/hatchlybox-android --fill-first --label from-agent` with the template filled in (`Fixes #12`).
- Commit as `Hatchlybox <hatchlybox@localhost>`, not as the computer's person.
- Never put personal details in issues or pull requests: write `/home/USER` in paths, leave out network addresses,
  emails, tokens and keys.
