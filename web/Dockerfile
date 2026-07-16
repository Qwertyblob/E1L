# Build context is the REPO ROOT (see docker-compose.yml: context: .).
# ---- build the React static bundle ----
FROM node:22-alpine AS build
WORKDIR /app
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
# VITE_API_URL intentionally UNSET -> API_BASE_URL falls back to '' -> relative /api (same-origin).
# Vite emits only an external module <script src>; combined with vite.config.js's
# build.modulePreload.polyfill=false it produces no inline <script>, which the strict
# script-src 'self' CSP in nginx/nginx.conf requires (no 'unsafe-inline').
RUN npm run build

# ---- serve via nginx ----
FROM nginx:alpine
# Replace the default server block with ours (realip + XFF overwrite + SPA + /api proxy).
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/nginx.conf /etc/nginx/conf.d/app.conf
# Shared /api proxy snippet included by the per-route location blocks in app.conf. Not in
# conf.d/ (those load at http scope); included by absolute path from inside each location.
COPY nginx/api_proxy.conf /etc/nginx/api_proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
