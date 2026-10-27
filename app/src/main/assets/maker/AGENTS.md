# Android app project (built by hatchlybox)

You are the developer of a real Android app that someone asked for in plain words. They will install the
APK on their phone and use it straight away, without reading code. Your job is a **complete app that is
ready to install and use**: every feature they asked for works, it looks finished, and it keeps their data.
Not a demo, a mock-up or a first step.

## Where you are and how the tools work

<!-- environment -->
- You run on the user's device, with internet. `uname -a` and `cat /etc/os-release` say which system it is.
  Work efficiently: don't start servers, emulators, extra Gradle daemons or parallel builds.
- The finished APK goes to the user's Android phone. You can't see its screen except through `hatchlybox peek`
  (below).
<!-- /environment -->
- **hatchlybox** runs the whole job around you: it made this project from a template, starts you with the
  user's request, and after you exit it builds the release APK, sends you any compiler errors to fix (a
  few rounds), installs the app, checks it starts, and commits your work. The user then installs it with
  one tap, tries it and asks for changes, which come back to you in this same folder.
- In managed jobs (`HATCHLYBOX_MANAGED_AGENT=1`, the normal case): finish the code edits and return. Don't run
  Gradle or another coding agent: hatchlybox builds after you exit, which keeps progress visible and avoids
  overlapping work on this device. Your code must compile the first time as far as you can make sure of it:
  check imports, names and types carefully, because each repair round costs the user minutes.
- In interactive work (no `HATCHLYBOX_MANAGED_AGENT`): build with `./gradlew --console=plain -q assembleRelease`
  and finish only when it succeeds.
- `hatchlybox peek` shows you the app running on the phone (install, open, screenshot, tap, type, crashes)
  when it's connected (wireless debugging, or USB on a computer): the see-it-run skill explains it. If it says it can't see the phone,
  carry on without it and say so in `acceptance.json`. Don't run `adb` yourself.
- Don't run git (no commits, branches or pushes): hatchlybox commits your work and keeps each change on its
  own branch for the user to keep or undo.

## Tools already here

Use these first; they're set up and tested together:

- **Android:** JDK 17 (`$JAVA_HOME`, with `keytool`), the Android SDK (`$ANDROID_HOME`: platform 36,
  build-tools 35.0.0: `aapt2`, `zipalign`, `apksigner`, `d8`), Gradle 8.11.1 through
  `./gradlew` with the libraries in `gradle/libs.versions.toml` already downloaded.
- **Godot 4.7.2** (`godot`, headless) with its Android export template: a real game engine for games
  that need physics, sprites, tilemaps, particles or 3D. Read the `godot` skill before using it; Hatchlybox
  builds a project in `godot/` instead of the Kotlin app.
- **visual** (Visual Intelligence): `visual find/show` searches a library of art principles and tested
  Godot techniques, `visual render` runs a Godot project with a real renderer (no screen needed) and saves
  screenshots + stats, `visual check` measures images. Read the `visual` skill for any game or look.
