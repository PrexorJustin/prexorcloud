#!/usr/bin/env bash
#
# uninstall.sh — undo a `prexorctl setup` install, plus optionally remove
# system MongoDB / Redis packages.
#
# Usage:
#   sudo ./scripts/uninstall.sh             # interactive, asks before each step
#   sudo ./scripts/uninstall.sh --yes       # answer yes to everything
#   sudo ./scripts/uninstall.sh --keep-data # remove services but keep /opt/prexorcloud
#
# Each layer (compose, systemd, install dirs, binary, system packages, CLI
# config) is independently confirmed. Skipped layers leave that part of the
# install untouched.

set -euo pipefail

ASSUME_YES=0
KEEP_DATA=0
for arg in "$@"; do
  case "$arg" in
    -y|--yes)         ASSUME_YES=1 ;;
    --keep-data)      KEEP_DATA=1 ;;
    -h|--help)
      sed -n '1,20p' "$0" | sed -e 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown flag: $arg" >&2; exit 64 ;;
  esac
done

if [[ -t 1 ]]; then
  blue=$'\033[1;34m'; red=$'\033[1;31m'; yellow=$'\033[1;33m'; bold=$'\033[1m'; reset=$'\033[0m'
else
  blue=""; red=""; yellow=""; bold=""; reset=""
fi
ohai()  { printf "%s==>%s %s\n" "$blue" "$reset" "$*"; }
warn()  { printf "%sWarning%s: %s\n" "$yellow" "$reset" "$*"; }
abort() { printf "%sError%s: %s\n" "$red" "$reset" "$*" >&2; exit 1; }

confirm() {
  local prompt="$1"
  if [[ $ASSUME_YES -eq 1 ]]; then
    ohai "$prompt → yes (--yes)"
    return 0
  fi
  read -r -p "${bold}${prompt}${reset} [y/N] " ans
  [[ "$ans" =~ ^[Yy]$ ]]
}

