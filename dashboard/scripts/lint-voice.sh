#!/usr/bin/env bash
# Voice/copy lint — design system rules:
#   1. Sentence case in dashboard chrome (h1, h2, CardTitle).
#   2. No emoji in product UI .vue files.
# Non-zero exit on any violation. Run via `pnpm lint:voice`.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"

if ! command -v rg >/dev/null 2>&1; then
  echo "ripgrep (rg) is required for voice lint" >&2
  exit 2
fi

EXIT=0

echo "▶ Title Case headings (sentence case required)"
HITS=$(rg -n --no-heading -P '<(h1|h2|CardTitle)[^>]*>\s*([A-Z][a-z]+(\s+[A-Z][a-z]+)+)' "$APP" || true)
if [ -n "$HITS" ]; then
  echo "✗ Title Case found in h1/h2/CardTitle — use sentence case:"
  echo "$HITS" | sed 's/^/   /'
  EXIT=1
else
  echo "✓ none"
fi

echo
echo "▶ Emoji in product UI"
# Emoji ranges: BMP supplementary symbols + flags. Narrowed to avoid catching
# the design system's prescribed CLI glyphs (✓ ✗ ● ○ → ▶ │ └ ├ … ⌘ ↵).
HITS=$(rg -n --no-heading -P '[\x{1F300}-\x{1FAFF}\x{1F1E6}-\x{1F1FF}]|[\x{2700}-\x{2712}\x{2714}-\x{2716}\x{2718}-\x{27BF}]' "$APP" --type-add 'vue:*.vue' --type vue --type ts || true)
if [ -n "$HITS" ]; then
  echo "✗ Emoji found in product UI:"
  echo "$HITS" | sed 's/^/   /'
  EXIT=1
else
  echo "✓ none"
fi

echo
if [ "$EXIT" -eq 0 ]; then
  echo "✓ voice lint passed"
else
  echo "✗ voice lint failed — fix the items above or update scripts/lint-voice.sh allowlist."
fi
exit $EXIT
