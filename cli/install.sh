#!/usr/bin/env bash
#
# prexorctl installer
#
# Usage:
#   curl -fsSL https://get.scharbau.me/cli | bash                    # install + launch wizard
#   curl -fsSL https://get.scharbau.me/cli | bash -s -- --no-setup   # install only
#   curl -fsSL https://get.scharbau.me/cli | bash -s -- --version 0.2.0
#
# Flags (parsed by the script itself):
#   --no-setup              install the binary, do NOT launch the browser wizard
#   --version <ver>         pin a specific release (default: latest)
#   --install-dir <dir>     install directory (default: /usr/local/bin)
#
# Environment overrides (still honoured for back-compat):
#   PREXORCTL_VERSION       same as --version
#   PREXORCTL_INSTALL_DIR   same as --install-dir
#   DOWNLOAD_BASE           override the download root (advanced/testing)

set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

# CUTOVER: when the project goes public on GitHub, replace these two lines with:
#   DOWNLOAD_BASE="https://github.com/prexorcloud/prexorcloud/releases"
#   and update path resolution below to use "latest/download" / "download/v${VERSION}".
DOWNLOAD_BASE="${DOWNLOAD_BASE:-https://get.scharbau.me}"

BINARY="prexorctl"
ALIAS="pc"
VERSION="${PREXORCTL_VERSION:-latest}"
INSTALL_DIR="${PREXORCTL_INSTALL_DIR:-/usr/local/bin}"
# Auto-setup is the default per the master plan: a fresh user runs the
# one-liner and lands in the wizard. --no-setup opts out for "I just want
# the binary on my PATH" workflows.
AUTO_SETUP=1

# -----------------------------------------------------------------------------
# Flag parsing — supports `bash -s -- --flag value` style.
# -----------------------------------------------------------------------------

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-setup)
      AUTO_SETUP=0
      shift
      ;;
    --version)
      [[ $# -ge 2 ]] || { echo "--version requires a value" >&2; exit 64; }
      VERSION="$2"
      shift 2
      ;;
    --install-dir)
      [[ $# -ge 2 ]] || { echo "--install-dir requires a value" >&2; exit 64; }
      INSTALL_DIR="$2"
      shift 2
      ;;
    -h|--help)
      sed -n '1,21p' "$0" | sed -e 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown flag: $1 (try --help)" >&2
      exit 64
      ;;
  esac
done

# Back-compat: PREXORCTL_AUTO_SETUP was the opt-in env var before the default
# flipped to auto-setup-on. PREXORCTL_AUTO_SETUP=0 now opts out, matching the
# new --no-setup flag, so existing automation that explicitly sets the var
# keeps working.
if [[ "${PREXORCTL_AUTO_SETUP:-}" == "0" ]]; then
  AUTO_SETUP=0
fi

# -----------------------------------------------------------------------------
# Output helpers
# -----------------------------------------------------------------------------

if [[ -t 1 ]]; then
  tty_escape() { printf "\033[%sm" "$1"; }
else
  tty_escape() { :; }
fi
tty_mkbold() { tty_escape "1;$1"; }
tty_blue="$(tty_mkbold 34)"
tty_red="$(tty_mkbold 31)"
tty_bold="$(tty_mkbold 39)"
tty_reset="$(tty_escape 0)"

ohai()  { printf "${tty_blue}==>${tty_bold} %s${tty_reset}\n" "$*"; }
warn()  { printf "${tty_red}Warning${tty_reset}: %s\n" "$*"; }
abort() { printf "${tty_red}Error${tty_reset}: %s\n" "$*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# Platform detection
# -----------------------------------------------------------------------------

detect_platform() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"

  case "$os" in
    Linux)  PLATFORM="linux" ;;
    Darwin) PLATFORM="darwin" ;;
    MINGW*|MSYS*|CYGWIN*)
      abort "Windows is not supported by this installer. Use Scoop: scoop install prexorctl"
      ;;
    *) abort "Unsupported operating system: $os" ;;
  esac

  case "$arch" in
    x86_64|amd64)  ARCH="amd64" ;;
    arm64|aarch64) ARCH="arm64" ;;
    *) abort "Unsupported architecture: $arch (supported: x86_64, arm64)" ;;
  esac
}

# -----------------------------------------------------------------------------
# Privilege helpers
# -----------------------------------------------------------------------------

need_sudo() {
  [[ ! -w "$1" ]]
}

run_as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  else
    command -v sudo >/dev/null 2>&1 || abort "sudo is required to write to $INSTALL_DIR but is not installed."
    sudo "$@"
  fi
}

ensure_install_dir() {
  if [[ -d "$INSTALL_DIR" ]]; then
    return
  fi
  ohai "Creating $INSTALL_DIR"
  if [[ -w "$(dirname "$INSTALL_DIR")" ]]; then
    mkdir -p "$INSTALL_DIR"
  else
    run_as_root mkdir -p "$INSTALL_DIR"
  fi
}

