#!/bin/sh
# theme: Hatchlybox's launcher themes (see /root/themes/THEMES.md).
D=/root/themes
die() { echo "$*" >&2; exit 1; }

# The checks, the same ones Hatchlybox makes when it loads a theme. Prints one problem per line:
# "error: ..." (that part is ignored by the launcher) or "warning: ..." (it works, but looks poor).
CHECK='
def hex: type == "string" and test("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
def h2: explode | map(if . >= 97 then . - 87 elif . >= 65 then . - 55 else . - 48 end) | .[0] * 16 + .[1];
def ch: h2 / 255 | if . <= 0.03928 then . / 12.92 else pow((. + 0.055) / 1.055; 2.4) end;
def lum: ltrimstr("#") | (if length == 8 then .[2:] else . end) as $x
  | 0.2126 * ($x[0:2] | ch) + 0.7152 * ($x[2:4] | ch) + 0.0722 * ($x[4:6] | ch);
def contrast($a; $b): ([($a | lum), ($b | lum)] | max + 0.05) / ([($a | lum), ($b | lum)] | min + 0.05);
def oneof($k; $vals): if has($k) and ((.[$k] | IN($vals[])) | not) then "error: \($k) must be one of \($vals | join(", ")); it is \(.[$k] | tojson)" else empty end;
def num($k; $lo; $hi): if has($k) then (if (.[$k] | type) != "number" then "error: \($k) must be a number from \($lo) to \($hi)"
  elif .[$k] < $lo or .[$k] > $hi then "error: \($k) is \(.[$k]); it must be from \($lo) to \($hi) (it would be clamped)" else empty end) else empty end;
def bool($k): if has($k) and (.[$k] | type) != "boolean" then "error: \($k) must be true or false" else empty end;
def tones: ["red", "orange", "yellow", "green", "cyan", "blue", "magenta"];
if type != "object" then "error: theme.json must be one JSON object" else
  (if (.name // "" | tostring | length) == 0 then "error: name is missing" else empty end),
  (keys[] | select(IN("name", "description", "style", "dark", "colors", "corner", "border", "borderColor", "glow", "font", "uppercase",
    "motion", "gap", "cardOpacity", "frameAll", "clock", "background") | not) | "error: unknown key \(tojson) (see theme defaults)"),
  oneof("style"; ["soft", "oldschool", "neon", "glass", "tiled"]),
  oneof("dark"; ["auto", "always", "never"]),
  oneof("font"; ["sans", "mono", "serif"]),
  oneof("motion"; ["smooth", "snappy", "pulse", "float"]),
  oneof("clock"; ["thin", "mono", "glow", "light"]),
  num("corner"; 0; 40), num("border"; 0; 6), num("gap"; 0; 16), num("cardOpacity"; 0; 1),
  bool("glow"), bool("uppercase"), bool("frameAll"),
  (if has("borderColor") and ((.borderColor | IN("outline", "text", "accent", "glow", "light")) or (.borderColor | hex) | not)
    then "error: borderColor must be outline, text, accent, glow, light or a #hex" else empty end),
  (if has("colors") then (.colors | if type != "object" then "error: colors must be an object with light and dark" else
    (keys[] | select(IN("light", "dark") | not) | "error: colors.\(.) is unknown: use colors.light and colors.dark"),
    (if has("light") or has("dark") | not then "error: colors needs light, dark or both" else empty end),
    (to_entries[] | select(.key | IN("light", "dark")) | .key as $m | .value |
      if type != "object" then "error: colors.\($m) must be an object" else
        (to_entries[] | select(.key | IN(["surface", "text", "accent", "glow"] + tones | .[]) | not) | "error: colors.\($m).\(.key) is unknown (surface, text, accent, glow, \(tones | join(", ")))"),
        (to_entries[] | select(.value | hex | not) | "error: colors.\($m).\(.key) must be a #RRGGBB colour; it is \(.value | tojson)"),
        (["surface", "text", "accent"] - keys | select(length > 0) | "warning: colors.\($m) has no \(join(", ")): taken from the default palette"),
        (if (.surface | hex) and (.text | hex) and contrast(.text; .surface) < 7 then "warning: colors.\($m): text on surface is \(contrast(.text; .surface) * 10 | floor / 10):1, aim for 7:1" else empty end),
        (if (.surface | hex) and (.accent | hex) and contrast(.accent; .surface) < 3 then "warning: colors.\($m): accent on surface is \(contrast(.accent; .surface) * 10 | floor / 10):1, aim for 3:1" else empty end),
        (tones[] as $t | select((.[$t] | hex) and (.surface | hex)) | select(contrast(.[$t]; .surface) < 2.5) | "warning: colors.\($m).\($t) is hard to see on surface (\(contrast(.[$t]; .surface) * 10 | floor / 10):1)")
      end)
  end) else empty end),
  (if has("background") then (.background | if type == "string" then (if IN("theme", "plain", "wallpaper", "glow", "mesh", "aurora", "waves", "dunes", "peaks", "stars", "rain", "sunset", "horizon", "embers", "fog", "topo", "bauhaus", "frost", "grid", "dots", "city") or test("^p:(tokyonight|neonrain|mainframe|arcade|fjord|moss|campfire|greatwave|jadealley|espresso|starlit|icedesk|ember|bog|lastlight|mission|fogbank|void|marshmallow|inkpaper|petal|wildflower|gallery)$") then empty else "error: background must be theme, plain, wallpaper, an art name (glow, mesh, aurora, waves, dunes, peaks, stars, rain, sunset, horizon, embers, fog, topo, bauhaus, frost, grid, dots, city), a painting (p:tokyonight, p:fjord… see THEMES.md) or {\"image\": \"file\"}" end)
    elif type == "object" then ((if has("image") then empty elif (.kind // "" | IN("theme", "plain", "wallpaper")) then empty else "error: background needs kind (theme, plain, wallpaper) or image" end),
      (if has("art") and (((.art | IN("glow", "mesh", "aurora", "waves", "dunes", "peaks", "stars", "rain", "sunset", "horizon", "embers", "fog", "topo", "bauhaus", "frost", "grid", "dots", "city")) or (.art | test("^p:(tokyonight|neonrain|mainframe|arcade|fjord|moss|campfire|greatwave|jadealley|espresso|starlit|icedesk|ember|bog|lastlight|mission|fogbank|void|marshmallow|inkpaper|petal|wildflower|gallery)$"))) | not) then "error: background.art must be a painting (p:tokyonight, p:fjord… see THEMES.md) or one of glow, mesh, aurora, waves, dunes, peaks, stars, rain, sunset, horizon, embers, fog, topo, bauhaus, frost, grid, dots, city" else empty end))
    else "error: background must be theme, plain, wallpaper, an art name or {\"image\": \"file\"}" end) else empty end)
end'

file_of() {
  case "$1" in
    "") echo "$PWD/theme.json" ;;
    */*|*.json) echo "$1" ;;
    *) echo "$D/$1/theme.json" ;;
  esac
}

case "$1" in
  list)
    cur=$(jq -r '.id // ""' "$D/CURRENT.json" 2>/dev/null)
    for d in "$D"/*/; do [ -f "$d/theme.json" ] || continue; id=$(basename "$d")
      mark=" "; [ "custom:$id" = "$cur" ] && mark="*"
      printf '%s %s\t%s\n' "$mark" "$id" "$(jq -r '"\(.name // "") (\(.style // "soft"))"' "$d/theme.json" 2>/dev/null || echo "(not valid JSON)")"; done
    echo "Built-in styles: $(jq -r '[.[].id] | join(", ")' "$D/STYLES.json" 2>/dev/null). Palettes: theme palettes." ;;
  check)
    f=$(file_of "$2"); [ -f "$f" ] || die "There's no $f."
    err=$(jq . "$f" 2>&1 >/dev/null) || die "error: $(basename "$f") isn't valid JSON: $err"
    out=$(jq -r "$CHECK" "$f")
    img=$(jq -r 'if (.background | type) == "object" then .background.image // "" else "" end' "$f")
    if [ -n "$img" ]; then
      case "$img" in /*|*..*) out="$out
error: background.image must be a file name in the theme's folder" ;;
        *) [ -f "$(dirname "$f")/$img" ] || out="$out
error: background.image: there's no $(dirname "$f")/$img" ;; esac
    fi
    out=$(printf '%s\n' "$out" | sed '/^$/d')
    [ -n "$out" ] && printf '%s\n' "$out"
    if printf '%s\n' "$out" | grep -q '^error:'; then echo "Fix the errors above: the launcher ignores those parts." >&2; exit 1; fi
    echo "OK: $(jq -r '.name' "$f") is valid.$( [ -n "$out" ] && echo ' Consider the warnings.') Saved changes show on the phone within two seconds." ;;
  defaults)
    cat <<'EOF'
key          default (from style)   range / values
name         (required)             text
description  ""                     text
style        soft                   soft, oldschool, neon, glass, tiled  (the base: every key below comes from it)
dark         style's                auto, always, never
colors       the user's palette     { "light": {...}, "dark": {...} }  keys: surface text accent glow red orange yellow green cyan blue magenta (#RRGGBB)
corner       style's                0 to 40 (dp)
border       style's                0 to 6 (dp)
borderColor  style's                outline, text, accent, glow, light, #hex
glow         style's                true, false
font         style's                sans, mono, serif
uppercase    style's                true, false
motion       style's                smooth, snappy, pulse, float
gap          style's                0 to 16 (dp)
cardOpacity  style's                0 to 1
frameAll     style's                true, false
clock        style's                thin, mono, glow, light
background   theme                  "theme", "plain", "wallpaper", an art name, {"kind": "theme", "art": "waves"} or {"image": "bg.jpg"}
EOF
    echo; echo "Each built-in style's values: theme styles" ;;
  styles) jq . "$D/STYLES.json" ;;
  palettes) jq -r '.[] | "\(.id)\t\(.name)\t(\(.native // "both"), suits \(.style // "any style"))"' "$D/PALETTES.json" ;;
  palette) [ -n "$2" ] || die "usage: theme palette <id>"; jq --arg id "$2" '.[] | select(.id == $id)' "$D/PALETTES.json" ;;
  current) jq . "$D/CURRENT.json" ;;
  new)
    [ -n "$2" ] || die "usage: theme new <id> [style|palette|current]"
    id=$(printf '%s' "$2" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9\n-' '-'); dir="$D/$id"
    [ -e "$dir/theme.json" ] && die "$dir/theme.json already exists: change it, or pick another id."
    mkdir -p "$dir"
    from=${3:-current}
    if [ "$from" = current ]; then jq --arg n "$id" 'del(.id) | .name = $n' "$D/CURRENT.json"
    elif jq -e --arg s "$from" 'any(.[]; .id == $s)' "$D/STYLES.json" >/dev/null; then
      jq --arg s "$from" --arg n "$id" '.[] | select(.id == $s) | del(.id) | .style = $s | .name = $n' "$D/STYLES.json"
    elif jq -e --arg s "$from" 'any(.[]; .id == $s)' "$D/PALETTES.json" >/dev/null; then
      jq --arg s "$from" --arg n "$id" '(.[] | select(.id == $s)) as $p | {name: $n, style: ($p.style // "soft"), colors: $p.colors}' "$D/PALETTES.json"
    else die "There's no style or palette $from (theme styles, theme palettes)."; fi > "$dir/theme.json"
    echo "$dir/theme.json" ;;
  use)
    [ -n "$2" ] || die "usage: theme use <id>"
    [ -f "$D/$2/theme.json" ] || jq -e --arg s "$2" 'any(.[]; .id == $s)' "$D/STYLES.json" >/dev/null 2>&1 || die "There's no theme $2."
    printf '%s\n' "$2" > "$D/.use"; echo "Hatchlybox switches to $2 within two seconds (while it's open)." ;;
  errors) cat "$D/errors.txt" 2>/dev/null || echo "Hatchlybox hasn't loaded the themes yet." ;;
  *) cat <<'EOF'
usage: theme list                  the themes here (* = in use)
       theme new <id> [from]       start a theme from current (default), a style or a palette
       theme check [id|file]       what the launcher would ignore and why (default ./theme.json)
       theme defaults              every key, its default and range
       theme styles | palettes     the built-in styles; the palettes (theme palette <id> for its colours)
       theme current               the look in use, as a theme.json
       theme use <id>              switch the launcher to a theme or a built-in style
       theme errors                what Hatchlybox ignored when it last loaded the themes
EOF
     exit 1 ;;
esac
