# NGINX (VM-EDGE-01) : point d'entrée unique + application web du médecin (React).
# L'image contient le code (l'application compilée) ; la configuration NGINX (nginx.conf,
# security-headers.conf) est montée par docker-compose.yml.
# Contexte de build : le dossier tier2-dmz/   ->  depuis Services/ : docker compose --profile build build nginx

# 1) Compilation de l'application React (Vite) -> dist/
FROM node:24-alpine AS front
WORKDIR /app
COPY frontend-medecin/package.json frontend-medecin/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend-medecin/ ./
RUN npm run build

# 2) NGINX (branche stable) + fichiers de l'application
FROM nginx:1.28-alpine
RUN rm -f /etc/nginx/conf.d/default.conf
COPY --from=front /app/dist /usr/share/nginx/html
EXPOSE 443 8443 8883
