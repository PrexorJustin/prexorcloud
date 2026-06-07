# Rotate Secrets

Routine rotation prevents long-lived credentials from accumulating
exposure. Schedule it; don't wait for an incident.

| Secret                              | Rotate every     | Why                                              |
| ----------------------------------- | ---------------- | ------------------------------------------------ |
| JWT signing secret                  | 90 days          | Compromise revokes all sessions otherwise        |
| Bootstrap admin password            | After first login (one-time) | Printed on first start; not meant to last |
| Operator user passwords             | Per your policy  | Standard hygiene                                 |
| mTLS daemon certificates            | 365 days (or before)  | Certificate-based identity                  |
| Controller CA                       | Every few years  | Re-issuing daemon certs is the cost              |
| MongoDB / Valkey credentials        | Per your policy  | Operator-managed; PrexorCloud just consumes URIs |
| Module-level signing trust root     | When an author rotates  | See [Module Trust Root](#module-trust-root) below |

## JWT Signing Secret

PrexorCloud supports dual-key rotation: the controller accepts tokens
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
2. Edit every controller's `controller.yml`. Move the current value
   from `jwtSecret` into `jwtPreviousSecrets`; set `jwtSecret` to the
   new value. Keep `jwtPreviousSecrets` populated for at least
   `jwtExpirationMinutes` (default 24h).
3. Restart each controller in turn:
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

If you suspect the old secret was compromised, **don't** wait the
rotation window — issue a forced sign-out instead:

```bash
# Revoke every active token by JTI bulk (audit-logged):
prexorctl auth revoke-all
```

The bulk revocation populates `prexor:v1:jwt:revoked:*` for every
non-expired token. Users re-login.

## Bootstrap Admin Password

Right after [`install.md`](install.md):

```bash
prexorctl login --controller https://localhost:8080
# Username: admin, Password: <from /etc/prexorcloud/config/.initial-admin-password>

prexorctl user set-password admin
# Enter a strong password.

# Then delete the bootstrap file.
sudo shred -u /etc/prexorcloud/config/.initial-admin-password
```

If the bootstrap file was checked into a backup before deletion,
treat the password as compromised and rotate all admin passwords.

## Operator User Passwords

```bash
prexorctl user set-password <username>
# Or, as the user themselves (when logged in):
prexorctl auth change-password
```

The login lockout policy protects against brute force: default 5
attempts / 15 min window / 15 min lockout, configurable under
`security.lockout`. Valkey-backed in production (shared across
controllers); in-memory in development.

## Daemon mTLS Certificate

Two flows: routine rotation (cert is about to expire) and emergency
revocation (cert is compromised).

### Routine Rotation

```bash
# Check expiry on the daemon host:
sudo prexorctl daemon cert-info

# When approaching expiry, drain the node first.
prexorctl node drain <node-id> --shutdown=false --timeout 5m

# On the daemon host, request a new certificate.
prexorctl token create --description "rotate-cert <node-id>" --ttl 1h
sudo prexorctl daemon rotate-cert --join-token <token>

# Bring the node back.
prexorctl node undrain <node-id>
```

The controller logs the issuance in the audit log
(`type=node.cert-issued`).

### Emergency Revocation

If a daemon's private key was leaked or the host was compromised:

```bash
# Revoke immediately. New connections fail at TLS; existing fail on
# the next RPC.
curl -X POST \
    -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/nodes/<node-id>/revoke-cert?ttlDays=365"
```

Or via REST permission scope `nodes.revoke-cert` (admin by default).
Then issue a fresh certificate via the routine flow above; the new
certificate is unrelated to the revoked one.

To list currently revoked certificates:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/nodes/revoked-certs"
```

To unrevoke (e.g. you revoked the wrong node):

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/nodes/<node-id>/unrevoke-cert"
```

## Controller CA

> For the full v1.1 procedure — including the two distinct CAs (daemon
> mTLS vs. the Raft cluster CA) and the exact on-disk material — see the
> dedicated [`ca-rotation.md`](ca-rotation.md). The steps below are the
> short version.

Rotating the CA invalidates every existing daemon certificate. Plan
for a maintenance window proportional to the number of daemons.

1. Take a backup. See [`backup.md`](backup.md).
2. Drain every daemon (in HA you can stagger).
3. Stop all controllers.
4. Generate a new CA:
   ```bash
   sudo -u prexorcloud /opt/prexorcloud/bin/prexorcloud-controller \
       ca rotate \
       --config /etc/prexorcloud/config/controller.yml
   ```
   This stores the new CA in `data/certs/` and pushes the public
   cert into Mongo for HA peers.
5. Start a single controller; verify `/system/ready`.
6. Re-join each daemon (see [`recover-mongo.md`](recover-mongo.md)
   §Scenario 4 §Step 4 for the rejoin flow). Daemons must run
   `prexorctl setup --role daemon --rejoin --join-token <token>`.
7. Start any HA peers.

## MongoDB / Valkey Credentials

PrexorCloud holds these only as URIs in `controller.yml`. Rotating:

1. Add the new credential to the database (Mongo: `db.createUser`;
   Valkey: `ACL SETUSER`).
2. Update each controller's `controller.yml`. Restart in turn.
3. Remove the old credential.

The controller does not need a graceful "two-credential" window —
restarts under HA are zero-downtime.

## Module Trust Root

> Full procedure (overlap rotation, emergency revocation, the cluster-
> config PATCH path, and why a restart is required) lives in
> [`module-trust-root-rotation.md`](module-trust-root-rotation.md). Short
> version below.

The signature verifier supports two formats:

- **`COSIGN_BUNDLE`** (recommended) — Cosign sign-blob bundles. Trust root
  can be PEM-bundled RSA / EC / Ed25519 keys (raw-keyed signing) or PKIX
  CA roots (embedded-cert signing). Optional offline Rekor SET enforcement
  via `modules.signing.rekor.policy=REQUIRE_SET` + `publicKey`.
- **`KEYED`** (legacy) — PEM-bundled keys with sidecar `.sig` files.

Rotate by:

1. Add the new key (or CA cert) to the trust root file (concatenate PEM blocks).
2. Restart controllers in turn.
3. Re-sign and re-upload modules with the new key.
4. Remove the old key from the trust root.
5. Restart controllers.

Modules signed only with the old key will fail-closed at install
after step 4. Don't skip step 3.

## Audit and Verify

After every rotation, confirm in the audit log:

```bash
prexorctl audit query \
    --since "1 hour ago" \
    --type-prefix "auth." \
    --type-prefix "node.cert-" \
    --type-prefix "ca."
```

(Audit CLI lands under M3; until then, query Mongo directly:
`db.audit_log.find({ createdAt: { $gt: ISODate(...) } }).sort({createdAt:-1})`.)

## Common Failures

| Symptom                                                  | Likely cause                                    | Fix                                                |
| -------------------------------------------------------- | ----------------------------------------------- | -------------------------------------------------- |
| All sessions kicked after JWT rotation                   | Forgot `jwtPreviousSecrets`                     | Restore old secret to `jwtPreviousSecrets`; restart |
| Daemon disconnects after cert rotation                   | Cert rotated without drain                      | Drain first; existing RPCs fail mid-flight otherwise |
| `nodes/revoke-cert` returns 403                          | Missing `nodes.revoke-cert` permission          | Grant via role; admin has it by default             |
| Module install fails after trust-root rotation           | Old signature still on jar                      | Re-sign with the new key and re-upload              |
| `prexorctl audit query` empty                            | Audit CLI not yet shipped                       | Use Mongo query above                                |

## Related

- [`recover-controller.md`](recover-controller.md), [`recover-redis.md`](recover-redis.md), [`recover-mongo.md`](recover-mongo.md).
- [`SECURITY.md`](../../SECURITY.md) — disclosure policy.
- `docs/security/threat-model.md` §4.1, §4.2.
