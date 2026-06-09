# Rotate secrets

Routine rotation prevents long-lived credentials from accumulating
exposure. Schedule it; don't wait for an incident.

| Secret                          | Rotate every                 | Why                                              |
| ------------------------------- | ---------------------------- | ------------------------------------------------ |
| JWT signing secret              | 90 days                      | Compromise revokes all sessions otherwise        |
| Bootstrap admin password        | After first login (one-time) | Printed on first start; not meant to last        |
| Operator user passwords         | Per your policy              | Standard hygiene                                 |
| mTLS Daemon certificates        | 365 days (or before)         | Certificate-based identity                       |
| Controller CA                   | Every few years              | Re-issuing Daemon certs is the cost              |
| MongoDB / Valkey credentials    | Per your policy              | Operator-managed; PrexorCloud consumes the URIs  |
| Module-level signing trust root | When an author rotates       | See [Module trust root](#module-trust-root) below |

## JWT signing secret

PrexorCloud supports dual-key rotation: the Controller accepts tokens
signed with the current secret **and** any secret listed in
`security.jwtPreviousSecrets` until those tokens expire naturally.

```yaml
# controller.yml
security:
  jwtSecret: "<new-base64-256-bit-secret>"
  jwtPreviousSecrets:
    - "<old-base64-256-bit-secret>"  # accepted until last token expires
  jwtExpirationMinutes: 1440         # default 24h
```

Procedure:

1. Generate a new secret (32 bytes ≥ 256 bits, base64 or hex):
   ```bash
   openssl rand -base64 32
   ```
2. Edit every Controller's `controller.yml`. Move the current value
   from `jwtSecret` into `jwtPreviousSecrets`; set `jwtSecret` to the
   new value. Keep `jwtPreviousSecrets` populated for at least
   `jwtExpirationMinutes` (default 24h).
3. Restart each Controller in turn:
   ```bash
   sudo systemctl restart prexorcloud-controller
   ```
   In HA, restart one at a time — the survivor accepts both old and
   new tokens during the rolling restart, so users stay logged in.
4. After `jwtExpirationMinutes`, remove the old entry from
   `jwtPreviousSecrets`. Restart again.
5. Verify with a fresh login:
   ```bash
   prexorctl logout && prexorctl login
   prexorctl status
   ```

If you suspect the old secret was compromised, don't wait the rotation
window. Set `jwtSecret` to a new value and leave the compromised secret
**out** of `jwtPreviousSecrets`, then restart each Controller. Every
token signed with the old secret then fails validation on its next
request, so all sessions end at once and users re-login.

## Bootstrap admin password

Right after [`install.md`](install.md):

```bash
# Log in as admin; the password is in
# /etc/prexorcloud/config/.initial-admin-password.
prexorctl login --controller https://localhost:8080

# Change the password — in the dashboard (Account -> Change password),
# or over REST with your bearer token:
curl -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"currentPassword":"<bootstrap>","newPassword":"<strong-new>"}' \
    https://localhost:8080/api/v1/auth/change-password

# Then delete the bootstrap file.
sudo shred -u /etc/prexorcloud/config/.initial-admin-password
```

If the bootstrap file was checked into a backup before deletion,
treat the password as compromised and rotate all admin passwords.

## Operator user passwords

Reset another user's password as an admin (needs the `users.update`
permission):

```bash
curl -X PATCH \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"password":"<new-password>"}' \
    https://controller:8080/api/v1/users/<username>
```

Or change your own password while logged in:

```bash
curl -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"currentPassword":"<old>","newPassword":"<new>"}' \
    https://controller:8080/api/v1/auth/change-password
```

The login lockout policy protects against brute force: default 5
attempts / 15 min window / 15 min lockout, configurable under
`security.lockout`. Valkey-backed in production (shared across
Controllers); in-memory in development.

## Daemon mTLS certificate

Two flows: routine rotation (the cert is about to expire) and emergency
revocation (the cert is compromised).

### Routine rotation

A Daemon certificate rotates by redeeming a fresh join token — the same
exchange that first enrolled the node. The Daemon reloads the new key
material from disk without a Controller restart. Plan it before the
cert's validity expires (365 days by default).

```bash
# Drain the node so its instances reschedule elsewhere.
prexorctl node drain <node-id>

# Issue a fresh join token on the controller.
prexorctl token create --node <node-id> --ttl 1h

# On the daemon host, re-run setup and paste the new token. The wizard
# redeems it and installs the new certificate.
sudo prexorctl setup
#   non-interactive equivalent:
#   sudo prexorctl setup --non-interactive --component daemon \
#       --daemon-node-id <node-id> \
#       --daemon-controller-host <controller-host> \
#       --daemon-join-token <token>

# Bring the node back once it reconnects.
prexorctl node undrain <node-id>
```

The Controller records the join-token issuance in the audit log
(`type=token.create`).

