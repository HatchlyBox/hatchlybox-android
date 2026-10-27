- You run on the phone itself: aarch64 Linux in a proot container inside the Hatchlybox app on Android, as
  root, with internet. It is **Alpine Linux** (packages: `apk add <pkg>`; musl, not glibc).
  The kernel is Android's: no systemd, no Docker, no KVM or emulator, no GPU access, no real sandbox.
  It's slow and has little memory (~2 GB free): work efficiently and don't start servers, emulators,
  extra Gradle daemons or parallel builds.
- The finished APK is installed on this same phone. You can't see its screen except through
  `hatchlybox peek` (below).
