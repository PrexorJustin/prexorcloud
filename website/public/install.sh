#!/bin/sh
#
# prexorctl installer (POSIX sh)
#
# Runs under any POSIX-compatible shell: dash, ash/busybox, ksh, mksh, bash,
# zsh. No bash dependency, no re-fetch shim, no GNU coreutils requirement
# beyond curl + tar + sha256sum/shasum.
#
# Usage:
#   curl -fsSL https://prexor.cloud/install.sh | sh                    # install + launch wizard
#   curl -fsSL https://prexor.cloud/install.sh | sh -s -- --no-setup   # install only
#   curl -fsSL https://prexor.cloud/install.sh | sh -s -- --version v1.0.0
#
# Flags:
#   --no-setup              install the binary, do NOT launch the browser wizard
#   --version <ver>         pin a specific release tag (default: latest)
#   --install-dir <dir>     install directory (default: /usr/local/bin)
#   -h, --help              show this message
#
# Windows: use the PowerShell installer instead:
#   irm https://prexor.cloud/install.ps1 | iex
#
# Source artifacts live on the project's GitHub Releases:
#   https://github.com/PrexorJustin/prexorcloud/releases

set -eu

# ─── Configuration ───────────────────────────────────────────────────────────
GH_REPO="PrexorJustin/prexorcloud"
RELEASES_BASE="https://github.com/${GH_REPO}/releases"
BINARY="prexorctl"
ALIAS="pc"
VERSION="latest"
INSTALL_DIR="/usr/local/bin"
AUTO_SETUP=1

# Cosign identity — the GitHub Actions workflow that signs releases.
COSIGN_IDENTITY_REGEX='^https://github\.com/PrexorJustin/prexorcloud/\.github/workflows/release\.ya?ml@.*'
COSIGN_ISSUER='https://token.actions.githubusercontent.com'

# ─── Help ────────────────────────────────────────────────────────────────────
usage() {
  cat <<'USAGE'
prexorctl installer

Usage:
  curl -fsSL https://prexor.cloud/install.sh | sh
  curl -fsSL https://prexor.cloud/install.sh | sh -s -- --no-setup
  curl -fsSL https://prexor.cloud/install.sh | sh -s -- --version v1.0.0

Flags:
  --no-setup              install the binary, do NOT launch the browser wizard
  --version <ver>         pin a specific release tag (default: latest)
  --install-dir <dir>     install directory (default: /usr/local/bin)
  -h, --help              show this message

Windows users: use the PowerShell installer instead:
  irm https://prexor.cloud/install.ps1 | iex
USAGE
}

# ─── Flag parsing ────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --no-setup)    AUTO_SETUP=0; shift ;;
    --version)     [ $# -ge 2 ] || { echo "--version requires a value" >&2; exit 64; }
                   VERSION="$2"; shift 2 ;;
    --install-dir) [ $# -ge 2 ] || { echo "--install-dir requires a value" >&2; exit 64; }
                   INSTALL_DIR="$2"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    *)             echo "unknown flag: $1 (try --help)" >&2; exit 64 ;;
  esac
done

# ─── Output helpers ──────────────────────────────────────────────────────────
if [ -t 1 ]; then
  tty_escape() { printf "\033[%sm" "$1"; }
else
  tty_escape() { :; }
fi
tty_blue=$(tty_escape "1;34")
tty_red=$(tty_escape "1;31")
tty_bold=$(tty_escape "1;39")
tty_reset=$(tty_escape 0)
ohai()  { printf "%s==>%s %s%s\n" "$tty_blue" "$tty_bold" "$*" "$tty_reset"; }
warn()  { printf "%sWarning%s: %s\n" "$tty_red" "$tty_reset" "$*"; }
abort() { printf "%sError%s: %s\n" "$tty_red" "$tty_reset" "$*" >&2; exit 1; }

# ─── Platform detection ──────────────────────────────────────────────────────
uname_s=$(uname -s 2>/dev/null || echo unknown)
uname_m=$(uname -m 2>/dev/null || echo unknown)

