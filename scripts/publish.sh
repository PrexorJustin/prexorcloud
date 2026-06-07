#!/usr/bin/env bash
#
# publish.sh — drive the per-component publish lanes of PrexorCloud from one
# place. Today: CLI binaries (cli/Makefile) and Java JARs (java/gradlew
# publishCloud). Future: dashboard and website (stubs are registered below).
#
# Usage:
#   ./scripts/publish.sh                       # publish every "ready" component
#   ./scripts/publish.sh cli                   # publish only the CLI
#   ./scripts/publish.sh cli java              # publish multiple, in order
#   ./scripts/publish.sh --list                # show what's registered
#   ./scripts/publish.sh --dry-run cli java    # print actions, change nothing
#   ./scripts/publish.sh --yes cli             # skip the confirmation prompt
#
# Required environment / flags:
#   PUBLISH_HOST              SSH host (e.g. get.scharbau.me) — required
#   PUBLISH_USER              SSH user (default: deploy)
#   PUBLISH_VERSION           release label (default: git describe)
#
# Adding a new component:
#   1. Write a `publish_<name>()` function below.
#   2. Add a `register <name> <status> "<one-line description>"` call in the
#      COMPONENTS section. Status is "ready" or "stub".
#   3. Done. The dispatcher picks it up automatically.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ─── output helpers ─────────────────────────────────────────────────────────
if [[ -t 1 ]]; then
  blue=$'\033[1;34m'; green=$'\033[1;32m'; yellow=$'\033[1;33m'
  red=$'\033[1;31m'; bold=$'\033[1m'; dim=$'\033[2m'; reset=$'\033[0m'
else
  blue=""; green=""; yellow=""; red=""; bold=""; dim=""; reset=""
fi
ohai()  { printf "%s==>%s %s\n" "$blue"   "$reset" "$*"; }
ok()    { printf "%s ✓%s %s\n" "$green"  "$reset" "$*"; }
warn()  { printf "%sWarn%s: %s\n" "$yellow" "$reset" "$*"; }
abort() { printf "%sError%s: %s\n" "$red" "$reset" "$*" >&2; exit 1; }

# ─── component registry ─────────────────────────────────────────────────────
declare -a COMPONENT_ORDER=()
declare -A COMPONENT_STATUS=()
declare -A COMPONENT_DESC=()

register() {
  local name="$1" status="$2" desc="$3"
  case "$status" in ready|stub) ;; *) abort "register: unknown status '$status' for $name" ;; esac
  COMPONENT_ORDER+=("$name")
  COMPONENT_STATUS["$name"]="$status"
  COMPONENT_DESC["$name"]="$desc"
}

# ─── COMPONENTS — edit this section to add new lanes ────────────────────────

register cli       ready 'prexorctl binaries (linux/darwin/windows × amd64/arm64) + install.sh'
register java      ready 'controller + daemon shadow JARs (publishCloud Gradle task)'
register dashboard stub  'Vue/Nuxt dashboard bundle — not wired up yet'
register website   stub  'marketing/docs site — not wired up yet'

# ─── publishers ─────────────────────────────────────────────────────────────

publish_cli() {
  ohai "[$1] cli → $PUBLISH_USER@$PUBLISH_HOST:/var/www/prexorctl/$PUBLISH_VERSION/"
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "    (dry-run) cd cli && make publish PUBLISH_HOST=$PUBLISH_HOST PUBLISH_USER=$PUBLISH_USER PUBLISH_VERSION=$PUBLISH_VERSION"
    return 0
  fi
  ( cd "$REPO_ROOT/cli" && make publish \
      PUBLISH_HOST="$PUBLISH_HOST" \
      PUBLISH_USER="$PUBLISH_USER" \
      PUBLISH_VERSION="$PUBLISH_VERSION" )
}

publish_java() {
  ohai "[$1] java → $PUBLISH_USER@$PUBLISH_HOST:/var/www/prexorcloud/$PUBLISH_VERSION/"
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "    (dry-run) cd java && ./gradlew publishCloud -PpublishHost=$PUBLISH_HOST -PpublishUser=$PUBLISH_USER -PpublishVersion=$PUBLISH_VERSION"
    return 0
  fi
  ( cd "$REPO_ROOT/java" && ./gradlew publishCloud \
      -PpublishHost="$PUBLISH_HOST" \
      -PpublishUser="$PUBLISH_USER" \
      -PpublishVersion="$PUBLISH_VERSION" )
}

# Stubs intentionally fail loudly when explicitly requested. They are
# skipped (with a notice) when reached via the default "all ready" selection.
publish_dashboard() {
  abort "[$1] dashboard publish lane is not implemented yet — see scripts/publish.sh, the publish_dashboard function"
}

