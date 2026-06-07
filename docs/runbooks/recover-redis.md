# Recover from Coordination Store Loss

The coordination store (Valkey by default; Redis-compatible) holds
ephemeral state — leases, JWT revocations, rate-limit windows, login
lockout counters, SSE replay buffers, per-module ephemeral storage,
short-lived tickets. Durable platform state lives in MongoDB and is
unaffected by Valkey loss.

This runbook covers three scenarios:

1. Valkey is reachable but slow / partial / corrupt.
2. Valkey is unreachable.
3. Valkey was wiped (data lost).

## Symptoms

| Signal                                                    | Likely cause                              |
| --------------------------------------------------------- | ----------------------------------------- |
| `/system/ready` reports `coordination.store=unavailable`  | Connectivity or auth failure              |
| `/system/ready` 200 but mutations hang                    | Lease store slow                          |
| Controller log: `lease acquire timeout`                   | Network partition or Valkey overload      |
| Login lockouts reset for everyone                         | Valkey wiped                              |
| SSE clients get full re-deltas instead of replay          | Replay buffer empty                       |
| Logged-out tokens become valid again                      | Revocation set lost                       |

## What Loss of Valkey Means

By design, the coordination store is **soft state**. Losing it does
not corrupt the platform. The controller will:

- Refuse mutations until `coordination.store` returns to `available`
  (in production profile).
- Re-acquire leases from scratch when Valkey returns.
- Rebuild replay buffers from the live event stream.
- Forget JWT revocations until they expire naturally (worst case:
  `security.jwtExpirationMinutes`, default 1440 / 24 hours).
- Forget login lockout counters; failed attempts reset to zero.
- Forget pending SSE tickets; clients will reconnect via fresh tickets.

Per-module ephemeral storage **is** lost. Modules that use Valkey as a
durable store are misconfigured — they should use Mongo for durable
data. Document this when reviewing third-party modules.

## Scenario 1 — Valkey Slow / Partial

```bash
# Check Valkey health.
redis-cli -u "$REDIS_URI" PING
redis-cli -u "$REDIS_URI" --latency
redis-cli -u "$REDIS_URI" INFO replication
redis-cli -u "$REDIS_URI" INFO clients
```

Common issues:

- **Memory pressure** — `INFO memory` shows `used_memory_peak` near
  `maxmemory`. Apply an eviction policy that's safe for PrexorCloud:
  ```
  CONFIG SET maxmemory-policy volatile-lru
  ```
  All PrexorCloud keys carry TTLs, so `volatile-lru` evicts oldest
  TTL'd keys first. **Never** use `noeviction` (writes will fail) or
  `allkeys-*` (will evict no-TTL operator keys you might be sharing).
- **Slow log** — `redis-cli -u "$REDIS_URI" SLOWLOG GET 25`. If
  `KEYS *` shows up, that's not from PrexorCloud — the controller uses
  `SCAN`. Find the rogue client.
- **Persistence stalls** — `INFO persistence`. If `rdb_bgsave_in_progress=1`
  for minutes, disk is too slow for the working set.

After fixing, the controller's status should clear within ~10s.

## Scenario 2 — Valkey Unreachable

```bash
# From the controller host:
redis-cli -u "$REDIS_URI" PING
# (Connection refused / timed out / NOAUTH)
```

In production profile (`runtime.profile=production`), the controller
will refuse to accept mutations and respond `503` on `/system/ready`.
Reads served from Mongo continue.

Steps:

1. Restore connectivity. Most common causes:
   - Valkey systemd unit stopped (`systemctl status valkey`).
   - Network ACL change blocked the controller subnet.
   - Auth password rotated without updating `controller.yml`.
2. Once Valkey is back, the controller picks it up automatically on the
   next probe (5s interval). No restart required.
3. Verify:
   ```bash
   curl -fs http://localhost:8080/api/v1/system/ready
   ```

If Valkey will be down for an extended window, do **not** switch the
controller to `runtime.profile=development` to "keep it running" —
development mode is documented as not-HA, not-persistent, and not
production-safe (see `docs/public/en/internals/architecture.md` §3). Wait it out, or
provision a replacement Valkey:

```bash
# Quick replacement on the same host:
sudo apt-get install valkey-server
sudo systemctl enable --now valkey

# Update controller.yml if URI changed.
sudo systemctl restart prexorcloud-controller
```

Optionally restore from a recent Valkey backup if you have one and the
loss matters (see [`backup.md`](backup.md) §Valkey).

## Scenario 3 — Valkey Wiped

Valkey is up but empty (e.g. fresh container started, RDB file
deleted, `FLUSHALL` run by mistake).

You don't need to recover anything. The controller rebuilds:

1. Acquires fresh leases as it ticks the scheduler.
2. Builds new SSE replay sequences (clients get a one-time gap; they'll
   reconnect via the standard reconnect protocol — the lower-bounded
   replay window may be empty, but real-time delivery is unaffected).
3. Forgets JWT revocations and login lockouts (see "What Loss of
   Valkey Means" above for the security impact).
4. Per-module Valkey-only storage is gone.

Optional: rotate the JWT signing secret to invalidate any token whose
revocation was lost. See [`rotate-secrets.md`](rotate-secrets.md).

## Eviction Policy and Hardening

To prevent silent data loss in steady state:

```
maxmemory <appropriate-size>
maxmemory-policy volatile-lru
appendonly yes              # AOF preferred over RDB-only for ephemeral state
appendfsync everysec
```

Document the chosen policy in your operator notes; the install runbook
links here.

## Common Failures

| Symptom                                                    | Likely cause                                | Fix                                                |
| ---------------------------------------------------------- | ------------------------------------------- | -------------------------------------------------- |
| Controller log: `Connection refused` after install         | Valkey not enabled at boot                  | `sudo systemctl enable --now valkey`               |
| Controller log: `WRONGPASS`                                | Auth changed without config update          | Update `redis.uri` to include `:password@`         |
| Controller log: `READONLY You can't write against a read only replica` | Pointed at a replica instead of primary | Update `redis.uri`                                |
| Latency >100ms intermittently                              | Persistence fsync on slow disk              | Move Valkey RDB/AOF to faster disk                 |
| `Coordination store unavailable` flaps                     | Network MTU mismatch / NAT timeout          | Check long-lived connection timeouts on path       |
| Clients hang on SSE reconnect                              | Replay window is empty after wipe           | Expected; clients receive a one-time full state    |

## Related

- [`recover-controller.md`](recover-controller.md)
- [`backup.md`](backup.md) §Valkey
- [`rotate-secrets.md`](rotate-secrets.md) — when wipe means you should rotate JWT.
- `docs/public/en/internals/architecture.md` §3 — what the development profile preserves vs. degrades.