require_root() {
  if [[ $EUID -ne 0 ]]; then
    abort "this script needs root for systemctl + /opt cleanup. Re-run with sudo."
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 1. Compose projects
# ────────────────────────────────────────────────────────────────────────────

teardown_compose() {
  local dir="$1"
  if [[ ! -f "$dir/docker-compose.yml" ]]; then
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    warn "docker missing — leaving compose project at $dir/docker-compose.yml"
    return 0
  fi
  if confirm "Tear down docker compose project in $dir (containers + volumes)?"; then
    ohai "docker compose down -v in $dir"
    (cd "$dir" && docker compose down -v --remove-orphans) || warn "compose down failed in $dir (continuing)"
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 2. Systemd services
# ────────────────────────────────────────────────────────────────────────────

teardown_systemd_unit() {
  local unit="$1"
  if ! command -v systemctl >/dev/null 2>&1; then
    return 0
  fi
  if ! systemctl list-unit-files "${unit}.service" --no-legend --no-pager 2>/dev/null | grep -q .; then
    return 0
  fi
  if confirm "Stop and remove systemd unit ${unit}?"; then
    ohai "Stopping ${unit}"
    systemctl stop "${unit}" 2>/dev/null || true
    systemctl disable "${unit}" 2>/dev/null || true
    rm -f "/etc/systemd/system/${unit}.service"
    systemctl daemon-reload
    systemctl reset-failed "${unit}" 2>/dev/null || true
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 3. /opt install directories
# ────────────────────────────────────────────────────────────────────────────

remove_install_dir() {
  local dir="$1"
  if [[ ! -d "$dir" ]]; then
    return 0
  fi
  if [[ $KEEP_DATA -eq 1 ]]; then
    warn "--keep-data set, leaving $dir in place (config + jars + mongo-data + redis-data)"
    return 0
  fi
  if confirm "Delete $dir (jars, config, compose volumes if present)?"; then
    ohai "rm -rf $dir"
    rm -rf "$dir"
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 4. prexorctl + cosign binaries
# ────────────────────────────────────────────────────────────────────────────

remove_binary() {
  for path in /usr/local/bin/prexorctl /usr/local/bin/pc /usr/bin/prexorctl /usr/bin/pc; do
    if [[ -e "$path" || -L "$path" ]]; then
      if confirm "Remove $path?"; then
        ohai "rm $path"
        rm -f "$path"
      fi
    fi
  done
}

# Cosign is downloaded by the wizard (not apt) to /usr/local/bin/cosign so it
# can verify JAR signatures. Removing it isolates the cleanup to what the
# wizard installed — other tools that already use cosign keep their copy.
remove_cosign() {
  local path=/usr/local/bin/cosign
  if [[ ! -e "$path" && ! -L "$path" ]]; then
    return 0
  fi
  if confirm "Remove $path (installed by the wizard for JAR signature verification)?"; then
    ohai "rm $path"
    rm -f "$path"
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 5. CLI config (~/.prexorcloud)
# ────────────────────────────────────────────────────────────────────────────

remove_cli_config() {
  # Walk every real user (UID >= 1000) plus root, in case sudo invoked us
  # but the operator's config lives under their home.
  local target_homes=()
  if [[ -n "${SUDO_USER:-}" ]]; then
    local sudo_home
    sudo_home="$(getent passwd "$SUDO_USER" | cut -d: -f6)"
    [[ -n "$sudo_home" ]] && target_homes+=("$sudo_home")
  fi
  target_homes+=("$HOME" "/root")

  local seen=()
  for home in "${target_homes[@]}"; do
    [[ -z "$home" ]] && continue
    case " ${seen[*]} " in *" $home "*) continue ;; esac
    seen+=("$home")
    local cfg="$home/.prexorcloud"
    if [[ -d "$cfg" ]]; then
      if confirm "Remove CLI config at $cfg?"; then
        ohai "rm -rf $cfg"
        rm -rf "$cfg"
      fi
    fi
  done
}

# ────────────────────────────────────────────────────────────────────────────
# 6. Dashboard web-server vhosts (nginx / Caddy)
# ────────────────────────────────────────────────────────────────────────────

# The native dashboard install drops a vhost into nginx's conf.d or rewrites
# the system Caddyfile. Both are owned by the wizard, so remove them by name.
# We do NOT remove nginx/Caddy itself here — that's part of layer 7.
remove_webserver_vhosts() {
  local nginx_vhost=/etc/nginx/conf.d/prexorcloud-dashboard.conf
  if [[ -f "$nginx_vhost" ]]; then
    if confirm "Remove $nginx_vhost?"; then
      ohai "rm $nginx_vhost"
      rm -f "$nginx_vhost"
      if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx 2>/dev/null; then
        ohai "systemctl reload nginx"
        systemctl reload nginx 2>/dev/null || warn "nginx reload failed (config may now be inconsistent)"
      fi
    fi
  fi

  # Caddy's wizard install rewrites the SYSTEM Caddyfile rather than dropping
  # a sub-config. Restoring stock Caddy means resetting that file. Confirm
  # explicitly because /etc/caddy/Caddyfile is shared with anything else
  # someone might have edited into it.
  local caddyfile=/etc/caddy/Caddyfile
  if [[ -f "$caddyfile" ]] && grep -q 'Generated by PrexorCloud Installer' "$caddyfile" 2>/dev/null; then
    if confirm "Reset $caddyfile to the empty stock placeholder?"; then
      ohai "Resetting $caddyfile"
      printf "# Caddyfile reset by PrexorCloud uninstall.sh\n" > "$caddyfile"
      if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet caddy 2>/dev/null; then
        ohai "systemctl reload caddy"
        systemctl reload caddy 2>/dev/null || warn "caddy reload failed"
      fi
    fi
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 7. apt sources + GPG keyrings the wizard installed
# ────────────────────────────────────────────────────────────────────────────

# MongoDB + Caddy are pulled from upstream apt repos the wizard pinned via
# /etc/apt/sources.list.d/*.list + a dearmored key under /usr/share/keyrings.
# Leaving them behind means `apt update` keeps polling vendor repos for
# packages we just uninstalled. Removed unconditionally when they exist;
# noisy `apt-get update` is the only thing this protects against, so the
# blast radius is small.
remove_apt_sources() {
  if ! command -v apt-get >/dev/null 2>&1; then
    return 0
  fi
  local removed=0
  for f in /etc/apt/sources.list.d/mongodb-org-*.list \
           /etc/apt/sources.list.d/caddy-stable.list \
           /usr/share/keyrings/mongodb-server-*.gpg \
           /usr/share/keyrings/caddy-stable-archive-keyring.gpg; do
    if [[ -e "$f" ]]; then
      if [[ $removed -eq 0 ]]; then
        if ! confirm "Remove wizard-installed apt sources + GPG keyrings (MongoDB + Caddy)?"; then
          return 0
        fi
        removed=1
      fi
      ohai "rm $f"
      rm -f "$f"
    fi
  done
  if [[ $removed -eq 1 ]]; then
    ohai "apt-get update -qq"
    apt-get update -qq 2>/dev/null || warn "apt-get update reported a problem (you may have other broken sources)"
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# 8. System packages — MongoDB / Redis / nginx / Caddy
# ────────────────────────────────────────────────────────────────────────────

detect_pkg_manager() {
  for pm in apt-get dnf yum pacman zypper apk; do
    if command -v "$pm" >/dev/null 2>&1; then
      echo "$pm"
      return 0
    fi
  done
  echo ""
}

uninstall_system_pkg() {
  local pm="$1" pretty="$2"; shift 2
  local pkgs=("$@")
  if [[ -z "$pm" ]]; then
    warn "No supported package manager found — skipping $pretty system uninstall"
    return 0
  fi
  if ! confirm "Uninstall $pretty via $pm? (purges packages: ${pkgs[*]})"; then
    return 0
  fi
  case "$pm" in
    apt-get)
      DEBIAN_FRONTEND=noninteractive apt-get purge -y "${pkgs[@]}" 2>/dev/null || true
      apt-get autoremove -y 2>/dev/null || true
      ;;
    dnf|yum)         "$pm" remove -y "${pkgs[@]}" 2>/dev/null || true ;;
    pacman)          pacman -Rns --noconfirm "${pkgs[@]}" 2>/dev/null || true ;;
    zypper)          zypper --non-interactive remove "${pkgs[@]}" 2>/dev/null || true ;;
    apk)             apk del "${pkgs[@]}" 2>/dev/null || true ;;
  esac
}

uninstall_mongodb() {
  local pm="$1"
  if ! command -v mongod >/dev/null 2>&1 && ! command -v mongo >/dev/null 2>&1 && ! command -v mongosh >/dev/null 2>&1; then
    return 0
  fi
  case "$pm" in
    apt-get) uninstall_system_pkg "$pm" "MongoDB" mongodb-org mongodb-org-server mongodb-org-shell mongodb-org-mongos mongodb-org-tools mongodb-clients mongodb-server ;;
    dnf|yum) uninstall_system_pkg "$pm" "MongoDB" mongodb-org mongodb-org-server mongodb-org-shell mongodb-org-tools ;;
    pacman)  uninstall_system_pkg "$pm" "MongoDB" mongodb mongodb-tools mongodb-bin ;;
    zypper)  uninstall_system_pkg "$pm" "MongoDB" mongodb mongodb-server ;;
    apk)     uninstall_system_pkg "$pm" "MongoDB" mongodb mongodb-tools ;;
    *)       warn "MongoDB binary present but no recognised package manager — skipping" ;;
  esac
  if confirm "Also remove /var/lib/mongo and /var/lib/mongodb data dirs?"; then
    rm -rf /var/lib/mongo /var/lib/mongodb /var/log/mongodb
  fi
}

