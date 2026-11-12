# Reverse proxy image ([E0-3]): serves the built SPA and routes /api to the backend. Build context: repo root
#   docker build -f infra/docker/proxy.Dockerfile -t hms-proxy:<git-sha> .
# Dev only for now: plain HTTP, no TLS termination yet (hld.md container view). Login is rate limited (nginx.conf).

FROM node:22-alpine AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Unprivileged variant: runs as uid 101 and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY infra/proxy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
