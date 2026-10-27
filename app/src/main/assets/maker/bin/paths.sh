# paths.sh: where Hatchlybox Engine keeps things, decided here for every script (sourced): DATA (the engine, its
# settings and keys), CACHE (state and logs), APPS (Android versions), PORTS (versions for other systems), BACKUPS.
# The front end's HATCHLYBOX_* win. Otherwise the system's own places: XDG folders on Linux, ~/Library on macOS;
# creations in ~/Hatchlybox.
if [ "$(uname -s)" = Darwin ]; then
  DATA="$HOME/Library/Application Support/Hatchlybox" CACHE="$HOME/Library/Caches/Hatchlybox"
  APPS=$HOME/Hatchlybox/apps PORTS=$HOME/Hatchlybox/ports BACKUPS=$HOME/Hatchlybox/backups
else
  DATA=${XDG_DATA_HOME:-$HOME/.local/share}/hatchlybox CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/hatchlybox
  APPS=$HOME/Hatchlybox/apps PORTS=$HOME/Hatchlybox/ports BACKUPS=$HOME/Hatchlybox/backups
fi
DATA=${HATCHLYBOX_HOME:-$DATA} CACHE=${HATCHLYBOX_CACHE:-$CACHE} APPS=${HATCHLYBOX_APPS:-$APPS} PORTS=${HATCHLYBOX_PORTS:-$PORTS} BACKUPS=${HATCHLYBOX_BACKUPS:-$BACKUPS}
