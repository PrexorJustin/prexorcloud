---
title: Setup + Auth
description: prexorctl setup, login, logout, config, and token — first-contact commands for installing a controller or daemon and authenticating an operator.
---

These commands cover the lifecycle from a fresh host all the way to a
logged-in operator session: bare-metal installs, Compose deployments,
join-token issuance, and storing the controller URL plus auth token in
`~/.config/prexorctl/config.json`.

## What you'll learn

- The native and Compose install paths exposed by `prexorctl setup`.
- How `prexorctl login` exchanges credentials for a session token.
- How to issue and revoke node join tokens for `BootstrapService`.
- Where the CLI keeps its state and how to override it.

## `prexorctl setup`

Interactive (or fully non-interactive) installer for the controller and
daemon. Detects the OS package manager, optionally installs Java 21 +
MongoDB + Valkey, downloads the latest release, generates configuration,
and registers a systemd unit (native) or Compose project.

```bash
prexorctl setup
sudo prexorctl setup --component controller --install-mode native
sudo prexorctl setup --component daemon \
    --install-mode native \
    --daemon-controller-host controller.example.com \
    --daemon-controller-grpc-port 9090 \
    --daemon-join-token prxn_xxx \
    --non-interactive
```

Flags:

- `--browser` — open a loopback wizard at `127.0.0.1:9100` instead of
  using the TTY prompts. Pair with `--public` for token-protected HTTPS
  on a non-loopback bind.
- `--non-interactive` — fail rather than prompt; every value must be
  supplied via flags.
- `--component=controller|daemon` — what to install.
- `--install-mode=native|compose` — systemd + distro packages, or a
  generated Compose project.
- `--service-mode=prompt|enable|disable` — whether to register and
  enable the systemd unit at the end.
- `--startup-validation-mode=prompt|enable|disable` — whether to run the
  controller's startup validation immediately after install.
- Controller flags: `--controller-install-dir`, `--controller-mongo-mode`,
  `--controller-mongo-uri`, `--controller-redis-mode`, `--controller-redis-uri`,
  `--controller-http-port`, `--controller-grpc-port`, `--controller-cors-origin`.
- Daemon flags: `--daemon-install-dir`, `--daemon-node-id`,
  `--daemon-controller-host`, `--daemon-controller-grpc-port`,
  `--daemon-join-token`.

Native installs that provision packages or register services must run as
root.

## `prexorctl login`

Exchange username + password for a controller-issued session token. The
token is saved alongside the resolved controller URL in
`~/.config/prexorctl/config.json` (`0600`).

```bash
prexorctl login
prexorctl login --controller https://controller.example.com:8080
```

Flags: none — the form prompts for any field that isn't already
configured.

## `prexorctl logout`

Removes the stored auth token. Leaves the controller URL configured so
you can re-`login` without retyping it.

```bash
prexorctl logout
```

## `prexorctl config`

Inspect or modify the on-disk config. Keys: `controller`, `token`.

```bash
prexorctl config view
prexorctl config set controller https://controller.example.com:8080
prexorctl config set token prx_xxx
prexorctl config unset token
```

`config view` prints the masked token plus the path to the config file
on disk. With `--json` it returns the same shape as a script-friendly
JSON document; the stored token is masked there too.

## `prexorctl token`

Manage one-time **join tokens** that the daemon's bootstrap flow trades
for an mTLS certificate. Tokens are tracked in the controller and
listed by id, never raw value.

### `token create`

```bash
prexorctl token create --ttl 1h
prexorctl token create --node node-2 --ttl 24h
```

Flags:

- `--ttl <duration>` — Go-style duration (`30m`, `1h`, `24h`). Default `1h`.
- `--node <id>` — bind the token to a specific node id; rejects bootstrap
  attempts from any other node id.

The output includes the **raw token string** exactly once. Store it
somewhere durable; the controller persists only its hash.

### `token list`

```bash
prexorctl token list
```

Lists token ids, target node bindings, expiry timestamps, and current
status (`ACTIVE`, `EXPIRED`, `REVOKED`, `CONSUMED`).

### `token revoke`

```bash
prexorctl token revoke <token-id>
```

Revokes a token by its id (not the raw token string). Future bootstrap
attempts using a revoked token are rejected by the controller.

## Next up

- [Cluster commands](/reference/cli/cluster/) — `node`, `status`, `version`.
- [Installation guide](/getting-started/installation/) — full walkthrough that
  drives `setup` end-to-end.
- [Auth concepts](/concepts/security/) — how login tokens fit
  into the RBAC model.
