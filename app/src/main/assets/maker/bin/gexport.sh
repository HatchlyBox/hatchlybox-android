# gexport.sh: a Godot game on every system with no agent, sourced by hatchlybox. The game's Godot project (the Android
# version's godot/) is exported with Godot's own templates for the web, Linux, macOS and Windows, into that system's
# version ($PORTS/<slug>/<system>/export), with an install.sh by the contract of here.sh, so a computer installs it
# with one tap. Each system's renderer is set in the project (godot_renderers): phones and the web keep the light
# Compatibility renderer the game was made with, computers get Forward+ (Godot falls back to OpenGL where there's no
# Vulkan), so the same game looks its best where it runs. hatchlybox make <slug> web|linux|macos|windows uses this
# for a Godot game; a change request still goes to the agent.

GODOT_EXPORTS="web linux macos windows"

# godot_renderers <project dir>: Forward+ for computers, the game's own renderer kept for phones (.mobile) and the
# web (.web: Compatibility, the only one browsers run). Once: a project that already says .mobile is left as it is.
godot_renderers() {
  local f=$1/project.godot base
  [ -f "$f" ] || return 1
  grep -q '^renderer/rendering_method.mobile=' "$f" && return 0
  base=$(sed -n 's/^renderer\/rendering_method="\(.*\)"$/\1/p' "$f" | head -1); base=${base:-gl_compatibility}
  [ "$base" = forward_plus ] && base=mobile
  if grep -q '^renderer/rendering_method=' "$f"; then
    sed -i 's/^renderer\/rendering_method=.*/renderer\/rendering_method="forward_plus"/' "$f"
  elif grep -q '^\[rendering\]' "$f"; then
    sed -i '/^\[rendering\]/a renderer/rendering_method="forward_plus"' "$f"
  else
    printf '\n[rendering]\n\nrenderer/rendering_method="forward_plus"\n' >> "$f"
  fi
  sed -i "/^renderer\/rendering_method=\"forward_plus\"/a renderer/rendering_method.mobile=\"$base\"\nrenderer/rendering_method.web=\"gl_compatibility\"" "$f"
}

# here_game_system: the system a game is exported for on this computer.
here_game_system() { case $HOST in macos) echo macos ;; windows) echo windows ;; *) echo linux ;; esac; }

# godot_export_arch: the Linux build's processor: this computer's when it's an ARM Linux computer, else x86_64 (PCs).
godot_export_arch() { if [ "$HOST" = linux ] && [ "$(android_arch)" = arm64 ]; then echo arm64; else echo x86_64; fi; }

# godot_template <system>: the export template file(s) it needs (fetched once from the 1.3 GB archive by range).
godot_template() {
  case $1 in
    web) echo web_nothreads_release.zip ;;
    linux) echo "linux_release.$(godot_export_arch)" ;;
    macos) echo macos.zip ;;
    windows) echo windows_release_x86_64.exe ;;
  esac
}

# godot_preset_for <project dir> <system> <out file> <bundle id>: an export preset "Hatchlybox <system>" in the project.
godot_preset_for() {
  local dir=$1 sys=$2 out=$3 id=$4 f=$1/export_presets.cfg name n platform opts
  name="Hatchlybox $sys"
  case $sys in
    web) platform=Web; opts='variant/thread_support=false\nvariant/extensions_support=false\nhtml/canvas_resize_policy=2\nhtml/focus_canvas_on_start=true' ;;
    linux) platform=Linux; opts="binary_format/architecture=\"$(godot_export_arch)\"\nbinary_format/embed_pck=true" ;;
    macos) platform=macOS; opts="binary_format/architecture=\"universal\"\napplication/bundle_identifier=\"$id\"\ncodesign/codesign=1\nnotarization/notarization=0" ;;
    windows) platform="Windows Desktop"; opts='binary_format/architecture="x86_64"\nbinary_format/embed_pck=true' ;;
  esac
  # Made again each time, so a moved output or a changed option never lingers.
  if [ -f "$f" ] && grep -q "^name=\"$name\"" "$f"; then
    python3 -I - "$f" "$name" <<'PY'
import re, sys
path, name = sys.argv[1], sys.argv[2]
text = open(path).read()
blocks = re.split(r'(?m)^(?=\[preset\.\d+\]\s*$)', text)
keep = [b for b in blocks if f'\nname="{name}"\n' not in '\n' + b]
# Presets are numbered in order: renumber what's left.
out, n = [], 0
for b in keep:
    m = re.match(r'\[preset\.(\d+)\]', b)
    if m:
        old = m.group(1)
        b = re.sub(rf'(?m)^\[preset\.{old}\]', f'[preset.{n}]', b)
        b = re.sub(rf'(?m)^\[preset\.{old}\.options\]', f'[preset.{n}.options]', b)
        n += 1
    out.append(b)
