# Build context is the REPO ROOT (see docker-compose.yml: context: .).
# ---- build the React static bundle ----
FROM node:22-alpine AS build
WORKDIR /app
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
# REACT_APP_API_URL intentionally UNSET -> API_BASE_URL falls back to '' -> relative /api (same-origin).
# INLINE_RUNTIME_CHUNK=false keeps index.html free of inline <script>, which the strict
# script-src 'self' CSP in nginx/nginx.conf requires (no 'unsafe-inline').
ENV INLINE_RUNTIME_CHUNK=false
RUN npm run build

# ---- serve via nginx ----
FROM nginx:alpine
# Replace the default server block with ours (realip + XFF overwrite + SPA + /api proxy).
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/nginx.conf /etc/nginx/conf.d/app.conf
COPY --from=build /app/build /usr/share/nginx/html
EXPOSE 80
