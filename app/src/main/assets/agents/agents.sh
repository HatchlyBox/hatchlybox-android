#!/bin/sh
# The three AI helpers, installed or brought up to their latest versions. Each is downloaded only when
# it's missing or behind, so running this often is cheap. "✓ updated <name>" lines tell Hatchlybox what
# changed, so terminal widgets running that helper restart on the new version.
set +e
ver() { timeout 20 "$1" --version 2>/dev/null | head -1 | grep -o '[0-9][0-9.]*[0-9]' | head -1; }

for tool in opencode claude codex; do
    if command -v "$tool" >/dev/null 2>&1; then
        python3 /root/.local/bin/helper-backup snapshot "$tool" || exit 1
    fi
done

# OpenCode 2 (opencode.ai/install only ever installs OpenCode 1).
have=$(ver opencode)
want=$(curl -fsSL --max-time 20 https://opencode.ai/update/api/latest/cli/npm 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
if [ -z "$have" ] || { [ -n "$want" ] && [ "$want" != "$have" ]; }; then
    echo "› Installing OpenCode ${want:-latest}"
    if curl -fsSL https://opencode.ai/v2/install | bash -s -- --no-modify-path; then
        [ "$(ver opencode)" != "$have" ] && echo "✓ updated opencode $(ver opencode)"
    elif [ -z "$have" ]; then
        echo '! OpenCode 2 failed to install, trying OpenCode 1'
        curl -fsSL https://opencode.ai/install | bash -s -- --no-modify-path || echo '! OpenCode failed to install'
    else
        echo "! OpenCode update failed, keeping $have"
    fi
fi

# Claude Code updates itself on request (Hatchlybox turns its automatic updates off).
have=$(ver claude)
if [ -z "$have" ]; then
    echo '› Installing Claude Code'
    curl -fsSL https://claude.ai/install.sh | bash || echo '! Claude Code failed to install'
else
    env -u DISABLE_AUTOUPDATER claude update >/dev/null 2>&1 || echo '! Claude Code update failed'
fi
now=$(ver claude); [ -n "$have" ] && [ -n "$now" ] && [ "$now" != "$have" ] && echo "✓ updated claude $now"

# Codex from npm.
have=$(ver codex)
want=$(npm view @openai/codex version 2>/dev/null)
if [ -z "$have" ] || { [ -n "$want" ] && [ "$want" != "$have" ]; }; then
    echo "› Installing Codex ${want:-latest}"
    npm install -g --no-fund --no-audit --loglevel=error @openai/codex@latest || echo '! Codex failed to install'
    [ -n "$have" ] && [ "$(ver codex)" != "$have" ] && echo "✓ updated codex $(ver codex)"
fi

missing=0
for t in claude codex opencode node; do
    version=$(ver "$t")
    if command -v "$t" >/dev/null && [ -n "$version" ]; then echo "✓ $t ready $version"
    else
        echo "! $t health check failed"
        if [ "$t" != node ]; then python3 /root/.local/bin/helper-backup restore "$t" || true; fi
        missing=1
    fi
done
mkdir -p /root/.cache/hatchlybox
jq -n --arg claude "$(ver claude)" --arg codex "$(ver codex)" --arg opencode "$(ver opencode)" --arg node "$(ver node)" '{claude:$claude,codex:$codex,opencode:$opencode,node:$node}' > /root/.cache/hatchlybox/tool-versions.json.new && mv /root/.cache/hatchlybox/tool-versions.json.new /root/.cache/hatchlybox/tool-versions.json
[ "$missing" -eq 0 ]
