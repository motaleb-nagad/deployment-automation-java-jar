# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
RUN npm run build

# ---- runtime stage ----
FROM nginx:1.27-alpine
# SPA + reverse proxy for /api → backend service.
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# BACKEND_URL is templated into the nginx config at container start.
ENV BACKEND_URL=http://backend:8080
EXPOSE 80