publish_website() {
  abort "[$1] website publish lane is not implemented yet — see scripts/publish.sh, the publish_website function"
}

# ─── dispatcher ─────────────────────────────────────────────────────────────

print_list() {
  printf "${bold}Registered components:${reset}\n"
  for name in "${COMPONENT_ORDER[@]}"; do
    local status="${COMPONENT_STATUS[$name]}"
    local desc="${COMPONENT_DESC[$name]}"
    local badge
    case "$status" in
      ready) badge="${green}ready${reset}" ;;
      stub)  badge="${dim}stub${reset}"   ;;
      *)     badge="$status" ;;
    esac
    printf "  %-12s %s  %s\n" "$name" "$badge" "$desc"
  done
}

usage() {
  sed -n '1,28p' "$0" | sed -e 's/^# \{0,1\}//'
  exit 0
}

DRY_RUN=0
ASSUME_YES=0
COMPONENTS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list)     print_list; exit 0 ;;
    --dry-run)  DRY_RUN=1; shift ;;
    -y|--yes)   ASSUME_YES=1; shift ;;
    --all)      shift; COMPONENTS=() ;;  # explicit no-op — we'll fall through to default
    -h|--help)  usage ;;
    --)         shift; while [[ $# -gt 0 ]]; do COMPONENTS+=("$1"); shift; done ;;
    -*)         abort "unknown flag: $1 (try --help)" ;;
    *)          COMPONENTS+=("$1"); shift ;;
  esac
done

# Required env: PUBLISH_HOST. Versions/users have sensible defaults.
PUBLISH_HOST="${PUBLISH_HOST:-}"
PUBLISH_USER="${PUBLISH_USER:-deploy}"
PUBLISH_VERSION="${PUBLISH_VERSION:-$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo dev)}"

if [[ -z "$PUBLISH_HOST" ]]; then
  abort "PUBLISH_HOST is required (e.g. PUBLISH_HOST=get.scharbau.me $0 cli java)"
fi

# No components specified → publish every "ready" lane in registration order.
if [[ ${#COMPONENTS[@]} -eq 0 ]]; then
  for name in "${COMPONENT_ORDER[@]}"; do
    [[ "${COMPONENT_STATUS[$name]}" == "ready" ]] && COMPONENTS+=("$name")
  done
  if [[ ${#COMPONENTS[@]} -eq 0 ]]; then
    abort "no components are 'ready' — nothing to publish"
  fi
fi

# Validate every requested component before doing anything.
for name in "${COMPONENTS[@]}"; do
  if [[ -z "${COMPONENT_STATUS[$name]:-}" ]]; then
    abort "unknown component: $name (run --list to see what's registered)"
  fi
done

# ─── plan + confirm ─────────────────────────────────────────────────────────

ohai "Publish plan"
printf "  host:     %s@%s\n" "$PUBLISH_USER" "$PUBLISH_HOST"
printf "  version:  %s\n" "$PUBLISH_VERSION"
printf "  dry-run:  %s\n" "$([[ $DRY_RUN -eq 1 ]] && echo yes || echo no)"
printf "  targets:  %s\n" "${COMPONENTS[*]}"
echo

if [[ $ASSUME_YES -ne 1 && $DRY_RUN -ne 1 ]]; then
  read -r -p "${bold}Proceed?${reset} [y/N] " ans
  [[ "$ans" =~ ^[Yy]$ ]] || abort "aborted"
fi

# ─── execute ────────────────────────────────────────────────────────────────

declare -a FAILED=()
declare -a SKIPPED=()

count=${#COMPONENTS[@]}
i=0
for name in "${COMPONENTS[@]}"; do
  i=$((i + 1))
  status="${COMPONENT_STATUS[$name]}"

  # When stubs land in the default "ready-only" set they're filtered out
  # already, so a stub here means the user asked for it explicitly. Let
  # publish_<name> abort with its own message — that's the intended UX.

  fn="publish_$name"
  if ! declare -F "$fn" > /dev/null; then
    abort "internal: $name registered but no $fn function defined"
  fi

  echo
  if "$fn" "$i/$count"; then
    ok "$name published"
  else
    rc=$?
    warn "$name failed (rc=$rc) — continuing with remaining lanes"
    FAILED+=("$name")
  fi
done

# ─── summary ────────────────────────────────────────────────────────────────

echo
ohai "Summary"
printf "  published: %d\n" "$(( count - ${#FAILED[@]} ))"
if [[ ${#FAILED[@]} -gt 0 ]]; then
  printf "  ${red}failed${reset}:    %s\n" "${FAILED[*]}"
  exit 1
fi
ok "all targets published as $PUBLISH_VERSION"
