FROM oven/bun:1.4.4-alpine AS build
WORKDIR /app/web
COPY web/package.json web/bun.lock ./
RUN bun install --frozen-lockfile
COPY web/ ./
COPY shared/ /app/shared/
RUN bun run build

FROM oven/bun:1.4.4-alpine
WORKDIR /app/web
COPY --from=build /app/web/dist/ dist/
COPY web/server/ server/
COPY web/common/ common/
COPY shared/network-timeouts.json shared/douban-categories.json shared/LunaTV-config.json /app/shared/
USER bun
EXPOSE 8080
CMD ["bun", "server/index.ts"]
