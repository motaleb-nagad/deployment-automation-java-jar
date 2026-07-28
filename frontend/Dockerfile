# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
RUN npm run build

# ---- runtime stage ----
# Unprivileged nginx: runs as non-root (uid 101), listens on 8080. Stable 1.28 line on a
# current Alpine base; the floating tag picks up the latest patched Alpine on every rebuild.
FROM nginxinc/nginx-unprivileged:1.28-alpine
# SPA + reverse proxy for /api → backend service.
COPY --chown=nginx:nginx nginx.conf /etc/nginx/templates/default.conf.template
COPY --chown=nginx:nginx --from=build /app/dist /usr/share/nginx/html
# BACKEND_URL is templated into the nginx config at container start.
ENV BACKEND_URL=http://backend:8080
EXPOSE 8080
