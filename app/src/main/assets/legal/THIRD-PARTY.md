# Open-source licences

Hatchlybox is free software: you can use, study, share and change it under the GNU Affero General Public License,
version 3 (its full text is below). Its source is on Hatchlybox's GitHub page. "Hatchlybox" and its logo are not
covered by that licence.

## Inside this app

- PRoot 5.1.107.96 (Termux's build): runs the app's Linux. GNU GPL 2.0 or later. Copyright STMicroelectronics and
  the PRoot authors.
- talloc 2.5.0: used by PRoot. GNU LGPL 3.0 or later. Copyright the Samba team.
- libandroid-shmem: used by PRoot. BSD 3-Clause. Copyright Sergii Pylypenko, Fredrik Fornwall.
- llama.cpp and ggml: run the local model. MIT. Copyright the ggml authors.
- AndroidX, Jetpack Compose, Material Components, Kotlin standard library and coroutines: Apache 2.0. Copyright
  Google LLC, JetBrains s.r.o. and their contributors.
- Gradle wrapper (in the app template): Apache 2.0. Copyright Gradle, Inc.

### The source of PRoot and talloc

Their licences give you the right to their source. The exact source of the builds inside each version of
Hatchlybox is attached to its release on GitHub (github.com/HatchlyBox/hatchlybox-android/releases) as
hatchlybox-<version>-gpl-sources.tar.gz, with Termux's build recipes and what was changed for the app. Upstream,
it is at github.com/termux/proot and www.samba.org/ftp/talloc. For three years from when you got this version of
Hatchlybox, the exact source of the builds inside it is also available from Hatchlybox, at no more than the cost
of sending it: ask by opening an issue on Hatchlybox's GitHub page.

## Downloaded when you set up or ask for them

These aren't part of the app: Hatchlybox downloads them from their own projects onto your device, under their own
licences.

- Alpine Linux and its packages (Node.js, Java, git and others): each package's own licence.
- Claude Code (Anthropic, proprietary), Codex CLI (OpenAI, Apache 2.0), OpenCode (MIT): installed when you choose
  them, under their terms.
- Google's Android SDK (command-line tools, platform, build-tools): Google's Android Software Development Kit
  License Agreement, which you accept before it downloads.
- Android build-tools for ARM (lzhiyong/android-sdk-tools, from AOSP): Apache 2.0.
- Gradle and the Maven libraries the app template uses: Apache 2.0 and their own licences.
- Godot Engine 4.7.2 and its export templates: MIT (full text below), with its own third-party list. Games
  exported with Godot carry Godot's notice.
- Debian's glibc, Mesa and X libraries (for Godot and screenshots): LGPL and MIT.
- FunctionGemma 270M (Google, from Hugging Face): Google's Gemma Terms of Use and Prohibited Use Policy, which you
  accept before it downloads.
