# CA rotation

Deep-dive companion to [`rotate-secrets.md` §Controller CA](rotate-secrets.md#controller-ca).
Read this when you are actually rotating a certificate authority — it
covers the two distinct CAs PrexorCloud runs and the exact on-disk
material involved.

> **There are two CAs. Do not conflate them.**
>
> | CA | Signs | Where it lives | Rotation blast radius |
> | --- | --- | --- | --- |
> | **Daemon mTLS CA** | Controller↔Daemon gRPC certs, the Controller's own server cert | on disk: `config/security/ca.p12` (+ `ca.pem`, `.ca-password`) | invalidates every Daemon cert → every Daemon must re-bootstrap |
> | **Cluster (Raft) CA** | Controller↔Controller Raft peer TLS | Raft state machine: `cluster-ca.crt` / `cluster-ca.key` (replicated, never on local disk) | every Controller's peer cert; only relevant in HA (3/5-node) |

Both are EC (ECDSA P-256), self-signed, 3650-day validity. You rotate the
Daemon mTLS CA routinely; the cluster CA is minted once on day-0 and is
effectively permanent for the life of the Raft group.

---

## 1. Daemon mTLS CA

### On-disk material (Controller working directory)

| File | Purpose |
| --- | --- |
| `config/security/ca.p12` | PKCS#12 holding the CA private key + self-signed cert |
| `config/security/.ca-password` | password for `ca.p12` (owner-read-only) |
| `config/security/ca.pem` | PEM export of the CA cert — handed to Daemons at bootstrap |
| `config/security/server.p12` | the Controller's own server cert + key, signed by the CA |

On a systemd/native install these sit under the Controller's working
directory — `/opt/prexorcloud/controller` by default, whichever the unit
file sets as `WorkingDirectory`. Paths are always relative to that
directory; nothing is absolute-hard-coded.

### Mechanism

The Controller regenerates CA material **when it is absent at boot**:
`CertificateAuthority.loadOrCreate()` loads `ca.p12` if present, else
mints a fresh CA and writes it. It re-issues `server.p12` the same way,
only if absent. There is no dedicated `ca rotate` subcommand in this
build — rotation means "remove the material, restart, let the Controller
re-mint".

### Procedure (single Controller)

Rotating the CA invalidates **every** Daemon certificate. Budget a
maintenance window proportional to the Daemon count.

1. **Back up first.** See [`backup.md`](backup.md). Copy the whole
   `config/security/` directory somewhere safe — this is your rollback.
2. **Drain every Daemon** so no Instances are mid-flight:
   ```bash
   prexorctl node list
   prexorctl node drain <node-id>   # repeat per node
   ```
3. **Stop the Controller.**
   ```bash
   sudo systemctl stop prexorcloud-controller
   ```
4. **Remove the CA and server material** (keep `.ca-password` to reuse
   the same keystore password, or delete it too for a fresh one):
   ```bash
   cd <controller-working-dir>
   rm config/security/ca.p12 config/security/server.p12 config/security/ca.pem
   ```
5. **Start the Controller.** It mints a new CA, a new server cert, and
   re-exports `ca.pem`. Confirm readiness:
   ```bash
   sudo systemctl start prexorcloud-controller
   curl -s http://localhost:8080/ready
   ```
   The Controller logs the new CA fingerprint at startup
   (`Stamped ... CA (fingerprint=...)` / `loadOrCreate`). Record it.
6. **Re-bootstrap every Daemon** — each needs a new node cert signed by
   the new CA and the new `ca.pem`:
   ```bash
   prexorctl token create --node <node-id> --ttl 1h
   # on the Daemon host, re-run setup, pick the Daemon component, and paste the new join token:
   sudo prexorctl setup
   ```
   The bootstrap exchange (`BootstrapService.exchangeJoinToken`) returns
   the Daemon a fresh `node.p12` and writes the new `ca.pem` into the
   Daemon's cert directory.
7. **Undrain** each node once it reconnects:
   ```bash
   prexorctl node undrain <node-id>
   ```

### HA note

In a 3/5-node cluster, rotate the Daemon mTLS CA on the **leader**, then
restart followers so they reload `ca.pem` / `server.p12`. The cluster
(Raft) CA is unaffected — followers keep their Raft peer certs. Stagger
the Daemon re-bootstrap so you never drain quorum-critical capacity all
at once.

---

## 2. Cluster (Raft) CA

You almost never rotate this. It is minted once during day-0 bootstrap
(`ClusterControlService.ensureClusterCa`) and stored **in the Raft state
machine** under `cluster-ca.crt` / `cluster-ca.key`, replicated to every
peer via the Raft log and InstallSnapshot — it is never written to local
disk. Joining Controllers receive it (and a CA-signed leaf) through the
CSR exchange in the join flow.

Because the key lives only inside the replicated state machine, "rotating"
it means re-keying the entire Raft group's TLS identity, which is
equivalent to rebuilding the cluster. If you genuinely need to (suspected
key compromise of a Controller host):

1. Treat it as a **majority-loss rebuild**. Follow
   [`recover-cluster.md`](recover-cluster.md) to reset to a single
   surviving member.
2. Wipe the Raft data directory (`data/raft`) on the Controllers you are
   re-adding so they re-bootstrap their materials.
3. Re-join the other Controllers with fresh join tokens — they go
   through the CSR exchange again and receive leaves under the new CA.

There is no zero-downtime path for cluster-CA rotation; it is a planned
cluster rebuild.

---

## Verify

```bash
# Daemon mTLS CA fingerprint the Controller is serving:
openssl x509 -in config/security/ca.pem -noout -fingerprint -sha256
```

Confirm each Daemon reconnected under the new CA. This build has no audit
CLI yet, so query the audit log in Mongo directly — it is the `audit_log`
collection, indexed on `createdAt`:

```javascript
db.audit_log.find({ createdAt: { $gt: ISODate("…") } }).sort({ createdAt: -1 })
```

## Common failures

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Daemons stay disconnected after restart | not re-bootstrapped; old node cert no longer chains to new CA | re-run `prexorctl setup` and pick the Daemon component per node |
| Controller mints a new CA but Daemons still trust the old one | the Daemon's `ca.pem` was not refreshed | re-bootstrap; the exchange rewrites the Daemon `ca.pem` |
| New CA not minted on restart | `ca.p12` was not actually removed (wrong working dir) | `cd` to the unit's `WorkingDirectory`, remove, restart |
| Followers fail Raft TLS after cluster-CA tampering | cluster CA is in Raft state, not on disk — manual edits don't help | rebuild via [`recover-cluster.md`](recover-cluster.md) |
| Locked out after deleting `.ca-password` with `ca.p12` still present | `loadOrCreate` can't open the existing keystore | restore `.ca-password` from backup, or remove `ca.p12` too |

## Related

- [`rotate-secrets.md`](rotate-secrets.md) — the quick-reference summary and the full rotation cadence table.
- [`module-trust-root-rotation.md`](module-trust-root-rotation.md) — rotating the *module-signing* trust root (a different key entirely).
- [`recover-cluster.md`](recover-cluster.md) — Raft majority-loss recovery (needed for any cluster-CA rebuild).
- [`backup.md`](backup.md) / [`restore.md`](restore.md) — back up `config/security/` before you start.