### Emergency revocation

If a Daemon's private key was leaked or the host was compromised:

```bash
# Revoke immediately. New connections fail at TLS; existing connections
# fail on the next RPC.
curl -X POST \
    -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/nodes/<node-id>/revoke-cert?ttlDays=365"
```

Revocation needs the `nodes.revoke-cert` permission (ADMIN holds it by
default). Then issue a fresh certificate via the routine flow above; the
new certificate is unrelated to the revoked one.

To list currently revoked certificates:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/nodes/revoked-certs"
```

To unrevoke (for example, you revoked the wrong node):

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/nodes/<node-id>/unrevoke-cert"
```

## Controller CA

> For the full v1.1 procedure — including the two distinct CAs (Daemon
> mTLS vs. the Raft cluster CA) and the exact on-disk material — see the
> dedicated [`ca-rotation.md`](ca-rotation.md). The steps below are the
> short version.

Rotating the CA invalidates every existing Daemon certificate. Plan
for a maintenance window proportional to the number of Daemons.

1. Take a backup. See [`backup.md`](backup.md).
2. Drain every Daemon (in HA you can stagger).
3. Stop all Controllers.
4. Remove the CA and server material so the Controller re-mints it on
   the next start — there is no `ca rotate` subcommand:
   ```bash
   cd <controller-working-dir>
   rm config/security/ca.p12 config/security/server.p12 config/security/ca.pem
   ```
5. Start a single Controller. It mints a new CA and server cert and
   re-exports `ca.pem`. Verify readiness:
   ```bash
   curl -sk https://localhost:8080/api/v1/system/ready
   ```
6. Re-bootstrap each Daemon with a fresh join token (see the
   [Daemon mTLS certificate](#daemon-mtls-certificate) routine flow).
   Each Daemon receives a new `node.p12` and the new `ca.pem`.
7. Start any HA peers.

## MongoDB / Valkey credentials

PrexorCloud holds these only as URIs in `controller.yml`. To rotate:

1. Add the new credential to the database (Mongo: `db.createUser`;
   Valkey: `ACL SETUSER`).
2. Update each Controller's `controller.yml`. Restart in turn.
3. Remove the old credential.

The Controller does not need a graceful two-credential window —
restarts under HA are zero-downtime.

## Module trust root

> Full procedure (overlap rotation, emergency revocation, the cluster-
> config PATCH path, and why a restart is required) lives in
> [`module-trust-root-rotation.md`](module-trust-root-rotation.md). Short
> version below.

The signature verifier supports two formats:

- **`COSIGN_BUNDLE`** (recommended) — Cosign sign-blob bundles. The trust
  root can be PEM-bundled RSA / EC / Ed25519 keys (raw-keyed signing) or
  PKIX CA roots (embedded-cert signing). Optional offline Rekor SET
  enforcement via `modules.signing.rekor.policy=REQUIRE_SET` + `publicKey`.
- **`KEYED`** (legacy) — PEM-bundled keys with sidecar `.sig` files.

To rotate:

1. Add the new key (or CA cert) to the trust root file (concatenate PEM blocks).
2. Restart Controllers in turn.
3. Re-sign and re-upload modules with the new key.
4. Remove the old key from the trust root.
5. Restart Controllers.

Modules signed only with the old key fail-closed at install after step 4.
Don't skip step 3.

## Audit and verify

After every rotation, confirm in the audit log:

```bash
prexorctl audit query \
    --since "1 hour ago" \
    --type-prefix "user." \
    --type-prefix "token." \
    --type-prefix "node."
```

(The audit CLI is not shipped yet; until then, query Mongo directly:
`db.audit_log.find({ createdAt: { $gt: ISODate(...) } }).sort({ createdAt: -1 })`.)

## Common failures

| Symptom                                        | Likely cause                           | Fix                                                  |
| ---------------------------------------------- | -------------------------------------- | ---------------------------------------------------- |
| All sessions kicked after JWT rotation         | Forgot `jwtPreviousSecrets`            | Restore old secret to `jwtPreviousSecrets`; restart  |
| Daemon disconnects after cert rotation         | Cert rotated without drain             | Drain first; otherwise existing RPCs fail mid-flight |
| `nodes/revoke-cert` returns 403                | Missing `nodes.revoke-cert` permission | Grant via role; ADMIN has it by default              |
| Module install fails after trust-root rotation | Old signature still on jar             | Re-sign with the new key and re-upload               |
| `prexorctl audit query` not found              | Audit CLI not yet shipped              | Use the Mongo query above                            |

## Related

- [`recover-controller.md`](recover-controller.md), [`recover-redis.md`](recover-redis.md), [`recover-mongo.md`](recover-mongo.md).
- [`SECURITY.md`](../../SECURITY.md) — disclosure policy.
- `docs/security/threat-model.md` §4.1, §4.2.
