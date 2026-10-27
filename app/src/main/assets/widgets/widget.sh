#!/bin/sh
# widget: Hatchlybox's home-screen widgets (see /root/widgets/WIDGETS.md).
D=/root/widgets
die() { echo "$*" >&2; exit 1; }
# POSIX record lock shared with Android's FileChannel.lock(). All state merges are atomic.
merge_state() {
  python3 - "$1" "$2" <<'PY'
import fcntl,json,os,sys,tempfile
from pathlib import Path
path=Path(sys.argv[1]);values=json.loads(sys.argv[2])
if not isinstance(values,dict):raise ValueError('State updates must be a JSON object')
path.parent.mkdir(parents=True,exist_ok=True)
with (path.parent/'.state.lock').open('a') as lock:
    fcntl.lockf(lock,fcntl.LOCK_EX)
    state=json.loads(path.read_text()) if path.exists() else {}
    if not isinstance(state,dict):raise ValueError('Existing state is invalid; its file was kept')
    state.update(values)
    fd,name=tempfile.mkstemp(prefix='.state-',suffix='.tmp',dir=path.parent)
    try:
        with os.fdopen(fd,'w') as out:
            json.dump(state,out);out.flush();os.fsync(out.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name):os.unlink(name)
PY
}

case "$1" in
  list)
    for d in "$D"/*/; do [ -f "$d/widget.json" ] || continue; [ "$(basename "$d")" = examples ] && continue
      printf '%s\t%s\n' "$(basename "$d")" "$(jq -r '.name // ""' "$d/widget.json" 2>/dev/null)"; done ;;
  examples)
    for d in "$D"/examples/*/; do [ -f "$d/widget.json" ] || continue
      printf '%s\t%s\n' "$(basename "$d")" "$(jq -r '.description // .name // ""' "$d/widget.json" 2>/dev/null)"; done ;;
  new)
    [ -n "$2" ] || die "usage: widget new <id> [example]"
    [ -e "$D/$2" ] && die "$D/$2 already exists."
    if [ -n "$3" ]; then
      [ -d "$D/examples/$3" ] || die "There's no example $3. widget examples lists them."
      cp -r "$D/examples/$3" "$D/$2" && echo "Made $D/$2 from the $3 example. Change it, then run: widget check $2"
    else mkdir -p "$D/$2" && echo "Made $D/$2. Write widget.json there, then run: widget check $2"; fi ;;
  check)
    [ -n "$2" ] || die "usage: widget check <id> [small|wide|large|auto|all|screen|motion]"
    dir="$D/$2"; f="$dir/widget.json"; [ -f "$f" ] || die "There's no $f."
    case "$3" in ""|small|wide|large|auto|all|screen|motion) ;; *) die "The size to draw is small, wide, large, auto, all (nine sizes in one picture), screen (the widget's screen) or motion (six moments of its animation side by side)." ;; esac
    jq . "$f" >/dev/null || die "widget.json isn't valid JSON (above)."
    jq -e 'type == "object" and ((.ui | type) == "object" or (.terminal | type) == "object")' "$f" >/dev/null || die "widget.json needs a \"ui\" object (or a \"terminal\" object)."
    bad=$(jq -r '[(.ui // {}), (.views // [] | .[].ui // {}) | .. | objects | select(has("type")) | .type] | unique[]' "$f" | grep -vxE 'column|row|box|text|icon|progress|meter|stat|header|chart|list|clock|image|canvas|button|spacer|divider')
    echo "$bad" | grep -qx 'terminal' && die "A terminal isn't a node: put \"terminal\": {\"command\": \"opencode\"} at the top of widget.json in place of \"ui\" (see Terminal widgets in WIDGETS.md)."
    [ -z "$bad" ] || die "Unknown node types: $(echo $bad). There is no other node type: see the table in WIDGETS.md."
    jq -e '.size // "auto" | IN("small","wide","large","auto")' "$f" >/dev/null || die "size must be small, wide, large or auto."
    if jq -e '.terminal' "$f" >/dev/null; then
      cmd=$(jq -r '.terminal.command // ""' "$f")
      [ -n "$cmd" ] || die "terminal needs a \"command\", e.g. \"opencode\"."
      prog=$(printf '%s' "$cmd" | awk '{print $1}')
      case "$prog" in cd|exec|*=*) ;; *) command -v "$prog" >/dev/null 2>&1 || die "The terminal's program \"$prog\" isn't installed here (command -v $prog finds nothing).";; esac
      echo "terminal: runs \"$cmd\" in /root on a full screen the widget's size."
    fi
    data=$(jq -r '.data // ""' "$f")
    if [ -n "$data" ]; then
      out=$(cd "$dir" && timeout 30 sh -c "$data") || die "The data command failed: $out"
      printf '%s' "$out" | jq -e 'type == "object"' >/dev/null 2>&1 || die "The data command must print one JSON object; it printed: $(printf '%s' "$out" | head -c 300)"
      echo "data: $(printf '%s' "$out" | head -c 300)"
      # The picture shows this live data.
      merge_state "$dir/state.json" "$out" || die "The state update failed; existing state was kept."
    fi
    # Hatchlybox draws it, out of sight, and leaves a picture and what it couldn't draw.
    rm -f "$dir/.check.png" "$dir/.check.txt"; printf '%s' "$3" > "$dir/.check"
    i=0; while [ ! -f "$dir/.check.txt" ] && [ $i -lt 40 ]; do sleep 0.5; i=$((i + 1)); done
    if [ -f "$dir/.check.txt" ]; then cat "$dir/.check.txt"
    else rm -f "$dir/.check"; echo "No picture: Hatchlybox didn't answer (it draws only while it's open on screen). The checks above passed."; fi
    if grep -q '^!' "$dir/.check.txt" 2>/dev/null; then echo "Not OK yet: fix the lines starting with ! (they are drawn differently from what you wrote)."; exit 1; fi
    [ -f "$dir/.check.png" ] && echo "Look at the picture to check the layout: is it readable, balanced, nothing cut off? Then run: widget check $2 all, to see it from tiny to large."
    [ "$3" = motion ] && [ -f "$dir/.check.png" ] && echo "The six moments should differ where things move (breathing, a tail, eyes, water): if they look the same, nothing moves."
    echo "OK: $(jq -r '.name // "widget"' "$f") is valid." ;;
  done)
    # The agent's last step: Hatchlybox ends the task once it's said (an agent can keep running after it's finished).
    [ -f "$D/$2/widget.json" ] || die "usage: widget done <id> \"what you made, in one line\" (after widget check <id> says OK)"
    id=$2; shift 2; printf '%s' "$*" > "$D/$id/.done"; echo "Done: Hatchlybox shows the widget and ends this task now. Stop here." ;;
  errors)
    cat "$D/errors.txt" 2>/dev/null || echo "No errors.txt yet: Hatchlybox writes it while the home screen is open." ;;
  set)
    [ $# -ge 4 ] || die "usage: widget set <id> <key> <value>"
    id=$2; key=$3; shift 3; v="$*"; s="$D/$id/state.json"
    [ -d "$D/$id" ] || die "There's no widget $id."
    if printf '%s' "$v" | jq . >/dev/null 2>&1; then values=$(jq -nc --arg k "$key" --argjson v "$v" '{($k): $v}')
    else values=$(jq -nc --arg k "$key" --arg v "$v" '{($k): $v}'); fi
    merge_state "$s" "$values" || die "The state update failed; existing state was kept." ;;

  refresh)
    [ -d "$D/$2" ] || die "There's no widget $2."
    touch "$D/$2/.refresh" ;;
  *) echo "usage: widget list | examples | new <id> [example] | check <id> [size] | done <id> "summary" | errors | set <id> <key> <value> | refresh <id>"; exit 1 ;;
esac
