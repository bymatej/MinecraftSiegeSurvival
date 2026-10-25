#!/usr/bin/env bash
# Talk to the console of the local Paper server started by local-server.sh.
#
#   scripts/console.sh                  attach to the console (Ctrl+A D detaches)
#   scripts/console.sh siege status     run one command and show the log tail
#
# The server runs in a detached screen session, so the console stays reachable
# from any terminal without the server being a child of that terminal.

set -euo pipefail

SESSION="${MSS_SESSION:-mss-paper}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_FILE="$REPO_ROOT/local-server/server/logs/latest.log"

if ! command -v screen >/dev/null 2>&1; then
  echo "screen is not installed" >&2
  exit 1
fi

if ! screen -ls | grep -q "\.${SESSION}[[:space:]]"; then
  echo "No console session '$SESSION' is running." >&2
  echo "Start the server first: scripts/local-server.sh --version 26.2" >&2
  exit 1
fi

if [[ $# -eq 0 ]]; then
  echo "Attaching to '$SESSION' - detach with Ctrl+A D"
  exec screen -r "$SESSION"
fi

command="$*"
screen -S "$SESSION" -p 0 -X stuff "$command$(printf '\r')"
sleep 1
[[ -f "$LOG_FILE" ]] && tail -n 8 "$LOG_FILE"
