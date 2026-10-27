# compat.sh: the same shell tools everywhere (sourced after paths.sh). The engine is written for GNU's tools
# (sed -i, stat, timeout, realpath -m, sha256sum, flock, setsid); Linux and the phones have them. On macOS
# Homebrew's GNU tools go first on the PATH (brew install coreutils gnu-sed findutils grep gawk flock jq),
# and setsid, which macOS lacks, starts a new session through Perl. `hatchlybox doctor` names what's missing.
if [ "$(uname -s)" = Darwin ]; then
  for _b in /opt/homebrew /usr/local; do
    for _p in coreutils gnu-sed findutils grep gawk; do
      [ -d "$_b/opt/$_p/libexec/gnubin" ] && case ":$PATH:" in *":$_b/opt/$_p/libexec/gnubin:"*) ;; *) PATH=$_b/opt/$_p/libexec/gnubin:$PATH ;; esac
    done
  done
  unset _b _p; export PATH
  command -v setsid >/dev/null || setsid() {
    local fork=''; [ "${1:-}" = -f ] && { fork=1; shift; }
    if [ -n "$fork" ]; then perl -MPOSIX -e 'fork and exit; POSIX::setsid(); exec @ARGV or die' "$@"
    else perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV or die' "$@"; fi
  }
fi
# compat_missing: the tools this system lacks for the engine, one per line (nothing: all there).
compat_missing() {
  local t
  for t in bash git jq curl sed stat timeout realpath sha256sum flock setsid unzip; do
    command -v "$t" >/dev/null 2>&1 || declare -F "$t" >/dev/null || echo "$t"
  done
  # GNU's own, not the BSD tools of the same name.
  sed --version >/dev/null 2>&1 || echo "GNU sed"
  stat -c %Y / >/dev/null 2>&1 || echo "GNU stat (coreutils)"
}
# file_mtime <path>: when it last changed, in seconds (GNU and BSD alike).
file_mtime() { date -r "$1" +%s; }
# lan_ip: this machine's address on the local network, for a phone on the same Wi-Fi (nothing: no network).
# HATCHLYBOX_LAN_IP says otherwise (tests, or a front end that knows better).
lan_ip() {
  [ -n "${HATCHLYBOX_LAN_IP:-}" ] && { echo "$HATCHLYBOX_LAN_IP"; return; }
  local ip="" ifc
  command -v ip >/dev/null 2>&1 && ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") {print $(i + 1); exit}}')
  if [ -z "$ip" ] && command -v ipconfig >/dev/null 2>&1; then
    ifc=$(route -n get default 2>/dev/null | awk '/interface:/ {print $2}')
    [ -n "$ifc" ] && ip=$(ipconfig getifaddr "$ifc" 2>/dev/null)
  fi
  # Where routes can't be read (Android's Linux), the first global address, Wi-Fi or Ethernet first.
  [ -n "$ip" ] || ip=$(ip -4 addr 2>/dev/null | awk '/inet / && / global / {a = $2; sub("/.*", "", a); if ($NF ~ /^(wlan|eth|en|wl)/) {print a; f = 1; exit} if (!o) o = a} END {if (!f && o) print o}')
  [ -n "$ip" ] || ip=$(hostname -I 2>/dev/null | awk '{print $1}')
  echo "$ip"
}
