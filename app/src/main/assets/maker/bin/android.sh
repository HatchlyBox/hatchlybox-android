# android.sh: the Android toolchain on any system (sourced after paths.sh and compat.sh). Which command-line tools
# to download, whether Google's build-tools run here or the same tools built for aarch64 are needed, where Java 17
# is, where an SDK may already be, and Gradle's settings for the machine. The phones keep exactly what they had.

# One version of the build-tools everywhere (sdkmanager installs it; on ARM Linux the aarch64 build goes in its place).
BUILD_TOOLS=35.0.0
# Pinned downloads: a changed file fails its checksum instead of running. Google publishes SHA-1 for the
# command-line tools (repository2-3.xml); these SHA-256 are of the files that matched it.
CLT_LINUX_URL=https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
CLT_LINUX_SHA=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
CLT_MAC_URL=https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip
CLT_MAC_SHA=5673201e6f3869f418eeed3b5cb6c4be7401502bd0aae1b12a29d164d647a54e
# Google's Linux build-tools are x86_64 only; these are the same tools built for aarch64 (static: any libc).
ARM_URL=https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
ARM_SHA=db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2

android_os() { case $(uname -s) in Darwin) echo macos ;; Linux) echo linux ;; *) uname -s | tr '[:upper:]' '[:lower:]' ;; esac; }
android_arch() { case $(uname -m) in x86_64|amd64) echo x86_64 ;; aarch64|arm64) echo arm64 ;; *) uname -m ;; esac; }

# android_clt: "<url> <sha256>" of the command-line tools for this system (nothing: Google makes none for it).
android_clt() {
  case $(android_os) in
    linux) echo "$CLT_LINUX_URL $CLT_LINUX_SHA" ;;
    macos) echo "$CLT_MAC_URL $CLT_MAC_SHA" ;;
  esac
}

# android_tools_kind: where this system's build-tools come from. google: Google's run here (x86_64 Linux, and Macs,
# whose tools Google builds for both chips); arm64-build: Linux on ARM (the phones, a Raspberry Pi, an ARM server),
# the aarch64 build; none: nothing runs here.
android_tools_kind() {
  case "$(android_os)/$(android_arch)" in
    linux/x86_64|macos/*) echo google ;;
    linux/arm64) echo arm64-build ;;
    *) echo none ;;
  esac
}

# bt <tool>: a build tool's path (aapt2, apksigner, zipalign…).
bt() { echo "$ANDROID_HOME/build-tools/$BUILD_TOOLS/$1"; }

# java17 <home>: whether that's a JDK 17 (its release file, else asking java itself).
java17() {
  [ -n "$1" ] && [ -x "$1/bin/java" ] || return 1
  if [ -f "$1/release" ]; then grep -q '^JAVA_VERSION="17[."]' "$1/release"; else "$1/bin/java" -version 2>&1 | grep -q 'version "17[."]'; fi
}

# android_java_home: a JDK 17's home (nothing: none here). The front end's choice first, then the phones' place (so
# they never change), then JAVA_HOME when it is a 17, then each system's usual places.
android_java_home() {
  local d
  for d in "${HATCHLYBOX_JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk "${JAVA_HOME:-}" \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17 /usr/lib/jvm/temurin-17-jdk* \
           /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
           /Library/Java/JavaVirtualMachines/*17*/Contents/Home; do
    java17 "$d" && { echo "$d"; return 0; }
  done
  # macOS's own finder, last: it starts a process.
  [ "$(android_os)" = macos ] && [ -x /usr/libexec/java_home ] && d=$(/usr/libexec/java_home -v 17 2>/dev/null) && java17 "$d" && { echo "$d"; return 0; }
  return 1
}

# android_java_hint: how to install JDK 17 on this system, in one line.
android_java_hint() {
  if [ "$(android_os)" = macos ]; then echo "brew install openjdk@17"
  elif command -v pacman >/dev/null 2>&1; then echo "pacman -S jdk17-openjdk"
  elif command -v apk >/dev/null 2>&1; then echo "apk add openjdk17-jdk"
  elif command -v apt-get >/dev/null 2>&1; then echo "apt install openjdk-17-jdk"
  elif command -v dnf >/dev/null 2>&1; then echo "dnf install java-17-openjdk-devel"
  else echo "install a JDK 17 and set HATCHLYBOX_JAVA_HOME to it"; fi
}

# android_sdk_dirs: where an SDK set up by hand (or by Android Studio) usually is, one per line.
android_sdk_dirs() {
  case $(android_os) in
    macos) echo "$HOME/Library/Android/sdk" ;;
    *) echo /opt/android-sdk; echo "$HOME/Android/Sdk"; echo /usr/lib/android-sdk ;;
  esac
}

# The machine's memory (MB) and processors; HATCHLYBOX_MEMORY_MB / HATCHLYBOX_CPUS say otherwise (tests, or a front end).
android_memory_mb() {
  [ -n "${HATCHLYBOX_MEMORY_MB:-}" ] && { echo "$HATCHLYBOX_MEMORY_MB"; return; }
  if [ -r /proc/meminfo ]; then awk '/^MemTotal:/ {print int($2 / 1024)}' /proc/meminfo
  else echo $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1048576 )); fi
}
android_cpus() { echo "${HATCHLYBOX_CPUS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)}"; }

# android_phone: build as on a phone (little memory, everything under proot): the settings tuned for that.
android_phone() { under_proot || [ "$(android_memory_mb)" -lt 6000 ]; }

# android_gradle_props <sdk>: gradle.properties for the apps built here. A phone keeps the settings tuned for it
# (a small heap, two workers, the compiler in-process); a computer gets a quarter of its memory (2–6 GB) and
# parallel work on half its processors (2–8). The aarch64 aapt2 replaces the x86_64 one AGP would download.
android_gradle_props() {
  local sdk=$1 mem cpu
  if android_phone; then
    cat <<EOF
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=false
org.gradle.workers.max=2
org.gradle.caching=true
kotlin.compiler.execution.strategy=in-process
EOF
  else
    mem=$(android_memory_mb); cpu=$(android_cpus)
    local heap=$(( mem / 4 )) workers=$(( cpu / 2 ))
    [ $heap -lt 2048 ] && heap=2048; [ $heap -gt 6144 ] && heap=6144
    [ $workers -lt 2 ] && workers=2; [ $workers -gt 8 ] && workers=8
    cat <<EOF
org.gradle.jvmargs=-Xmx${heap}m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.workers.max=$workers
org.gradle.caching=true
EOF
  fi
  echo "android.useAndroidX=true"
  echo "android.nonTransitiveRClass=true"
  [ "$(android_tools_kind)" = arm64-build ] && echo "android.aapt2FromMavenOverride=$sdk/build-tools/$BUILD_TOOLS/aapt2"
  echo "# AGP 8.7 knows API 35; 36 builds fine with it."
  echo "android.suppressUnsupportedCompileSdk=36"
}

# android_adb_hint: how to get adb on this system, in one line.
android_adb_hint() {
  if [ "$(android_os)" = macos ]; then echo "brew install android-platform-tools"
  elif command -v pacman >/dev/null 2>&1; then echo "pacman -S android-tools"
  elif command -v apk >/dev/null 2>&1; then echo "apk add android-tools"
  elif command -v apt-get >/dev/null 2>&1; then echo "apt install adb"
  elif command -v dnf >/dev/null 2>&1; then echo "dnf install android-tools"
  else echo "install Android's platform-tools"; fi
}
