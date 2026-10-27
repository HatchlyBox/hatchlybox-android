# Shared locking, identities and immutable build receipts. Sourced by hatchlybox.
# Keep one lock per JSON file across its complete read/modify/rename transaction.
json_edit() (
  local f=$1 tmp; shift
  mkdir -p "$(dirname "$f")"
  local lock; lock=$(printf '%s' "$(realpath -m "$f")" | sha256sum | cut -d' ' -f1)
  mkdir -p "$CACHE/state-locks"
  exec 7> "$CACHE/state-locks/$lock"; flock -w 30 7 || return 1
  tmp=$(mktemp "$(dirname "$f")/.json.XXXX") || return 1
  trap 'rm -f "$tmp"' EXIT
  { cat "$f" 2>/dev/null || echo '{}'; } | jq -c "$@" > "$tmp" && mv "$tmp" "$f"
)
# process_id <pid>: who that process is: its pid's start time, so a pid used again by another process never
# passes for it. From Linux's /proc where there is one, else from ps (macOS, BSD). HATCHLYBOX_PROC: tests.
process_id() {
  local proc=${HATCHLYBOX_PROC:-/proc} s
  if [ -d "$proc/self" ]; then
    [ -r "$proc/$1/stat" ] && sed 's/.*) //' "$proc/$1/stat" | awk '{print $20}'
  else
    s=$(ps -o lstart= -p "$1" 2>/dev/null) && [ -n "$s" ] && tr -s ' ' '-' <<< "$s" | sed 's/^-//; s/-$//'
  fi
}
worker_alive() {
  local p id
  p=$(jq -r '.pid // 0' "$JOB" 2>/dev/null); id=$(jq -r '.process // empty' "$JOB" 2>/dev/null)
  [[ $p =~ ^[0-9]+$ ]] && [ "$p" -gt 1 ] && [ -n "$id" ] && [ "$(process_id "$p")" = "$id" ] && ! orphaned "$p"
}
# Under the phone app's proot every process is traced. When the proot that ran a process dies (its terminal
# closed or crashed, Android killed it), the process lives on untraced but proot's seccomp filter stays:
# fork, threads and wait fail with ENOSYS, so it can never finish. Such a process counts as dead.
tracer_of() { awk '/^TracerPid:/ {print $2}' "/proc/$1/status" 2>/dev/null; }
# under_proot: this engine runs under proot (the phones' Linux): traced, and by proot itself (not, say, a
# debugger on a computer). Only there do untraced processes mean orphans; everywhere else this is all off.
under_proot() {
  local t; t=$(tracer_of $$)
  [ -n "$t" ] && [ "$t" != 0 ] && [[ $(readlink "/proc/$t/exe" 2>/dev/null) == *proot* ]]
}
orphaned() {
  under_proot || return 1
  [ "$(tracer_of "$1")" = 0 ] && [[ $(readlink "/proc/$1/exe" 2>/dev/null) != */libproot.so ]]
}
# reap_orphans [pgid]: SIGKILL (nothing else can be handled) orphaned Gradle/Kotlin daemons, which a
# new build would otherwise connect to and hang on, and the orphaned rest of a dead job's group.
reap_orphans() {
  local group=${1:-} p
  under_proot || return 0
  for p in $(grep -lsx 'TracerPid:[[:space:]]*0' /proc/[0-9]*/status | cut -d/ -f3); do
    [[ $(readlink "/proc/$p/exe" 2>/dev/null) != */libproot.so ]] || continue
    if { [ -n "$group" ] && [ "$(ps -o pgid= -p "$p" 2>/dev/null | tr -d ' ')" = "$group" ]; } \
      || tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -qE 'GradleDaemon|GradleWrapperMain|KotlinCompileDaemon'; then
      kill -9 "$p" 2>/dev/null
    fi
  done
  true
}
mutation_lock() {
  [ "${MUTATION_LOCKED:-}" = 1 ] && return 0
  exec 6> "$CACHE/work.lock"; flock -n 6 || { echo "another job or update is running" >&2; exit 1; }
  MUTATION_LOCKED=1
}
vault_lock() { exec 5> "$DATA/vault.lock"; flock -w 15 5 || { echo "publishing keys are in use" >&2; exit 1; }; }
# Selected once and passed to every device-specific adb operation. No arbitrary first-device fallback.
# Every adb call closes the lock descriptors (5-9): adb starts its server in the background when none is
# running, and a server that inherited work.lock or apps-index.lock would hold it for as long as it runs:
# later jobs would be refused ("another job or update is running") and listing apps would fail.
# adb_bin: the adb to run: the system's (the phones' packages, Homebrew, a distribution's), else the SDK's
# platform-tools (setup installs them where Google's tools run).
adb_bin() { type -P adb || { [ -x "${ANDROID_HOME:-}/platform-tools/adb" ] && echo "$ANDROID_HOME/platform-tools/adb"; }; }
# adb_addr <port | host:port>: where to connect. A bare port is this phone's own wireless debugging (127.0.0.1);
# a computer's phones are at the address they announce, which is passed whole.
adb_addr() { case $1 in *:*) echo "$1" ;; '') ;; *) echo "127.0.0.1:$1" ;; esac; }
adb() {
  local action=${1:-}
  case $action in
    devices|connect|pair|mdns|start-server|kill-server|version) timeout 15s "$(adb_bin)" "$@" 5>&- 6>&- 7>&- 8>&- 9>&- ;;
    *)
      if [ -z "${ADB_SERIAL:-}" ]; then
        local devices; devices=$(timeout 10s "$(adb_bin)" devices 2>/dev/null 5>&- 6>&- 7>&- 8>&- 9>&- | awk 'NR>1 && $2=="device" {print $1}')
        [ "$(wc -w <<< "$devices")" = 1 ] || { echo "select one device: hatchlybox set device <serial>" >&2; return 1; }
        ADB_SERIAL=$devices
      fi
      timeout 90s "$(adb_bin)" -s "$ADB_SERIAL" "$@" 5>&- 6>&- 7>&- 8>&- 9>&- ;;
  esac
}
# Avoid indefinite network operations. Local git also gets a bounded deadline.
git() { timeout 120s "$(type -P git)" "$@"; }
gh() { timeout 120s "$(type -P gh)" "$@"; }
isolated() { "$DATA/bin/isolate" "$@"; }
# An app's source files, from inside its folder: tracked and untracked, including ignored Gradle
# configuration; not products or state. Extra find arguments (the action) come last.
source_find() {
  find . -type f ! -path './.git/*' ! -path './.gradle/*' ! -path './.kotlin/*' \
    ! -path '*/build/*' ! -path './.claude/*' ! -path './.peek/*' ! -path './play/*' \
    ! -name '.hatchlybox.json*' ! -name 'local.properties' ! -name 'CLAUDE.md' \
    ! -name '*.apk' ! -name '*.aab' "$@"
}
source_id() (
  cd "$APPS/$1" || exit 1
  source_find -print0 | sort -z | xargs -0 -r sha256sum | sha256sum | cut -d' ' -f1
)
# source_changed <slug> <epoch>: a source file was saved after that time (cheap, unlike source_id).
source_changed() (
  cd "$APPS/$1" 2>/dev/null || exit 1
  [ -n "$(source_find -newermt "@$2" -print -quit 2>/dev/null)" ]
)
artifact_record() {
  local slug=$1 kind=$2 file=$3 certificate=${4:-} hash src commit
  [ -n "$certificate" ] || certificate=$(jq -r --arg k "$kind" '.artifacts[$k].certificate // empty' "$APPS/$slug/.hatchlybox.json")
  hash=$(sha256sum "$file" | cut -d' ' -f1) || return 1
  src=$(source_id "$slug") || return 1
  commit=$(git -C "$APPS/$slug" rev-parse HEAD 2>/dev/null || true)
  meta "$slug" '.artifacts[$kind]={sha256:$hash, source:$source, commit:$commit, certificate:$cert, job:$job, at:$t}' \
    --arg kind "$kind" --arg hash "$hash" --arg source "$src" --arg commit "$commit" --arg cert "$certificate" \
    --arg job "${JOB_ID:-manual}" --argjson t "$(now)"
}
artifact_ok() {
  local slug=$1 kind=$2 file=$3 hash
  [ -s "$file" ] || return 1
  hash=$(sha256sum "$file" | cut -d' ' -f1) || return 1
  jq -e --arg k "$kind" --arg h "$hash" --arg s "$(source_id "$slug")" \
    '.artifacts[$k].sha256 == $h and .artifacts[$k].source == $s' "$APPS/$slug/.hatchlybox.json" >/dev/null
}
certificate_id() { openssl x509 -inform DER -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'; }
key_certificate() {
  KS_PASS=$(cat "$KEYS/$1.pass") keytool -exportcert -alias upload -keystore "$KEYS/$1.p12" -storepass:env KS_PASS 2>/dev/null | certificate_id
}
bundle_certificate() {
  unzip -p "$1" 'META-INF/*.RSA' 2>/dev/null | openssl pkcs7 -inform DER -print_certs 2>/dev/null \
    | openssl x509 -outform DER 2>/dev/null | certificate_id
}
protected_id() {
  local dir=$1
  (cd "$dir" && sha256sum gradlew gradle/wrapper/* gradle.properties gradle/libs.versions.toml 2>/dev/null)
}
validate_project() {
  local slug=$1 pkg=${2:-} dir=$APPS/$1
  [ -n "$pkg" ] || pkg=$(jq -r .pkg "$dir/.hatchlybox.json")
  grep -qE "applicationId *= *\"$pkg\"" "$dir/app/build.gradle.kts" && \
    grep -qE "namespace *= *\"$pkg\"" "$dir/app/build.gradle.kts" && \
    grep -qE 'isMinifyEnabled *= *false' "$dir/app/build.gradle.kts" || {
      echo 'package identity or protected build configuration changed' >> "$LOG"; return 1;
    }
}
# Per-job run budget covers agent, build and crash-repair rounds together.
agent_budget() {
  local used=${AGENT_RUNS:-0} max=$(( (FIX_ROUNDS + 1) * 2 + 2 ))
  [ "$used" -lt "$max" ] && [ "$(now)" -lt "${JOB_DEADLINE:-$(( $(now) + 1 ))}" ] || return 1
  AGENT_RUNS=$((used + 1))
}
rotate_vault() (
  local old=$VP new t f generation link
  new_pass || return 1; new=$VP
  t=$(mktemp -d "$CACHE/.unlocked.$$.XXXX") || return 1
  generation=$DATA/vault.generations/$(now)-$$-$RANDOM
  mkdir -p "$generation"
  # Only ciphertext survives a signal. Before activation the original vault stays valid.
  trap 'rm -rf "$t"; recover_vault' EXIT
  trap 'exit 130' INT TERM HUP
  local active=$VAULT
  while IFS= read -r -d '' f; do
    local name=${f#"$active"/}; name=${name%.enc}
    VP=$old VAULT=$active
    vault_dec "$name" "$t/plain" || return 1
    VP=$new VAULT=$generation
    vault_put "$t/plain" "$name" || { VAULT=$active; return 1; }
    rm -f "$t/plain"
  done < <(find -L "$active" -type f -name '*.enc' -print0)
  VAULT=$generation VP=$new
  vault_check || { VAULT=$active; return 1; }
  VAULT=$active
  link=$DATA/.vault.link.$$
  ln -s "$generation" "$link" || return 1
  # Legacy directory migration: recovery restores it if power fails before pointer activation.
  if [ -d "$VAULT" ] && [ ! -L "$VAULT" ]; then
    [ ! -e "$DATA/vault.previous" ] || { echo 'vault.previous already exists; recovery needed' >&2; return 1; }
    : > "$DATA/.vault-activating"
    mv "$VAULT" "$DATA/vault.previous" || return 1
  fi
  mv -Tf "$link" "$VAULT" || return 1
  rm -f "$DATA/.vault-activating"
  rm -rf "$t"
  trap - EXIT INT TERM HUP
  echo 'passphrase changed; complete previous encrypted vault retained for recovery'
)
recover_vault() {
  if [ -f "$DATA/.vault-activating" ] && [ ! -e "$DATA/vault" ] && [ ! -L "$DATA/vault" ] && [ -d "$DATA/vault.previous" ]; then
    mv "$DATA/vault.previous" "$DATA/vault"
    rm -f "$DATA/.vault-activating"
  fi
}
release_preflight() {
  local slug=$1 dir=$APPS/$1 f=$APPS/$1/play/listing.json
  listing_ok "$slug" || { echo 'complete the store listing first' >&2; return 1; }
  jq -e '(.shortDescription | length)>0 and (.fullDescription | length)>0' "$f" >/dev/null || return 1
  [ -s "$dir/play/privacy-policy.md" ] && [ -s "$dir/play/icon.png" ] && [ -s "$dir/play/feature.png" ] || {
    echo 'privacy policy or store graphics are missing' >&2; return 1;
  }
  artifact_ok "$slug" aab "$CACHE/apks/$slug.aab" || { echo 'bundle is outdated: prepare again' >&2; return 1; }
  local cert; cert=$(bundle_certificate "$CACHE/apks/$slug.aab")
  [ -n "$cert" ] && [ "$cert" = "$(jq -r '.artifacts.aab.certificate' "$dir/.hatchlybox.json")" ] || return 1
  local count; count=$(find "$dir/play/shots" -maxdepth 1 -name '*.png' -type f 2>/dev/null | wc -l)
  [ "$count" -ge 2 ] && [ "$(jq -r '.play.graphicsSource // empty' "$dir/.hatchlybox.json")" = "$(source_id "$slug")" ] || { echo 'capture at least two screenshots of the current build before review' >&2; return 1; }
  local review; review=$(release_review_id "$slug")
  jq -e --arg h "$review" '.play.reviewed == $h' "$dir/.hatchlybox.json" >/dev/null || {
    echo 'review the release: hatchlybox play preview <app>, then hatchlybox play approve <app> <fingerprint>' >&2; return 1;
  }
}
release_review_id() {
  local slug=$1
  { source_id "$slug"; sha256sum "$CACHE/apks/$slug.aab"; find "$APPS/$slug/play" -type f -print0 | sort -z | xargs -0 -r sha256sum; } | sha256sum | cut -d' ' -f1
}
release_preview() {
  local slug=$1 h; h=$(release_review_id "$slug") || return 1
  jq --arg fingerprint "$h" '{app:.label,package:.pkg,version:.play.vn,code:.play.vc,bundle:.artifacts.aab,graphics:.play.graphics,listing:.play.title,notes:.play.notes,reviewFingerprint:$fingerprint,tracks:.play.tracks}' "$APPS/$slug/.hatchlybox.json"
  echo "Review play/listing.json, play/privacy-policy.md, play/shots and release notes, then:"
  echo "hatchlybox play approve $slug $h"
}
