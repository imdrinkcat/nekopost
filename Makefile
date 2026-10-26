VERSION := $(shell cat VERSION)
LDFLAGS := -s -w -X main.version=$(VERSION)

.PHONY: build test race vet install logs

build:
	CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)" -o bin/nekopost ./cmd/nekopost

test:
	go test ./...

race:
	go test -race -count=1 ./...

vet:
	gofmt -l . && go vet ./...

# Build, install/upgrade and restart the systemd service (run as root).
install:
	./deploy/install.sh

logs:
	journalctl -u nekopost -f