case "$uname_s" in
  Linux)
    PLATFORM="linux"
    ;;
  Darwin)
    PLATFORM="darwin"
    ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    abort "Detected Windows (${uname_s}). Use the PowerShell installer:
  irm https://prexor.cloud/install.ps1 | iex
Or install via package manager:
  scoop install prexorctl
  winget install prexorctl"
    ;;
  FreeBSD|OpenBSD|NetBSD|DragonFly)
    abort "${uname_s} is not in the release build matrix yet.
Build from source: https://github.com/${GH_REPO}#building-from-source"
    ;;
  *)
    abort "Unsupported operating system: ${uname_s}"
    ;;
esac

case "$uname_m" in
  x86_64|amd64)
    ARCH="amd64"
    ;;
  arm64|aarch64)
    ARCH="arm64"
    ;;
  armv6l|armv7l|armv7|armhf)
    abort "32-bit ARM (${uname_m}) is not in the release build matrix.
Build from source: https://github.com/${GH_REPO}#building-from-source"
    ;;
  i386|i486|i586|i686)
    abort "32-bit x86 (${uname_m}) is not supported. prexorctl requires a 64-bit CPU."
    ;;
  riscv64|ppc64le|s390x|loongarch64)
    abort "${uname_m} is not in the release build matrix.
Build from source: https://github.com/${GH_REPO}#building-from-source"
    ;;
  *)
    abort "Unsupported architecture: ${uname_m} (supported: x86_64, arm64)"
    ;;
esac

command -v curl >/dev/null 2>&1 || abort "curl is required but not installed."
command -v tar  >/dev/null 2>&1 || abort "tar is required but not installed."

# ─── Resolve version + download URLs ─────────────────────────────────────────
# "latest" is resolved via the HTTP redirect on /releases/latest — no GH API
# token, no rate limit, no JSON parsing. Pinned versions are used verbatim.
if [ "$VERSION" = "latest" ]; then
  ohai "Resolving latest release tag"
  resolved=$(curl -fsSL --proto '=https' --tlsv1.2 --retry 3 --retry-delay 2 \
    -o /dev/null -w '%{url_effective}' "${RELEASES_BASE}/latest") \
    || abort "Could not reach ${RELEASES_BASE}/latest"
  TAG="${resolved##*/}"
  [ -n "$TAG" ] && [ "$TAG" != "latest" ] \
    || abort "Could not resolve latest release tag from ${RELEASES_BASE}/latest"
else
  # Accept "v1.0.0" or "1.0.0" — GoReleaser tags always have the v.
  TAG="${VERSION#v}"
  TAG="v${TAG}"
fi
SEMVER="${TAG#v}"
DL_BASE="${RELEASES_BASE}/download/${TAG}"

archive="${BINARY}_${SEMVER}_${PLATFORM}_${ARCH}.tar.gz"
archive_url="${DL_BASE}/${archive}"
checksums_url="${DL_BASE}/checksums.txt"
checksums_sig_url="${DL_BASE}/checksums.txt.sig"
checksums_cert_url="${DL_BASE}/checksums.txt.pem"

ohai "Installing ${BINARY} ${TAG} for ${PLATFORM}/${ARCH}"
ohai "Source: ${DL_BASE}"
ohai "Target: ${INSTALL_DIR}/${BINARY}"

workdir=$(mktemp -d 2>/dev/null || mktemp -d -t prexorctl)
trap 'rm -rf "$workdir"' EXIT INT HUP TERM

fetch() {
  curl -fSL --proto '=https' --tlsv1.2 --retry 3 --retry-delay 2 -o "$2" "$1" \
    || abort "Failed to download $1"
}

ohai "Downloading checksums.txt"
fetch "$checksums_url" "${workdir}/checksums.txt"

# ─── Cosign verification (signs checksums.txt; chain transitively covers
# every archive listed in it). Skip silently if cosign isn't installed —
# the SHA-256 check below still catches in-flight corruption.
if [ "${PREXORCTL_COSIGN:-}" = "skip" ]; then
  warn "PREXORCTL_COSIGN=skip — bypassing cosign signature verification."
