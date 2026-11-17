# InfraHub console - multi-stage build
# Build : Node 22 (Vite 6 builds with vue-tsc --noEmit), npm ci + npm run build
# Run   : nginx serves dist/ with SPA fallback; /api proxies to the backend
#         container. No host URLs or secrets are baked into this image.

# ---------- Stage 1: Build ----------
FROM node:22-alpine AS builder

WORKDIR /build/infrahub-console

# Lockfile first so `npm ci` layers are cached across source rebuilds.
COPY infrahub-console/package.json infrahub-console/package-lock.json ./
RUN npm ci

# Then the full source tree.
COPY infrahub-console ./
RUN npm run build

# ---------- Stage 2: Run ----------
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.licenses="MIT"

COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/infrahub-console/dist /usr/share/nginx/html
COPY LICENSE /usr/share/licenses/infrahub/LICENSE

EXPOSE 80

# busybox wget shipped with nginx:alpine serves as a stable health probe.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q --spider http://127.0.0.1/ || exit 1