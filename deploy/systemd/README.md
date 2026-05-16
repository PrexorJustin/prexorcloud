# PrexorCloud — systemd units

Reference unit files for running the controller and daemon directly on
the host (no containers). Useful for:

- Multi-host clusters where one daemon runs per machine.
- Operators who already have Mongo / Valkey under systemd and prefer to
  keep the orchestrator next to them.
- Air-gapped installs where pulling images is harder than dropping a jar.

## What the units assume

```
/opt/prexorcloud/
├── controller/
│   ├── PrexorCloudController.jar
│   ├── config/
│   │   ├── controller.yml
│   │   └── security/        ← certs, written by the controller
│   ├── data/
│   ├── templates/
│   ├── modules/
│   └── logs/
└── daemon/
    ├── PrexorCloudDaemon.jar
    ├── config/
    │   ├── daemon.yml
    │   └── security/
    ├── instances/
    ├── cache/
    └── logs/
```

A single `prexorcloud` system user owns the trees. The units run
unprivileged with `NoNewPrivileges=true` and `ProtectSystem=strict`.

## Java version

The units invoke `/usr/bin/java`. Both processes need **Java 25+** with
the same `--enable-preview` / `--enable-native-access` flags the
Dockerfiles use. On Debian/Ubuntu, install `temurin-25-jdk` from
Adoptium. If `java` isn't 25 in `$PATH`, edit `ExecStart=` in the unit.

## Install

```bash
# As root:
useradd --system --home /opt/prexorcloud/controller --shell /usr/sbin/nologin prexorcloud

# Drop the jars and configs in place, then:
chown -R prexorcloud:prexorcloud /opt/prexorcloud
cp deploy/systemd/prexorcloud-controller.service /etc/systemd/system/
cp deploy/systemd/prexorcloud-daemon.service /etc/systemd/system/

systemctl daemon-reload
systemctl enable --now prexorcloud-controller
# After enrolling a join token (see below):
systemctl enable --now prexorcloud-daemon
```

## Daemon enrolment

The first time a daemon starts, it has no certificate. It needs a
single-use **join token** from the controller:

```bash
prexorctl node generate-token --node-id <unique-id>
# → prints a token; drop into daemon.yml under security.joinToken
systemctl start prexorcloud-daemon
```

The daemon consumes the token, receives an mTLS certificate signed by
the controller's CA, and clears the token from the config file. Restarts
after this point are token-free.

Rotate certs and revoke nodes per `docs/runbooks/rotate-secrets.md`.

## Why the daemon unit is more permissive than the controller

The daemon spawns Minecraft server processes via `ProcessBuilder`. Those
children need network access, `/tmp`, and a normal filesystem view.
Tightening `RestrictNamespaces` / `MemoryDenyWriteExecute` etc. on the
daemon unit will silently break running instances. Process-level
isolation (cgroups, containers) is a v2 conversation.

## Logs

systemd units write to journald:

```
journalctl -u prexorcloud-controller -f
journalctl -u prexorcloud-daemon -f
```

The controller also writes structured logs under `logs/` (consumed by
`prexorctl logs controller`). The daemon writes per-instance logs under
`instances/<id>/logs/` and forwards a ring of recent lines to the
controller via the gRPC log channel (consumed by
`prexorctl logs daemon <node-id>`).
