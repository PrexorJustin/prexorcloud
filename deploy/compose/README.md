# PrexorCloud — production compose stack

Reference Docker Compose v2 stack for a single-host PrexorCloud install.
Different from `deploy/compose.dev.yml`, which is a developer
convenience that exposes datastore ports on the host. This one keeps
Mongo + Valkey on a private network and reads its config from the files
in this directory.

## What you get

- `mongo` (durable state) and `valkey` (coordination store) on
  `prexor-internal`, **not exposed to the host**.
- `controller` reachable via `prexor-public` on the host ports declared
  in `.env`. mTLS is enforced on gRPC; HTTP is plaintext and should sit
  behind a TLS-terminating reverse proxy.
- One in-stack `daemon` for low-volume installs. Multi-host clusters add
  more daemons via `deploy/systemd/`.
- `dashboard` reachable on the host port from `.env`.
- Healthchecks on every service so `depends_on: { condition: service_healthy }`
  actually waits.

## First-time install

1. `cp .env.example .env && $EDITOR .env`
2. Edit `controller.yml`:
   - `security.jwtSecret`: `openssl rand -base64 48`
   - `security.initialAdminPassword`: any string; clear after first login
   - `network.allowedSubnets`: tighten to your daemon network ranges
   - `http.cors.allowedOrigins`: the dashboard origin you actually serve
3. `docker compose -f compose.yml up -d`
4. `docker compose logs -f controller` — wait for the readiness line.
5. Generate a daemon join token:
   `prexorctl node generate-token` (or via the dashboard).
6. Drop the token into `daemon.yml: security.joinToken` and
   `docker compose up -d daemon`.
7. Log into the dashboard, change the admin password, then clear
   `security.initialAdminPassword` from `controller.yml` and restart the
   controller.

## Operating

- **Logs:** `docker compose logs -f <service>`, or use
  `prexorctl logs controller --follow` against
  `http://<host>:8080`.
- **Backups:** `prexorctl backup create` writes manifests under the
  `controller-data` volume. See `docs/runbooks/backup.md`.
- **Restore:** `prexorctl restore <manifest> --dry-run` first, then
  drop `--dry-run`. See `docs/runbooks/restore.md`.
- **Upgrades:** `docker compose pull && docker compose up -d`. See
  `docs/runbooks/upgrade.md`.
- **Disaster recovery:** see `docs/public/en/operations/backups-and-dr.md` for RPO/RTO targets and the
  full restore drill.

## What this stack does not do

- TLS termination on the HTTP edge — front it with Caddy / nginx /
  Traefik. The HTTP port is plaintext on purpose.
- Off-host backups — `controller-data` is a Docker volume on the host.
  Snapshot the volume (or rsync `prexorctl backup` output) to off-host
  storage on whatever cadence your RPO requires.
- High availability — single controller. Active-active controller HA
  via the `controller_lease` is supported by the codebase but requires
  a second controller pointed at the same Mongo + Valkey, not in scope
  for this single-host reference.