# -----------------------------------------------------------------------------
# Checksum verification
# -----------------------------------------------------------------------------

sha256_check() {
  local file="$1" expected="$2" actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  else
    abort "Neither sha256sum nor shasum is available; cannot verify checksum."
  fi
  if [[ "$actual" != "$expected" ]]; then
    abort "Checksum mismatch for $(basename "$file"): expected $expected, got $actual"
  fi
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

detect_platform

asset="${BINARY}-${PLATFORM}-${ARCH}"
if [[ "$VERSION" == "latest" ]]; then
  base="${DOWNLOAD_BASE}/latest"
else
  base="${DOWNLOAD_BASE}/${VERSION}"
fi
binary_url="${base}/${asset}"
checksums_url="${base}/checksums.txt"

ohai "Installing ${BINARY} (${VERSION}) for ${PLATFORM}/${ARCH}"
ohai "Source: ${base}"
ohai "Target: ${INSTALL_DIR}/${BINARY}"

command -v curl >/dev/null 2>&1 || abort "curl is required but not installed."

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

ohai "Downloading ${asset}"
curl -fsSL --proto '=https' --tlsv1.2 -o "${workdir}/${asset}" "${binary_url}" \
  || abort "Failed to download ${binary_url}"

ohai "Downloading checksums.txt"
curl -fsSL --proto '=https' --tlsv1.2 -o "${workdir}/checksums.txt" "${checksums_url}" \
  || abort "Failed to download ${checksums_url}"

ohai "Verifying SHA256 checksum"
expected="$(awk -v f="${asset}" '$2 == f || $2 == "*"f { print $1; exit }' "${workdir}/checksums.txt")"
[[ -n "$expected" ]] || abort "No checksum entry for ${asset} in checksums.txt"
sha256_check "${workdir}/${asset}" "$expected"

# -----------------------------------------------------------------------------
# Cosign signature verification (production-grade integrity check)
#
# Every prexorctl release ships a cosign sign-blob bundle (.cosign.bundle)
# alongside the binary, with the Rekor transparency log entry embedded.
# When cosign is on PATH we verify the bundle against the upstream
# OIDC identity (the GitHub Actions OIDC token used by release.yml).
#
# If cosign is missing, we print a loud warning and continue (the SHA-256
# checksum still gates obvious tampering). Operators are encouraged to
# install cosign — the threat model the SHA-256 alone covers is "in-flight
# corruption", NOT "compromised release server" or "MITM with a forged
# checksums.txt". cosign + Rekor closes both.
#
# Override: set PREXORCTL_COSIGN=skip to bypass entirely (CI testing only).
# -----------------------------------------------------------------------------
COSIGN_IDENTITY_REGEX="${PREXORCTL_COSIGN_IDENTITY:-^https://github\\.com/prexorjustin/prexorcloud/\\.github/workflows/release\\.ya?ml@.*}"
COSIGN_ISSUER="${PREXORCTL_COSIGN_ISSUER:-https://token.actions.githubusercontent.com}"

if [[ "${PREXORCTL_COSIGN:-}" == "skip" ]]; then
  warn "PREXORCTL_COSIGN=skip — bypassing cosign signature verification."
elif command -v cosign >/dev/null 2>&1; then
  bundle_url="${base}/${asset}.cosign.bundle"
  ohai "Downloading ${asset}.cosign.bundle"
  if curl -fsSL --proto '=https' --tlsv1.2 -o "${workdir}/${asset}.cosign.bundle" "${bundle_url}"; then
    ohai "Verifying cosign signature (Rekor transparency-log-backed)"
    cosign verify-blob \
      --bundle "${workdir}/${asset}.cosign.bundle" \
      --certificate-identity-regexp "${COSIGN_IDENTITY_REGEX}" \
      --certificate-oidc-issuer "${COSIGN_ISSUER}" \
      "${workdir}/${asset}" \
      || abort "cosign verification failed — aborting install. Set PREXORCTL_COSIGN=skip only if you understand the risk."
  else
    warn "No cosign bundle published for ${asset} at ${bundle_url}"
    warn "Continuing with SHA-256 only. For production installs, install cosign:"
    warn "  https://docs.sigstore.dev/cosign/installation/"
  fi
else
  warn "cosign not installed — skipping signature verification."
  warn "SHA-256 catches in-flight corruption but NOT a compromised release server."
  warn "For production installs, install cosign:"
  warn "  https://docs.sigstore.dev/cosign/installation/"
fi

ensure_install_dir

ohai "Installing to ${INSTALL_DIR}/${BINARY}"
chmod +x "${workdir}/${asset}"
if need_sudo "$INSTALL_DIR"; then
  run_as_root mv "${workdir}/${asset}" "${INSTALL_DIR}/${BINARY}"
else
  mv "${workdir}/${asset}" "${INSTALL_DIR}/${BINARY}"
fi

ohai "Creating ${ALIAS} alias"
if [[ -e "${INSTALL_DIR}/${ALIAS}" || -L "${INSTALL_DIR}/${ALIAS}" ]]; then
  if [[ ! -L "${INSTALL_DIR}/${ALIAS}" ]]; then
    warn "${INSTALL_DIR}/${ALIAS} exists and is not a symlink; skipping alias."
  else
    if need_sudo "$INSTALL_DIR"; then
      run_as_root ln -sfn "${BINARY}" "${INSTALL_DIR}/${ALIAS}"
    else
      ln -sfn "${BINARY}" "${INSTALL_DIR}/${ALIAS}"
    fi
  fi
else
  if need_sudo "$INSTALL_DIR"; then
    run_as_root ln -s "${BINARY}" "${INSTALL_DIR}/${ALIAS}"
  else
    ln -s "${BINARY}" "${INSTALL_DIR}/${ALIAS}"
  fi
fi

# -----------------------------------------------------------------------------
# Post-install
# -----------------------------------------------------------------------------

ohai "Verifying installation"
if ! "${INSTALL_DIR}/${BINARY}" version >/dev/null 2>&1; then
  abort "Installed binary at ${INSTALL_DIR}/${BINARY} did not run successfully."
fi

printf "\n${tty_bold}prexorctl installed successfully.${tty_reset}\n"
"${INSTALL_DIR}/${BINARY}" version || true
if [[ -L "${INSTALL_DIR}/${ALIAS}" ]]; then
  echo "Alias: ${ALIAS} -> ${BINARY}"
fi
echo

if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
  warn "${INSTALL_DIR} is not in your PATH."
  case "$(basename "${SHELL:-}")" in
    zsh)  rc="~/.zshrc" ;;
    bash) [[ "$PLATFORM" == "darwin" ]] && rc="~/.bash_profile" || rc="~/.bashrc" ;;
    fish) rc="~/.config/fish/config.fish" ;;
    *)    rc="your shell rc file" ;;
  esac
  if [[ "${rc}" == "~/.config/fish/config.fish" ]]; then
    echo "  Add to ${rc}:"
    echo "    fish_add_path ${INSTALL_DIR}"
  else
    echo "  Add to ${rc}:"
    echo "    export PATH=\"${INSTALL_DIR}:\$PATH\""
  fi
