#!/usr/bin/env bash
#
# Stops all services started by run-all.sh.
#
set -uo pipefail
cd "$(dirname "$0")"

if [[ -f logs/pids ]]; then
  while read -r pid; do
    if kill "${pid}" 2>/dev/null; then
      echo "stopped pid ${pid}"
    fi
  done < logs/pids
  rm -f logs/pids
else
  echo "No logs/pids file found; nothing to stop (or services were run from IntelliJ)."
fi
