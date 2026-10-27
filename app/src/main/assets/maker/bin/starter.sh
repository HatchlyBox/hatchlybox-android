# starter_files <project>: the showcase in a scaffolded project: Neon Run (starter/MainActivity.kt) with its own
# icon, colours, name and chiptune (starter/res).
starter_files() {
  cp "$DATA/starter/MainActivity.kt" "$1/app/src/main/java/${ID_PREFIX//.//}/hello_world/MainActivity.kt" &&
  cp -R "$DATA/starter/res/." "$1/app/src/main/res/"
}

# starter_refresh: a Hello World from before the showcase (the plain greeting, or the one wearing the brand's mark),
# untouched since, becomes the showcase, saved in its history; it shows as needing a build until it's built again.
STARTER_OLD_SHA=1d6d31e3bd0ed112a29442706d088fcca6016415c6794dd599ee6acee369edd1
starter_refresh() {
  local d=$APPS/hello-world f
  f=$d/app/src/main/java/${ID_PREFIX//.//}/hello_world/MainActivity.kt
  jq -e '.starter == true' "$d/.hatchlybox.json" >/dev/null 2>&1 && [ -f "$f" ] || return 0
  [ "$(sha256sum "$f" | cut -c1-64)" = "$STARTER_OLD_SHA" ] || return 0
  starter_files "$d" && save hello-world "Hello World is Neon Run, the showcase" >/dev/null 2>&1
  meta hello-world 'del(.icon)'
}

# One-time onboarding project; never copy a user's projects or signing material.
starter_app() {
  local marker=$DATA/.starter-v1 slug=hello-world d=$APPS/hello-world LOG=$SLOG
  [ ! -e "$marker" ] || return 0
  # A prior partial attempt may be retried, but never adopt or overwrite another project.
  if [ -L "$d" ]; then
    touch "$marker"; return 0
  elif [ -d "$d" ]; then
    jq -e '.starter == true' "$d/.hatchlybox.json" >/dev/null 2>&1 || { touch "$marker"; return 0; }
  else
    local entry
    for entry in "$APPS"/* "$APPS"/.[!.]* "$APPS"/..?*; do
      [ ! -e "$entry" ] || { touch "$marker"; return 0; }
    done
    local stage
    stage=$(mktemp -d "$CACHE/.starter.XXXX") || return 1
    (
      trap 'rm -rf "$stage"' EXIT
      local APPS=$stage
      list_apps() { :; }
      scaffold "$slug" &&
      starter_files "$APPS/$slug" &&
      meta "$slug" '.starter=true | .label="Hello World"' &&
      save "$slug" 'Add Hello World starter' &&
      mv -T "$APPS/$slug" "$d"
    ) || return 1
  fi
  do_build "$slug" || { meta "$slug" '.status="failed" | .outcomes.build="failed"'; return 1; }
  meta "$slug" '.status="ready"' || return 1
  touch "$marker"
}