open(path, 'w').write(''.join(out).rstrip('\n') + '\n')
PY
  fi
  n=$(grep -c '^\[preset\.[0-9]*\]$' "$f" 2>/dev/null); n=${n:-0}
  printf '%s\n' '' "[preset.$n]" '' "name=\"$name\"" "platform=\"$platform\"" 'runnable=false' 'export_filter="all_resources"' \
    'include_filter=""' 'exclude_filter=""' "export_path=\"$out\"" '' "[preset.$n.options]" '' >> "$f"
  printf "$opts\n" >> "$f"
}

# godot_export_files <slug> <system> <export dir>: install.sh (here.sh's contract), hatchlybox.json and RUN.md beside the build.
godot_export_files() {
  local slug=$1 sys=$2 out=$3 label bin
  label=$(creation_label "$slug")
  case $sys in
    linux)
      bin=$(cd "$out" && ls | grep -E "\.(x86_64|arm64)$" | head -1)
      cat > "$out/install.sh" <<EOF
#!/usr/bin/env bash
# Made by Hatchlybox from the game's Godot project: installs $label for this user (see RUN.md).
set -e
d="\$HATCHLYBOX_SHARE/hatchlybox-games/$slug"
mkdir -p "\$d" "\$HATCHLYBOX_BIN" "\$HATCHLYBOX_SHARE/applications" "\$HATCHLYBOX_SHARE/icons/hicolor/512x512/apps"
cp "$bin" "\$d/$slug" && chmod +x "\$d/$slug"
ln -sfn "\$d/$slug" "\$HATCHLYBOX_BIN/$slug"
[ ! -f icon.png ] || cp icon.png "\$HATCHLYBOX_SHARE/icons/hicolor/512x512/apps/hatchlybox-$slug.png"
printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=$label' "Exec=\$d/$slug" 'Icon=hatchlybox-$slug' 'Categories=Game;' > "\$HATCHLYBOX_SHARE/applications/hatchlybox-$slug.desktop"
printf '%s\n' "\$d" "\$HATCHLYBOX_BIN/$slug" "\$HATCHLYBOX_SHARE/applications/hatchlybox-$slug.desktop" "\$HATCHLYBOX_SHARE/icons/hicolor/512x512/apps/hatchlybox-$slug.png" >> "\$HATCHLYBOX_MANIFEST"
EOF
      jq -nc --arg r "\"\$HOME/.local/bin/$slug\"" '{run: $r}' > "$out/hatchlybox.json"
      printf '# %s for Linux\n\nInstall with Hatchlybox (one tap), or run `bash install.sh` with HATCHLYBOX_BIN, HATCHLYBOX_SHARE and HATCHLYBOX_MANIFEST set; or just run `./%s`. Built for %s processors.\n' "$label" "$bin" "$(godot_export_arch)" > "$out/RUN.md" ;;
    macos)
      cat > "$out/install.sh" <<EOF
#!/usr/bin/env bash
# Made by Hatchlybox from the game's Godot project: puts $label in your Applications folder (see RUN.md).
set -e
t=\$(mktemp -d); unzip -q game.zip -d "\$t"
app=\$(cd "\$t" && ls -d *.app | head -1)
mkdir -p "\$HATCHLYBOX_APPS_DIR"; rm -rf "\$HATCHLYBOX_APPS_DIR/\$app"; mv "\$t/\$app" "\$HATCHLYBOX_APPS_DIR/"; rm -rf "\$t"
echo "\$HATCHLYBOX_APPS_DIR/\$app" >> "\$HATCHLYBOX_MANIFEST"
# Its name, for opening it (this runs in the copy Hatchlybox keeps).
echo "\$app" > app-name.txt
EOF
      jq -nc '{run: "open \"$HATCHLYBOX_APPS_DIR/$(cat app-name.txt)\""}' > "$out/hatchlybox.json"
      printf '# %s for macOS\n\nInstall with Hatchlybox (one tap), or unzip game.zip and move the app to Applications. It is signed ad hoc, not notarized: the first time, open it with right-click › Open.\n' "$label" > "$out/RUN.md" ;;
    windows)
      bin=$(cd "$out" && ls *.exe | head -1)
      cat > "$out/install.sh" <<EOF
