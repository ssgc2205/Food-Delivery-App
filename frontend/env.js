/*
 * Where the API lives. Overwritten inside the Docker image (see Dockerfile).
 *
 * ""                       -> same origin; nginx proxies /api to the gateway (docker compose)
 * "http://localhost:8080"  -> talk to the gateway directly (services running in an IDE)
 *
 * The empty-string case is why this file exists at all: with a build-tool frontend the API URL
 * gets compiled into the bundle, so you need a separate build per environment. A runtime file
 * means one set of files runs everywhere.
 */
window.__API_BASE__ = "http://localhost:8080";
