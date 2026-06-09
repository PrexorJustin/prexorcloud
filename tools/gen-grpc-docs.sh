#!/usr/bin/env bash
# tools/gen-grpc-docs.sh — generate Markdown documentation for every
# `.proto` file under `java/cloud-protocol/src/main/proto/prexorcloud/`.
#
# Output goes to `docs/public/en/internals/protocol/_generated/`. The
# `_generated` segment is intentionally underscore-prefixed so the Astro
# content collection in `website/src/content.config.ts` skips it; the
# hand-curated wrapping pages under `docs/public/en/internals/protocol/`
# stay the canonical entry points and link to specific
# `_generated/<service>.md` files when the deeper reference is useful.
#
# This is the drift-detector / underlying truth: when the proto files
# move, regenerate and diff against the curated pages.
#
# Tooling — picks the first available, in this order:
#   1. `protoc` + a local `protoc-gen-doc` binary on $PATH
#   2. Docker image `pseudomuto/protoc-gen-doc` (no host install required)
#
# Pinned to protoc-gen-doc 1.5.1 for reproducibility (WEBSITE_PLAN risk #3).
#
# Usage:
#   tools/gen-grpc-docs.sh [--docker]
#
# Run from the repo root.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="${ROOT}/java/cloud-protocol/src/main/proto"
OUT_DIR="${ROOT}/docs/public/en/internals/protocol/_generated"
DOC_VERSION="1.5.1"

force_docker=0
[[ "${1-}" == "--docker" ]] && force_docker=1

mkdir -p "${OUT_DIR}"
# Wipe the directory first so removed services don't leave stale files.
find "${OUT_DIR}" -mindepth 1 -delete

# `protoc-gen-doc`'s `--doc_opt` template `markdown,FILENAME` writes a single
# bundle file. To get one MD per service we run protoc once per .proto file.
gen_one_local() {
  local proto_file="$1"
  local out_name="$2"
  ( cd "${PROTO_DIR}" && \
    protoc \
      --plugin=protoc-gen-doc="$(command -v protoc-gen-doc)" \
      --doc_out="${OUT_DIR}" \
      --doc_opt="markdown,${out_name}" \
      -I . \
      "${proto_file}"
  )
}

gen_one_docker() {
  local proto_file="$1"
  local out_name="$2"
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -v "${PROTO_DIR}:/protos:ro" \
    -v "${OUT_DIR}:/out" \
    "pseudomuto/protoc-gen-doc:${DOC_VERSION}" \
    --doc_opt="markdown,${out_name}" \
    "${proto_file}"
}

mode=""
if [[ ${force_docker} -eq 0 ]] && command -v protoc >/dev/null && command -v protoc-gen-doc >/dev/null; then
  mode="local"
elif command -v docker >/dev/null; then
  mode="docker"
else
  echo "[gen-grpc-docs] need either (protoc + protoc-gen-doc) or docker on PATH" >&2
  exit 1
fi
echo "[gen-grpc-docs] using ${mode} backend"

count=0
while IFS= read -r -d '' proto; do
  rel="${proto#"${PROTO_DIR}/"}"
  base="$(basename "${rel}" .proto)"
  out_name="${base}.md"
  if [[ ${mode} == "local" ]]; then
    gen_one_local "${rel}" "${out_name}"
  else
    gen_one_docker "${rel}" "${out_name}"
  fi
  count=$((count + 1))
done < <(find "${PROTO_DIR}" -name '*.proto' -print0)

# Drop a small README inside the generated directory pointing readers back at
# the canonical hand-curated wrapping pages. The README is never published
# (the docs collection ignores `_*` directories) but helps anyone who finds
# the directory while spelunking the repo.
cat >"${OUT_DIR}/README.md" <<'EOF'
# Auto-generated gRPC reference

These files are produced by `tools/gen-grpc-docs.sh` from the canonical
`.proto` files under `java/cloud-protocol/src/main/proto/`. They are
**not** part of the published website — the Astro content collection
(`website/src/content.config.ts`) excludes any directory whose name
starts with `_`.

User-facing protocol docs live at
`docs/public/en/internals/protocol/{daemon,bootstrap,admin}-service.md`.
Use this directory as the underlying truth for diffing against those
hand-curated pages when proto changes land.
EOF

echo "[gen-grpc-docs] wrote ${count} service files (+ README) to ${OUT_DIR}"
