# syntax=docker/dockerfile:1
#
# One stage, because there is nothing to build. The files written are the files the browser runs.
# (Compare with the services' Dockerfiles, which need a Maven stage to produce a jar first.)
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY index.html styles.css /usr/share/nginx/html/
COPY js/ /usr/share/nginx/html/js/

# Point the app at the nginx proxy instead of a hard-coded port. Same source files, different
# runtime configuration — the thing a compiled-in environment variable can't give you.
RUN echo 'window.__API_BASE__ = "";' > /usr/share/nginx/html/env.js

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD wget -qO- http://localhost/ >/dev/null 2>&1 || exit 1
