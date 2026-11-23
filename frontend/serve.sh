#!/usr/bin/env bash
#
# Serves the frontend on http://localhost:3000 for the IDE workflow, where the services run
# from IntelliJ or ./run-all.sh rather than in Docker.
#
# Why a server at all, rather than opening index.html?
#   The app is built from ES modules (`import` / `export`), and browsers refuse to load modules
#   over file:// for security reasons. Any HTTP server fixes it — this uses the Python that
#   already ships with macOS, so there is nothing to install.
#
# In this mode the browser calls the gateway on :8080 directly, which IS cross-origin, so it
# relies on the CORS settings in api-gateway's application.yml. Under `docker compose up` nginx
# proxies /api instead and none of that applies.
#
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-3000}"

echo "Frontend  ->  http://localhost:${PORT}"
echo "API       ->  http://localhost:8080  (make sure the gateway is running)"
echo
echo "Press Ctrl+C to stop."

exec python3 -m http.server "${PORT}"
