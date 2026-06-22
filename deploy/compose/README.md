# PrexorCloud — production compose stack

Reference Docker Compose v2 stack for a single-host PrexorCloud install.
Different from `deploy/compose.dev.yml`, which is a developer
convenience that exposes datastore ports on the host. This one keeps
Mongo on a private network and reads its config from the files
in this directory.

## How it fits

Two networks. Datastores stay on `prexor-internal` and never touch the host;
only the controller's HTTP/gRPC and the dashboard cross onto `prexor-public`.

```
                         host ports (.env)
                          │           │
                  ┌───────┴───┐   ┌───┴───────┐
   prexor-public  │ controller│   │ dashboard │
                  └───┬───┬───┘   └───────────┘
                      │   │ gRPC (mTLS)
            ┌─────────┘   └─────────┐
            │ prexor-internal       │
        ┌───┴───┐               ┌───┴────┐
        │ mongo │               │ daemon │  (in-stack, one)
        └───────┘               └────────┘
       durable state          spawns instances
```

Multi-host installs add more daemons on other machines via
[`deploy/systemd/`](../systemd/README.md); they dial the controller's public
gRPC port, not the internal datastore network.

## What you get

- `mongo` (durable state) on `prexor-internal`, **not exposed to the host**.
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
5. Mint a daemon join token:
   `prexorctl token create --node <node-id>` (or via the dashboard).
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
- High availability — single controller. Multi-controller HA ships in
  v1.1 as an embedded Raft (Apache Ratis) control plane: stand up a 3- or
  5-controller quorum and join them with controller tokens. That's a
  multi-host topology, out of scope for this single-host reference — see
  [`upgrade-v1.0-to-v1.1.md`](../../docs/runbooks/upgrade-v1.0-to-v1.1.md).

## License

Apache 2.0 — see [LICENSE](../../LICENSE).