elif command -v cosign >/dev/null 2>&1; then
  ohai "Downloading cosign signature + certificate"
  fetch "$checksums_sig_url"  "${workdir}/checksums.txt.sig"
  fetch "$checksums_cert_url" "${workdir}/checksums.txt.pem"
  ohai "Verifying cosign signature (Rekor transparency-log-backed)"
  cosign verify-blob \
    --certificate            "${workdir}/checksums.txt.pem" \
    --signature              "${workdir}/checksums.txt.sig" \
    --certificate-identity-regexp "${COSIGN_IDENTITY_REGEX}" \
    --certificate-oidc-issuer "${COSIGN_ISSUER}" \
    "${workdir}/checksums.txt" \
    || abort "cosign verification of checksums.txt failed. Set PREXORCTL_COSIGN=skip only if you understand the risk."
else
  warn "cosign not installed — skipping signature verification."
  warn "SHA-256 catches in-flight corruption but NOT a compromised release server."
  warn "For production installs, install cosign: https://docs.sigstore.dev/cosign/installation/"
fi

ohai "Downloading ${archive}"
fetch "$archive_url" "${workdir}/${archive}"

ohai "Verifying SHA-256 against checksums.txt"
expected=$(awk -v f="${archive}" '$2 == f { print $1; exit }' "${workdir}/checksums.txt")
[ -n "$expected" ] || abort "No checksum entry for ${archive} in checksums.txt"
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "${workdir}/${archive}" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "${workdir}/${archive}" | awk '{print $1}')
elif command -v openssl >/dev/null 2>&1; then
  actual=$(openssl dgst -sha256 "${workdir}/${archive}" | awk '{print $NF}')
else
  abort "Neither sha256sum, shasum, nor openssl is available; cannot verify checksum."
fi
[ "$actual" = "$expected" ] || abort "Checksum mismatch: expected $expected, got $actual"

ohai "Extracting ${BINARY}"
tar -xzf "${workdir}/${archive}" -C "${workdir}" "${BINARY}" \
  || abort "Failed to extract ${BINARY} from ${archive}"
chmod +x "${workdir}/${BINARY}"

# ─── Install ─────────────────────────────────────────────────────────────────
run_as_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  else
    command -v sudo >/dev/null 2>&1 \
      || abort "sudo is required to write to $INSTALL_DIR but is not installed.
Re-run as root, or pass --install-dir to choose a writable directory:
  curl -fsSL https://prexor.cloud/install.sh | sh -s -- --install-dir \"\$HOME/.local/bin\""
    sudo "$@"
  fi
}

# exec_as_root replaces the current process with "$@", elevating to root when
# needed. When already uid 0 (e.g. SSH'd into a VPS as root via key — no
# password involved) it execs directly and never touches sudo, so hosts where
# root has no password still work. Otherwise it execs through sudo. Used to
# launch the setup wizard as root so the native (systemd/package-manager)
# install path has the privileges it needs; the compose path is happy as root
# too. The wizard chowns any config it writes back to $SUDO_USER.
exec_as_root() {
  if [ "$(id -u)" -eq 0 ]; then
    exec "$@"
  else
    command -v sudo >/dev/null 2>&1 \
      || abort "sudo is required to run the setup wizard as root but is not installed.
Re-run as root (e.g. 'ssh root@host'), or install only with --no-setup and run the wizard yourself."
    exec sudo "$@"
  fi
}

# Install $2..$N either directly or via sudo, depending on whether $1 is writable.
maybe_root() {
  target="$1"; shift
  if [ -w "$target" ]; then
    "$@"
  else
    run_as_root "$@"
  fi
}

if [ ! -d "$INSTALL_DIR" ]; then
  ohai "Creating $INSTALL_DIR"
  parent=$(dirname "$INSTALL_DIR")
  maybe_root "$parent" mkdir -p "$INSTALL_DIR"
fi

ohai "Installing to ${INSTALL_DIR}/${BINARY}"
maybe_root "$INSTALL_DIR" mv "${workdir}/${BINARY}" "${INSTALL_DIR}/${BINARY}"

