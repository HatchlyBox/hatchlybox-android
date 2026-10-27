<p align="center">
  <img src="art-src/brand/hatchlybox-app-icon.svg" alt="Hatchlybox" width="112">
</p>

<h1 align="center">Hatchlybox</h1>

<p align="center"><b>Say it. Hatch it.</b></p>

<p align="center">
  Describe an app or a game, and AI builds it, installs it and keeps it in one library across all your devices.
</p>

<p align="center">
  <a href="../../releases/latest">Download</a> ·
  <a href="#getting-started">Getting started</a> ·
  <a href="#features">Features</a> ·
  <a href="app/src/main/assets/legal/PRIVACY.md">Privacy</a> ·
  <a href="CHANGELOG.md">What's new</a> ·
  <a href="https://github.com/sponsors/HatchlyBox">Sponsor</a>
</p>

---

Hatchlybox makes apps and games from what you describe, on Android, Linux, macOS and Windows. Type what you want,
such as *"a budget tracker with a pie chart"* or *"a neon racing game"*, and an AI helper (Claude Code, Codex or
OpenCode) writes it, builds it and installs it on the device you're using. Ask for changes the same way. Then ask for
the same creation on another system: the Android app gets its Linux, macOS, Windows or web version. Everything you
make lives in one library, synced between your phones and computers.

| | Phone (Android) | Computer (Linux, macOS, Windows) |
| --- | --- | --- |
| Create and change by describing | ✓, on the phone itself | ✓ |
| Makes versions for | every system | every system |
| Installs and runs here | Android apps, web apps | that computer's apps (Android ones go to your phone by QR code) |
| Library synced across devices | ✓ | ✓ |
| Also | a home screen with widgets and themes, actions on the phone | a desktop page in the browser |

No Hatchlybox account, no analytics, no server of ours in between. The AI helper runs under **your own** account.

## Features

### Create anything, for any system
- **Apps, games and widgets** from plain words, made and installed on the phone. No computer, no coding.
- **Games with Godot**: 2D and 3D games, exported for Android, Linux, macOS and Windows.
- **Change anything later**: "make the buttons bigger", "add a dark mode". Every change waits for you to keep it
  or throw it away.
- **One creation, every system**: make an Android app, then ask for its Linux, macOS, Windows or web version.
- **Your choice of AI helper**: Claude Code, Codex or OpenCode, signed in with your own account. Or just chat
  with it in the create box.

### One library, all your devices
- **Sync** your creations between your phone and computers: pair once with six digits or a QR code. It's
  end-to-end encrypted and goes straight from device to device.
- **Hatchlybox on computers**: the same engine with a desktop page in your browser, on Linux, macOS and Windows
  (through WSL).
- **GitHub, if you want**: keep any creation in your own private repository.
- **Google Play, if you want**: publish a creation to the Play Store from the app.

### On Android: a home screen that's yours
- **Widgets** for weather, battery, headlines, system, water, agents' status and more, or describe a new one.
- **Themes and 23 painted backgrounds**, with opacity you choose and pages you drag into order.
- **Web apps**: share any site from your browser to Hatchlybox, and it becomes an app with its own icon.
- **Quick actions** from the create box: "open maps", "call Ana", "timer 5 min".

### On Android: hands on your phone
- **Ask in words, and it acts**: a small on-device model (Google's FunctionGemma, offline, about a second) turns
  what you type into an action on the phone.
- **You stay in control**: safe actions run straight away, others need a tap, and sensitive ones need your
  fingerprint or face. A gold light shows where every action goes.

## Getting started

### On your phone (Android 8 or later)
1. Download `hatchlybox-<version>.apk` from the [latest release](../../releases/latest) and open it. Android asks
   you to allow installs from your browser or file manager the first time.
2. Open Hatchlybox. The welcome screen says what it downloads and what leaves the phone. Tap **Accept and start**.
3. Setup downloads about 1 GB of free tools in the background (Alpine Linux, Node.js, Java, Android's build
   tools, Godot). Wi-Fi and a charger help.
4. Sign in to an AI helper when it asks: Claude, ChatGPT (for Codex) or OpenCode.
5. Optional: make Hatchlybox your home app (Settings › Apps › Default apps › Home app).
6. Type what you want into the create box.

### On a computer
Paste one line. It installs everything Hatchlybox needs (your password is asked once) and opens it.

**Linux** (Arch/Omarchy, Debian, Ubuntu, Fedora):
```sh
curl -fsSL https://github.com/HatchlyBox/hatchlybox/releases/latest/download/hatchlybox-linux-install.sh | bash
```
**macOS** 12 or later, in Terminal:
```sh
curl -fsSL https://github.com/HatchlyBox/hatchlybox/releases/latest/download/hatchlybox-macos-install.command | bash
```
**Windows** 10 or 11, in PowerShell:
```powershell
irm https://github.com/HatchlyBox/hatchlybox/releases/latest/download/hatchlybox-windows-install.ps1 | iex
```

Or download one file from [Hatchlybox's latest release](https://github.com/HatchlyBox/hatchlybox/releases/latest):

| System | File | Run it |
| --- | --- | --- |
| Linux | `hatchlybox-linux-install.sh` | `bash hatchlybox-linux-install.sh` |
| macOS | `hatchlybox-macos-install.command` | `bash hatchlybox-macos-install.command` in Terminal |
| Windows | `hatchlybox-windows-install.ps1` | right-click it, **Run with PowerShell** |

### Pair your phone and computer
On the phone: **Library › Sync › Sync with another device**. On the computer: the Devices page, or
`hatchlybox sync receive <code>`. Both show the same six digits; confirm, and they keep each other up to date.

### Checking a download
Each release lists the APK's SHA-256. On a computer: `sha256sum hatchlybox-<version>.apk`. Official APKs are
signed with the Hatchlybox release key, so they update each other. A copy built by someone else can't replace an
official one.

## Privacy

- Nothing is sent to us. There's no Hatchlybox account, no analytics and no tracking.
- When an AI helper works, your request and the creation's files go to that helper's company (Anthropic, OpenAI,
  or OpenCode's provider), under your account and their terms.
- Sync goes directly between your devices, encrypted. GitHub and Google Play only when you connect them.

The full [privacy policy](app/src/main/assets/legal/PRIVACY.md) says exactly what goes where.

## Building from source

JDK 17, then `./gradlew --console=plain -q assembleRelease`. The engine inside the app
(`app/src/main/assets/maker`) comes from [HatchlyBox/hatchlybox](https://github.com/HatchlyBox/hatchlybox), copied in with its
`sync-hatchlybox.sh`. Your own builds are signed with the debug key: they install fine but can't update an official
copy (uninstall that one first).

## Support Hatchlybox

Hatchlybox is free, with no ads and no tracking. If it's useful to you, [sponsor it](https://github.com/sponsors/HatchlyBox):
sponsorships pay for its development.

## Contributing

Bug reports, ideas and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md), and the
[Contributor Licence Agreement](CLA.md) for code. Security problems: [SECURITY.md](SECURITY.md), please not as a public issue.

## Licence

Free software under the [GNU AGPL 3.0](LICENSE). The libraries inside it and the tools it downloads are listed in
[THIRD-PARTY](app/src/main/assets/legal/THIRD-PARTY.md). The Hatchlybox name and logo are not covered by the licence.