#!/usr/bin/env bash
# Made by Hatchlybox from the game's Godot project: installs $label for this Windows user, from WSL (see RUN.md).
set -e
d="\$HATCHLYBOX_WIN_APPS/$slug"
mkdir -p "\$d"; cp "$bin" "\$d/"
echo "\$d" >> "\$HATCHLYBOX_MANIFEST"
"\$HATCHLYBOX_WIN_SHORTCUT" "$label" "\$d/$bin"
EOF
      jq -nc --arg r "\"\$HATCHLYBOX_WIN_APPS/$slug/$bin\"" '{run: $r}' > "$out/hatchlybox.json"
      printf '# %s for Windows\n\nInstall with Hatchlybox on Windows (one tap: a Start menu entry), or copy %s to the Windows computer and run it (one file, nothing to install). Windows may warn that it is from an unknown publisher: More info › Run anyway.\n' "$label" "$(cd "$out" && ls *.exe | head -1)" > "$out/RUN.md" ;;
    web)
      printf '# %s for the web\n\nThe whole folder is the game: put it on any web host, or open it from Hatchlybox. Browsers load it over http(s), not from a file.\n' "$label" > "$out/RUN.md" ;;
  esac
}

# godot_export_work <slug> <system>: the job: the template, the renderers, the preset, the export, the files.
godot_export_work() {
  local slug=$1 sys=$2 dir=$APPS/$1 port=$PORTS/$1/$2 t out file name label err=$CACHE/build-err.txt
  label=$(creation_label "$slug")
  job building "exporting the game for $(system_name "$sys")"
  : > "$err"
  godot_ok || install_godot >> "$LOG" 2>&1 || { job failed "Godot isn't installed and couldn't be installed: see the log"; return 1; }
  t=$(godot_template "$sys")
  if [ ! -f "$GODOT_TEMPLATES/$t" ]; then
    job building "fetching Godot's $(system_name "$sys") template (once)"
    python3 -I "$DATA/bin/zip-pick" "$GODOT_REL/Godot_v$GODOT_VER-stable_export_templates.tpz" "$GODOT_TEMPLATES" "templates/$t" >> "$LOG" 2>&1 \
      && [ -f "$GODOT_TEMPLATES/$t" ] || { job failed "couldn't fetch Godot's $(system_name "$sys") template: see the log"; return 1; }
    [ "$t" = macos.zip ] || [ "$t" = web_nothreads_release.zip ] || chmod +x "$GODOT_TEMPLATES/$t"
  fi
  godot_renderers "$dir/godot"
  name=$(sed 's/[^A-Za-z0-9 ._-]//g' <<< "$label"); name=${name:-$slug}
  case $sys in
    web) file=index.html ;; linux) file="$slug.$(godot_export_arch)" ;; macos) file=game.zip ;; windows) file="$name.exe" ;;
  esac
  out=$(mktemp -d "$CACHE/.export.XXXX")
  godot_preset_for "$dir/godot" "$sys" "$out/$file" "$(jq -r .pkg "$dir/.hatchlybox.json")"
  ( isolated build "$dir" timeout 20m "$DATA/bin/godot" --headless --path "$dir/godot" --import ) >> "$LOG" 2>&1
  ( isolated build "$dir" timeout 20m "$DATA/bin/godot" --headless --path "$dir/godot" --export-release "Hatchlybox $sys" "$out/$file" ) > "$CACHE/godot-out.txt" 2>&1
  cat "$CACHE/godot-out.txt" >> "$LOG"
  if [ ! -s "$out/$file" ]; then
    grep -E 'ERROR|rror:' -A2 "$CACHE/godot-out.txt" | head -30 > "$err"
    rm -rf "$out"; job failed "the $(system_name "$sys") export failed: see the log"; return 1
  fi
  [ "$sys" = web ] || godot_icon "$slug" "$out/icon.png" >> "$LOG" 2>&1 || true
  godot_export_files "$slug" "$sys" "$out"
  save "$slug" "the game's renderers and $(system_name "$sys") export preset" || true
  mkdir -p "$port"
  rm -rf "$port/export" && mv "$out" "$port/export"
  local content; content=$(version_time "$slug" android)
  jq -nc --arg s "$sys" --arg l "$label" --argjson t "$(now)" --argjson c "${content:-$(now)}" \
    '{target: $s, label: $l, created: $t, changed: $t, content: $c, from: "android", made: "godot-export"}' \
    | jq -c --slurpfile old <(cat "$port/.port.json" 2>/dev/null || echo '{}') '($old[0] // {}) + . | .created = (($old[0] // {}).created // .created)' > "$port/.port.json.tmp" \
    && mv "$port/.port.json.tmp" "$port/.port.json"
  job done "exported for $(system_name "$sys"): ready to $([[ " $INSTALLS " == *" $sys "* ]] && echo install || echo export)"
}
