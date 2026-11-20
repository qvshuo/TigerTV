FROM node:24-alpine AS build
WORKDIR /source
COPY web/package*.json web/
RUN npm --prefix web ci
COPY web/ web/
COPY shared/ shared/
COPY skills/references/LunaTV-config.json skills/references/
ENV VITE_TIGERTV_GATEWAY=./api/metadata/
RUN npm --prefix web run build

FROM node:24-alpine
WORKDIR /app
COPY --from=build /source/web/dist/ web/
COPY skills/references/LunaTV-config.json sources.json
COPY gateway/server.mjs .
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
