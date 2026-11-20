FROM node:24-alpine AS build
WORKDIR /source
COPY web/package*.json web/
RUN npm --prefix web ci
COPY web/ web/
COPY shared/ shared/
COPY skills/references/LunaTV-config.json skills/references/
ENV VITE_TIGERTV_GATEWAY=./api/metadata/
RUN npm --prefix web run build

FROM golang:1.27.4-alpine AS go-build
WORKDIR /source
COPY gateway/go.mod gateway/main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /tigertv-gateway .

FROM scratch
WORKDIR /app
COPY --from=go-build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=go-build /tigertv-gateway /tigertv-gateway
COPY --from=build /source/web/dist/ web/
COPY skills/references/LunaTV-config.json sources.json
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/tigertv-gateway"]