uninstall_redis() {
  local pm="$1"
  if ! command -v redis-server >/dev/null 2>&1 && ! command -v redis-cli >/dev/null 2>&1 && ! command -v valkey-server >/dev/null 2>&1; then
    return 0
  fi
  case "$pm" in
    apt-get) uninstall_system_pkg "$pm" "Redis"  redis-server redis-tools redis ;;
    dnf|yum) uninstall_system_pkg "$pm" "Redis"  redis ;;
    pacman)  uninstall_system_pkg "$pm" "Redis"  redis valkey ;;
    zypper)  uninstall_system_pkg "$pm" "Redis"  redis ;;
    apk)     uninstall_system_pkg "$pm" "Redis"  redis valkey ;;
    *)       warn "Redis binary present but no recognised package manager — skipping" ;;
  esac
  if confirm "Also remove /var/lib/redis data dir?"; then
    rm -rf /var/lib/redis /var/log/redis
  fi
}

# Nginx + Caddy are only present if the operator installed the dashboard via
# the native path. Same caveat as MongoDB/Redis: skip if shared with other
# services on the box.
uninstall_webservers() {
  local pm="$1"
  if command -v nginx >/dev/null 2>&1; then
    case "$pm" in
      apt-get) uninstall_system_pkg "$pm" "nginx"  nginx nginx-common nginx-core ;;
      dnf|yum) uninstall_system_pkg "$pm" "nginx"  nginx ;;
      pacman)  uninstall_system_pkg "$pm" "nginx"  nginx ;;
      zypper)  uninstall_system_pkg "$pm" "nginx"  nginx ;;
      apk)     uninstall_system_pkg "$pm" "nginx"  nginx ;;
    esac
  fi
  if command -v caddy >/dev/null 2>&1; then
    case "$pm" in
      apt-get) uninstall_system_pkg "$pm" "Caddy"  caddy ;;
      dnf|yum) uninstall_system_pkg "$pm" "Caddy"  caddy ;;
      pacman)  uninstall_system_pkg "$pm" "Caddy"  caddy ;;
      zypper)  uninstall_system_pkg "$pm" "Caddy"  caddy ;;
      apk)     uninstall_system_pkg "$pm" "Caddy"  caddy ;;
    esac
  fi
}

