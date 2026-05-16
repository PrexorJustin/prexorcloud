#!/usr/bin/env bash
# Build the Vite-based installer wizard and copy the resulting singlefile
# index.html into cli/internal/setupweb/static/ so Go's //go:embed picks it up.
#
# Idempotent. Re-running rebuilds and overwrites the embedded HTML. The output
# is one self-contained HTML document — no external CSS or JS chunks — so the
# Go server keeps shipping a single static asset.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALLER="$ROOT/installer"
EMBED_DEST="$ROOT/cli/internal/setupweb/static/index.html"

if [[ ! -d "$INSTALLER" ]]; then
  echo "error: $INSTALLER not found" >&2
  exit 1
fi

pnpm -C "$INSTALLER" install --frozen-lockfile
pnpm -C "$INSTALLER" build

if [[ ! -f "$INSTALLER/dist/index.html" ]]; then
  echo "error: build did not produce $INSTALLER/dist/index.html" >&2
  exit 1
fi

cp "$INSTALLER/dist/index.html" "$EMBED_DEST"
echo "wrote $EMBED_DEST ($(wc -c < "$EMBED_DEST") bytes)"