fi

echo "Docs: https://docs.prexor.cloud"

# Auto-launch the browser-based setup wizard. Phase 1.8 of the master plan
# states the one-liner should land a fresh user in a working controller;
# making the wizard opt-in defeats the entire flow. --no-setup (or
# PREXORCTL_AUTO_SETUP=0) skips this step for "binary only" workflows.
#
# Mode selection — loopback vs. --public (TLS + token):
#
# Loopback only makes sense when the *operator's* browser can reach
# 127.0.0.1 on the box running this script. That's true on a desktop or
# local VM. It is NOT true when the operator just SSH'd into a remote VPS
# and there's no local graphical session — the wizard would bind a port
# nobody can reach.
#
# Detection: we infer "remote / no local browser" from the union of:
#   • SSH_CONNECTION set         → operator is on this box via ssh
#   • DISPLAY/WAYLAND_DISPLAY    → unset (no X11/Wayland forwarding)
#   • no $BROWSER / xdg-open     → no realistic local browser path
#
# When that triggers, we flip on --public so prexorctl prints a
# token-protected HTTPS URL the operator can open from their laptop.
# We also lift the public hostname from SSH_CONNECTION ("client_ip
# client_port server_ip server_port") — server_ip is by definition the
# address the operator already reached us at, so the printed URL works
# without any DNS guessing.

setup_args=()
if [[ -n "${SSH_CONNECTION:-}" ]] \
   && [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]] \
   && [[ -z "${BROWSER:-}" ]] \
   && ! command -v xdg-open >/dev/null 2>&1; then
  setup_args+=("--public")
  # SSH_CONNECTION format: "<client_ip> <client_port> <server_ip> <server_port>"
  ssh_server_ip="$(awk '{print $3}' <<< "${SSH_CONNECTION}")"
  if [[ -n "${ssh_server_ip}" ]]; then
    setup_args+=("--public-host" "${ssh_server_ip}")
  fi
fi

if [[ $AUTO_SETUP -eq 1 ]]; then
  echo
  if [[ ${#setup_args[@]} -gt 0 ]]; then
    ohai "Detected SSH session with no local browser — enabling --public mode"
    echo "  The wizard will print a token-protected HTTPS URL you can open from your laptop."
    echo "  (Pass --no-setup to install.sh to skip this step.)"
    ohai "Launching prexorctl setup --browser ${setup_args[*]}"
    exec "${INSTALL_DIR}/${BINARY}" setup --browser "${setup_args[@]}"
  else
    ohai "Launching prexorctl setup --browser"
    echo "  (Pass --no-setup to install.sh to skip this step.)"
    exec "${INSTALL_DIR}/${BINARY}" setup --browser
  fi
fi
