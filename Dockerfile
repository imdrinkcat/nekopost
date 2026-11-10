# syntax=docker/dockerfile:1

# Build on the runner's own architecture and cross-compile: the program is
# pure Go (CGO_ENABLED=0), so no emulation is needed for other platforms.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG TARGETOS TARGETARCH
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath \
      -ldflags "-s -w -X main.version=$(cat VERSION)" -o /out/nekopost ./cmd/nekopost \
 && mkdir /out/data

# Static binary on distroless: no shell, no package manager, runs as
# uid/gid 65532 (nonroot). CA certificates and tzdata are included.
FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/nekopost /usr/local/bin/nekopost
COPY --from=build --chown=65532:65532 --chmod=0700 /out/data /data
# Unprivileged ports inside the container; publish them as 80/443.
ENV NEKOPOST_DATA_DIR=/data \
    NEKOPOST_HTTP_ADDR=:8080 \
    NEKOPOST_HTTPS_ADDR=:8443 \
    GOMEMLIMIT=64MiB
EXPOSE 8080 8443
# The probe uses plain HTTP on 8080 in both TLS modes (see "healthcheck").
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD ["nekopost", "healthcheck"]
ENTRYPOINT ["nekopost"]
CMD ["serve"]