# ────────────────────────────────────────────────────────────────────────────
# main
# ────────────────────────────────────────────────────────────────────────────

require_root

ohai "PrexorCloud uninstall — interactive ($([[ $ASSUME_YES -eq 1 ]] && echo "auto-yes" || echo "step-by-step"))"
echo "Each layer will ask before doing anything destructive."
echo

ohai "Layer 1/8 — Docker Compose projects"
teardown_compose /opt/prexorcloud/controller
teardown_compose /opt/prexorcloud/daemon
teardown_compose /opt/prexorcloud/dashboard
echo

ohai "Layer 2/8 — systemd units"
teardown_systemd_unit prexorcloud-controller
teardown_systemd_unit prexorcloud-daemon
echo

ohai "Layer 3/8 — install directories"
# /opt/prexorcloud/jre holds the wizard-managed Temurin JRE (tarball-installed,
# not via apt) — wiped here alongside the controller/daemon/dashboard trees.
remove_install_dir /opt/prexorcloud/controller
remove_install_dir /opt/prexorcloud/daemon
remove_install_dir /opt/prexorcloud/dashboard
remove_install_dir /opt/prexorcloud/jre
[[ -d /opt/prexorcloud ]] && rmdir /opt/prexorcloud 2>/dev/null || true
echo

ohai "Layer 4/8 — prexorctl + cosign binaries"
remove_binary
remove_cosign
echo

ohai "Layer 5/8 — CLI config"
remove_cli_config
echo

ohai "Layer 6/8 — Web-server vhosts (nginx / Caddy)"
remove_webserver_vhosts
echo

ohai "Layer 7/8 — apt sources + GPG keyrings"
remove_apt_sources
echo

ohai "Layer 8/8 — System MongoDB / Redis / nginx / Caddy packages"
PM="$(detect_pkg_manager)"
warn "This step removes MongoDB / Redis / nginx / Caddy from the entire system,"
warn "not just from PrexorCloud. Skip if anything else on this host shares them."
uninstall_mongodb "$PM"
uninstall_redis "$PM"
uninstall_webservers "$PM"
echo

ohai "Done. Verify with:"
echo "  systemctl list-unit-files 'prexorcloud-*'"
echo "  ls /opt/prexorcloud 2>/dev/null"
echo "  which prexorctl cosign mongod redis-server nginx caddy"
