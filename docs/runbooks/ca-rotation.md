# CA Rotation

Deep-dive companion to [`rotate-secrets.md` §Controller CA](rotate-secrets.md#controller-ca).
Read this when you are actually rotating a certificate authority — it
covers the two distinct CAs PrexorCloud runs and the exact on-disk
material involved.

> **There are two CAs. Do not conflate them.**
>
> | CA | Signs | Where it lives | Rotation blast radius |
> | --- | --- | --- | --- |
> | **Daemon mTLS CA** | controller↔daemon gRPC certs, the controller's own server cert | on disk: `config/security/ca.p12` (+ `ca.pem`, `.ca-password`) | every daemon cert is invalidated → every daemon must re-bootstrap |
> | **Cluster (Raft) CA** | controller↔controller Raft peer TLS | Raft state machine: `cluster-ca.crt` / `cluster-ca.key` (replicated, never on local disk) | every controller's peer cert; only relevant in HA (3/5-node) |

Both are EC (ECDSA P-256), self-signed, 3650-day validity. The daemon
mTLS CA is the one you rotate routinely; the cluster CA is minted once
on day-0 and effectively permanent for the life of the Raft group.

---

## 1. Daemon mTLS CA

### On-disk material (controller working directory)

| File | Purpose |
| --- | --- |
| `config/security/ca.p12` | PKCS#12 holding the CA private key + self-signed cert |
| `config/security/.ca-password` | password for `ca.p12` (owner-read-only) |
| `config/security/ca.pem` | PEM export of the CA cert — handed to daemons at bootstrap |
| `config/security/server.p12` | the controller's own server cert + key, signed by the CA |

On a systemd/native install these sit under the controller's working
directory (e.g. `/var/lib/prexorcloud/` or `/etc/prexorcloud/`,
whichever the unit file sets as `WorkingDirectory`). Paths are always
relative to that directory — there is no absolute hard-coding.

### Mechanism

The controller regenerates CA material **when it is absent at boot**:
`CertificateAuthority.loadOrCreate()` loads `ca.p12` if present, else
mints a fresh CA and writes it. `server.p12` is likewise re-issued only
if absent. There is no dedicated `ca rotate` subcommand in this build —
rotation is "remove the material, restart, let the controller re-mint".

### Procedure (single controller)

Rotating the CA invalidates **every** daemon certificate. Budget a
maintenance window proportional to the daemon count.

1. **Back up first.** See [`backup.md`](backup.md). Specifically copy
   the whole `config/security/` directory somewhere safe — this is your
   rollback.
2. **Drain every daemon** so no instances are mid-flight:
   ```bash
   prexorctl node list
   prexorctl node drain <node-id> --shutdown=false --timeout 5m   # repeat per node
   ```
3. **Stop the controller.**
   ```bash
   sudo systemctl stop prexorcloud-controller
   ```
4. **Remove the CA and server material** (keep `.ca-password` to reuse
   the same keystore password, or delete it too for a fresh one):
   ```bash
   cd <controller-working-dir>
   rm config/security/ca.p12 config/security/server.p12 config/security/ca.pem
   ```
5. **Start the controller.** It mints a new CA, a new server cert, and
   re-exports `ca.pem`. Confirm readiness:
   ```bash
   sudo systemctl start prexorcloud-controller
   curl -sk https://localhost:8080/system/ready
   ```
   The new CA fingerprint is logged at startup
   (`Stamped ... CA (fingerprint=...)` / `loadOrCreate`). Record it.
6. **Re-bootstrap every daemon** — each needs a new node cert signed by
   the new CA and the new `ca.pem`:
   ```bash
   prexorctl token create --description "ca-rotation rejoin <node-id>" --ttl 1h
   # on the daemon host:
   sudo prexorctl setup --role daemon --rejoin --join-token <token>
   ```
   The bootstrap exchange (`BootstrapService.exchangeJoinToken`) returns
   the daemon a fresh `node.p12` and writes the new `ca.pem` into the
   daemon's cert directory.
7. **Undrain** each node once it reconnects:
   ```bash
   prexorctl node undrain <node-id>
   ```

### HA note

In a 3/5-node cluster, rotate the daemon mTLS CA on the **leader**, then
restart followers so they reload `ca.pem` / `server.p12`. The cluster
(Raft) CA is unaffected — followers keep their Raft peer certs. Stagger
the daemon re-bootstrap so you never drain quorum-critical capacity all
at once.

---

## 2. Cluster (Raft) CA

You almost never rotate this. It is minted once during day-0 bootstrap
(`ClusterControlService.ensureClusterCa`) and stored **in the Raft state
machine** under `cluster-ca.crt` / `cluster-ca.key`, replicated to every
peer via the Raft log and InstallSnapshot — it is never written to local
disk. Joining controllers receive it (and a CA-signed leaf) through the
CSR exchange in the join flow.

Because the key lives only inside the replicated state machine, "rotating"
it means re-keying the entire Raft group's TLS identity, which is
equivalent to rebuilding the cluster. If you genuinely need to (suspected
key compromise of a controller host):

1. Treat it as a **majority-loss rebuild**. Follow
   [`recover-cluster.md`](recover-cluster.md) to reset to a single
   surviving member.
2. Wipe the Raft data directory (`data/raft`) on the controllers you are
   re-adding so they re-bootstrap their materials.
3. Re-join the other controllers with fresh join tokens — they go
   through the CSR exchange again and receive leaves under the new CA.

There is no zero-downtime path for cluster-CA rotation; it is a planned
cluster rebuild.

---

## Verify

```bash
# Daemon mTLS CA fingerprint the controller is serving:
openssl x509 -in config/security/ca.pem -noout -fingerprint -sha256

# Confirm each daemon reconnected under the new CA (audit log):
prexorctl audit query --since "1 hour ago" --type-prefix "node." --type-prefix "ca."
```

(Until the audit CLI ships, query Mongo:
`db.audit_log.find({ createdAt: { $gt: ISODate(...) } }).sort({createdAt:-1})`.)

## Common Failures

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Daemons stay disconnected after restart | not re-bootstrapped; old node cert no longer chains to new CA | run the `setup --role daemon --rejoin` flow per node |
| Controller mints a new CA but daemons still trust the old one | daemon's `ca.pem` not refreshed | re-bootstrap; the exchange rewrites the daemon `ca.pem` |
| New CA not minted on restart | `ca.p12` was not actually removed (wrong working dir) | `cd` to the unit's `WorkingDirectory`, remove, restart |
| Followers fail Raft TLS after cluster-CA tampering | cluster CA is in Raft state, not on disk — manual edits don't help | rebuild via [`recover-cluster.md`](recover-cluster.md) |
| Locked out after deleting `.ca-password` with `ca.p12` still present | `loadOrCreate` can't open the existing keystore | restore `.ca-password` from backup, or remove `ca.p12` too |

## Related

- [`rotate-secrets.md`](rotate-secrets.md) — the quick-reference summary and the full rotation cadence table.
- [`module-trust-root-rotation.md`](module-trust-root-rotation.md) — rotating the *module-signing* trust root (a different key entirely).
- [`recover-cluster.md`](recover-cluster.md) — Raft majority-loss recovery (needed for any cluster-CA rebuild).
- [`backup.md`](backup.md) / [`restore.md`](restore.md) — back up `config/security/` before you start.