# Alias `pc` → `prexorctl`. Skip cleanly if a non-symlink already exists.
if [ -e "${INSTALL_DIR}/${ALIAS}" ] && [ ! -L "${INSTALL_DIR}/${ALIAS}" ]; then
  warn "${INSTALL_DIR}/${ALIAS} exists and is not a symlink; skipping alias."
else
  ohai "Creating ${ALIAS} → ${BINARY} symlink"
  maybe_root "$INSTALL_DIR" ln -sfn "${BINARY}" "${INSTALL_DIR}/${ALIAS}"
fi

# ─── Post-install ────────────────────────────────────────────────────────────
ohai "Verifying installation"
"${INSTALL_DIR}/${BINARY}" version >/dev/null 2>&1 \
  || abort "Installed binary at ${INSTALL_DIR}/${BINARY} did not run successfully."

printf "\n%sprexorctl installed successfully.%s\n" "$tty_bold" "$tty_reset"
"${INSTALL_DIR}/${BINARY}" version || true
[ -L "${INSTALL_DIR}/${ALIAS}" ] && echo "Alias: ${ALIAS} -> ${BINARY}"
echo

case ":${PATH}:" in
  *":${INSTALL_DIR}:"*) ;;
  *)
    warn "${INSTALL_DIR} is not in your PATH."
    case "$(basename "${SHELL:-}")" in
      zsh)  rc="~/.zshrc" ;;
      bash) [ "$PLATFORM" = "darwin" ] && rc="~/.bash_profile" || rc="~/.bashrc" ;;
      fish) rc="~/.config/fish/config.fish" ;;
      *)    rc="your shell rc file" ;;
    esac
    if [ "$rc" = "~/.config/fish/config.fish" ]; then
      echo "  Add to ${rc}:"
      echo "    fish_add_path ${INSTALL_DIR}"
    else
      echo "  Add to ${rc}:"
      echo "    export PATH=\"${INSTALL_DIR}:\$PATH\""
    fi
    ;;
esac

echo "Docs: https://prexor.cloud"

# ─── Auto-launch setup wizard ────────────────────────────────────────────────
# Loopback (127.0.0.1) only works when the operator's browser can reach the
# box. On a remote VPS reached via SSH with no local graphical session, flip
# to --ssh-tunnel: the wizard binds loopback and prints the laptop-side
# `ssh -L` command. The operator opens http://127.0.0.1:9100 in their laptop
# browser — no TLS, no self-signed-cert warning, no public exposure, no
# firewall change. (Prefer this over --public for SSH-reached VPSes: the
# wizard traffic rides the operator's existing SSH tunnel.)
#
# Flag-parsing consumed all "$@" by this point, so we reuse positional params
# as the args list (POSIX has no arrays).
set --
if [ -n "${SSH_CONNECTION:-}" ] \
   && [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ] \
   && [ -z "${BROWSER:-}" ] \
   && ! command -v xdg-open >/dev/null 2>&1; then
  set -- "--ssh-tunnel"
fi

# The wizard is launched as root so its native install path can write systemd
# units, import package-manager keys, and run `systemctl`. Already root → exec
# directly; otherwise via sudo. (The Docker/compose path doesn't need root, but
# running as root is harmless and lets the operator pick either path in-wizard.)
if [ "$AUTO_SETUP" -eq 1 ]; then
  echo
  if [ $# -gt 0 ]; then
    ohai "Detected SSH session with no local browser — using SSH-tunnel mode"
    echo "  The wizard will print an 'ssh -L' command to run from your laptop."
    echo "  No TLS warning, no public exposure, no firewall change needed."
    echo "  (Pass --no-setup to install.sh to skip this step.)"
    ohai "Launching prexorctl setup --browser $* (as root)"
    exec_as_root "${INSTALL_DIR}/${BINARY}" setup --browser "$@"
  else
    ohai "Launching prexorctl setup --browser (as root)"
    echo "  (Pass --no-setup to install.sh to skip this step.)"
    exec_as_root "${INSTALL_DIR}/${BINARY}" setup --browser
  fi
fi