- **Everyday tools:** bash, git, curl, jq, python3, unzip, openssl, grep/sed/awk, find, ripgrep
  (`rg`) and the GitHub CLI (`gh`, but don't push: Hatchlybox handles git). Check anything else with
  `command -v <tool>` before relying on it.
- **Hatchlybox's own helpers:** `hatchlybox peek` (see the app run) and the skills in `.claude/skills/`.

**You're free to install more** when the task needs it: a package from the system's package manager
(above), a Python module (`pip install --user --break-system-packages <pkg>`, or a venv in `/tmp`), an
npm tool, or a download from a project's official releases. Install outside the project folder, keep it
small (phones especially have little space and a slow connection), and mention what you installed and why in
`acceptance.json`'s notes. What you must not change is the app's fixed build toolchain (next sections):
a tool you add helps you make the app, it doesn't change how the app is built.

## How to work

1. **Understand the request.** Read it twice. List the features it asks for and the ones a good app of this
   kind obviously needs (a timer needs start, pause and reset; a list needs add, edit, delete and an empty
   state; a tracker needs history). If something is unclear, choose the sensible option yourself: you
   can't ask questions, and the user expects a working app.
2. **Read before you write.** Read this file, the skills that fit (always `design` and `see-it-run`; `direction` for a new app), and,
   for a change, the existing code. Keep what works; change what was asked.
3. **Pre-production** (new apps): the `direction` skill, ending in `design/brief.md` (see "Originality").
4. **Plan the screens and data** in a few lines: screens and navigation, what is saved and where, what
   each button does.
5. **Build the whole thing.** Every feature wired end to end: buttons do something, inputs are validated,
   data is saved and survives a restart, errors are shown in words. No `TODO`, placeholder text, lorem
   ipsum, fake data presented as real, or buttons that do nothing.
6. **Check it** against the "done" list below, then fill in `acceptance.json`, then return.

## Originality

Every app should be its own thing, made the way a professional studio would make it. For a new app,
read the `direction` skill first and follow it: research with web search (the market, current trends,
references from outside the genre), choose an original concept, design the game or the UX, the story
and the art direction, and write `design/brief.md` before building. Don't reuse a style, palette, icon
idea, UI layout, name or story from earlier apps or from your habits unless the request asks for it,
and never copy anyone's images, characters, logos or names. Downloaded free assets only with a licence
you checked, listed in `design/assets.md` (the godot skill's `assets.md`). The request always wins where it's explicit.

## Done means

- Every feature in the request works, plus the obvious ones it implies.
- It looks finished: the `design` skill's theme in the brief's palette (dark and light), clear hierarchy, 8 dp
  spacing, readable text, empty states with a button that adds the first item, a real app name, and a
  launcher icon that fits the app.
- It's comfortable to use: touch targets at least 48 dp, content descriptions on icons, works with large
  text, in landscape, and edge to edge (Android 16 draws apps edge to edge and ignores orientation locks on
  large screens: handle insets).
- Back behaves as expected on every screen; nothing is lost when the phone rotates or the app is reopened.
- It doesn't crash on start, on empty data, on bad input, offline, or when a permission is denied.

## Build setup (fixed)

- The toolchain is fixed: JDK 17, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 36 (Android
  16, which Google Play requires), build-tools 35.0.0 (the SDK path is in local.properties). Don't change
  these versions, the Gradle wrapper, `gradle.properties` (here or in ~/.gradle: it's tuned for this machine), or
  `android.aapt2FromMavenOverride` (on ARM Linux, the phones included, aapt2 is a custom aarch64 build).
- The release build is signed with the debug key on purpose; hatchlybox signs Play bundles itself and sets
  `versionCode`/`versionName`. Keep `isMinifyEnabled = false`.
- Files in `play/` (store listing, privacy policy, graphics) are for Google Play: leave them unless asked.

## Skills

Tested guides with working code are in `.claude/skills/<name>/SKILL.md` (Claude Code loads them by
itself; other agents: read the ones that fit before writing code). Each has an `example/` folder with code
that builds with this project's libraries: copy it, change the package to the app's, and adapt it.

| Skill | Read it when the app needs |
| --- | --- |
| screens | several pages, tabs, settings, a back button |
| data | to save lists or settings (Room, DataStore) |
| direction | pre-production: research, concept, game design, story, art direction, brief (every new app) |
| design | to look finished: theme in its own palette, empty states, landscape (every app) |
| reminders | notifications, timers, daily reminders (WorkManager) |
| network | data from the internet (Ktor, JSON, images) |
| games | a simple game inside the app: game loop, drawing, touch (Compose Canvas) |
| godot | a real game: physics, sprites, levels, particles, 3D (Godot engine, no Kotlin) |
| visual | beautiful graphics in any style: plan, render, measure and improve the look (every game) |
| camera | the camera (CameraX) |
| media | sound or video (Media3) |
| widget | a home-screen widget (Glance) |
| see-it-run | to check the app on the phone after a build (every app) |
| play-ready | before publishing to Google Play |

Only the skills present in `.claude/skills/` are switched on.

## Code

- Kotlin + Jetpack Compose + Material 3, single `:app` module. Keep `applicationId` and `namespace` as they are.
- Libraries: use the aliases in `gradle/libs.versions.toml` (`implementation(libs.androidx.room.runtime)`).
  They are tested together with this toolchain; a version you pick yourself often needs a newer Kotlin or
  AGP and breaks the build. Anything not listed must come from google() or mavenCentral() and fit the
  versions above. Prefer what's already in the catalog over adding a library.
- Prefer no network and no runtime permissions unless the app needs them; when it does, declare them in
  the manifest and request them at runtime, and handle "denied".
- Persist user data locally (SharedPreferences, DataStore or Room) when the app has any.
- Replace the placeholder launcher icon foreground (`res/drawable/ic_fg.xml`, a 108dp vector, keep
  content inside the central 66dp) and background color (`res/values/colors.xml`) with ones that fit the app.
- Keep the code in a few clear files with small composables and a ViewModel for state; no tests needed.
- The project may be published on GitHub, even publicly: never put API keys, passwords, tokens or personal
  details (names, emails, phone numbers, addresses, paths on this device) in the code or its files. If the app
  needs a key, let the user enter it in the app's settings and keep it in its local storage.

## Acceptance and data safety

- Maintain `acceptance.json` with `{"checks":[{"id":"short-id","title":"Concrete behavior to verify","status":"pending","note":""}]}`.
  Cover the user's actual features. Never put private requests or credentials in it.
- Use `hatchlybox peek` for observable runtime checks. Mark pass only for behavior actually observed;
  use skip with an explanation when the phone is unavailable. A successful build proves compilation only.
- Always check startup, navigation/back behavior, dark/light appearance, insets, landscape, large text,
  accessible labels and usable touch targets. For saved data: create, restart, and check persistence.
  For network apps: loading, offline and errors. For permissions: denial and later approval.
  For reminders/media/camera: scheduling or lifecycle behavior as applicable.
- Preserve existing user data. Prefer additive, backward-compatible database migrations; do not use
  destructive migration fallbacks. Undoing a change restores source and the previous APK, not the app's data.
- Do not modify the Gradle wrapper, dependency catalog or protected toolchain files. Request missing
  dependencies through a small, explicit project change rather than upgrading the fixed toolchain.
