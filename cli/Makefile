BINARY     := prexorctl
DIST       := dist
VERSION    ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS    := -s -w -X main.version=$(VERSION)
GO_BUILD   := CGO_ENABLED=0 go build -trimpath -ldflags '$(LDFLAGS)'

PLATFORMS  := linux-amd64 linux-arm64 darwin-amd64 darwin-arm64 windows-amd64 windows-arm64

ext         = $(if $(findstring windows,$1),.exe,)
target      = $(DIST)/$(BINARY)-$1$(call ext,$1)
TARGETS    := $(foreach p,$(PLATFORMS),$(call target,$p))

SOURCES    := $(shell find . -name '*.go' -not -path './$(DIST)/*')

.PHONY: build all checksums fmt fmt-check test test-race cover vet lint check clean

build:
	$(GO_BUILD) -o $(BINARY) .

all: $(TARGETS)

$(DIST)/$(BINARY)-%: $(SOURCES) go.mod go.sum
	@mkdir -p $(DIST)
	@os=$(firstword $(subst -, ,$*)); \
	arch=$(word 2,$(subst -, ,$(basename $*))); \
	echo "→ $$os/$$arch"; \
	GOOS=$$os GOARCH=$$arch $(GO_BUILD) -o $@ .

checksums: $(DIST)/checksums.txt
$(DIST)/checksums.txt: $(TARGETS)
	cd $(DIST) && sha256sum $(BINARY)-* > checksums.txt

test:
	go test ./... -count=1

# Race detector — slower but catches concurrency bugs the standard test
# pass misses (HTTP client retries, SSE bus, scaffold watchers).
test-race:
	go test ./... -count=1 -race -timeout 120s

# Coverage profile — emits a single coverage.out you can open with
# `go tool cover -html=coverage.out` for the full annotated source.
cover:
	go test ./... -count=1 -coverprofile=coverage.out -covermode=atomic
	@echo "→ open coverage report: go tool cover -html=coverage.out"

# Format every .go file in place.
fmt:
	go fmt ./...

# CI gate: error if anything would be reformatted by `go fmt`.
fmt-check:
	@diff_files=$$(gofmt -l .); \
	if [ -n "$$diff_files" ]; then \
		echo "→ gofmt would change these files:"; echo "$$diff_files"; exit 1; \
	fi

vet:
	go vet ./...

lint: vet fmt-check

check: vet fmt-check test

clean:
	rm -rf $(DIST) $(BINARY) $(BINARY).exe coverage.out
