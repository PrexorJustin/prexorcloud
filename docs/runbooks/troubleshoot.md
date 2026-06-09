# Troubleshoot

A field guide for common failure modes. Start with the symptom in §1
and follow the link to the focused fix.

## 1. Symptom index

| Symptom                                                              | Section |
| -------------------------------------------------------------------- | ------- |
| Controller won't start                                               | §2      |
| `/system/ready` returns 503                                          | §3      |
| Login rejected with `Locked`                                         | §4      |
| Cannot connect (`Connection refused` from CLI / browser)             | §5      |
| Daemon shows `OFFLINE` or `UNHEALTHY`                                | §6      |
| Instance won't start                                                 | §7      |
| Group stuck in `DEPLOYING` / `ROLLING`                               | §8      |
| Dashboard SSE keeps reconnecting                                     | §9      |
| Module install rejected                                              | §10     |
| Module classloader leak warning                                      | §11     |
| Audit log entries missing                                            | §12     |
| Performance: slow REST / scheduler tick / deploy                     | §13     |
| Logs: where they are and how to read them                            | §14     |

## 2. Controller won't start

Start with the log:

```bash
sudo journalctl -u prexorcloud-controller --since "10 min ago" --no-pager
```

[`recover-controller.md`](recover-controller.md) §Step 3 lists common
ERROR signatures and their fixes. If none match, attach the log to your
support or issue report.

## 3. `/system/ready` returns 503

The endpoint returns 503 until every readiness check passes. Inspect
the body:

```bash
curl -s http://localhost:8080/api/v1/system/ready | jq
```

The response is `{"status": "READY"|"NOT_READY", "checks": {...}}`, where
each check is a boolean:

| `checks` field    | Bad value | Fix                                                          |
| ----------------- | --------- | ----------------------------------------------------------- |
| `mongo`           | `false`   | [`recover-mongo.md`](recover-mongo.md)                       |
| `redis`           | `false`   | [`recover-redis.md`](recover-redis.md)                       |
| `scheduler`       | `false`   | Controller still starting, or the scheduler died — see §2    |
| `platformModules` | `false`   | A platform module failed to start — see §10 and module logs  |

## 4. Login rejected with `Locked`

The account hit the lockout threshold. Default policy: 5 failed
attempts within 15 minutes, locked for 15 minutes. Configurable via
`security.lockout` (`maxAttempts`, `windowSeconds`, `lockoutSeconds`).

The controller answers a locked login with HTTP `429` and a
`Retry-After` header. Wait out the window — the lock clears on its own
after `security.lockout.lockoutSeconds`. There is no manual unlock
endpoint.

To see what tripped the lockout, search the controller log (failed
logins are not written to the audit log):

```bash
sudo journalctl -u prexorcloud-controller --since "1 hour ago" \
  | grep "failed login attempts"
```

If lockouts trigger broadly across users, see §13 (rate-limit /
under-load behavior) — your reverse proxy may be retransmitting
requests.

## 5. Cannot connect

```bash
curl -v http://localhost:8080/api/v1/system/health
```

| Curl outcome                              | Cause                                    | Fix                                       |
| ----------------------------------------- | ---------------------------------------- | ----------------------------------------- |
| `Connection refused`                      | Controller not running                   | `systemctl status prexorcloud-controller` |
| `Empty reply from server`                 | Crashed mid-response                     | [`recover-controller.md`](recover-controller.md) |
| `Forbidden by network.allowedSubnets`     | Client IP not in CIDR allowlist          | Add CIDR or fix reverse-proxy `X-Forwarded-For` handling |
| TLS handshake fails                       | Wrong port (HTTP vs HTTPS)               | REST is plain HTTP; put behind a TLS-terminating proxy |
| Times out                                 | Firewall                                 | Check `iptables`/`nftables`/cloud SG     |

## 6. Daemon `OFFLINE` / `UNHEALTHY`

```bash
prexorctl node info <node-id>
```

| Reason                    | Likely cause                              | Fix                                              |
| ------------------------- | ----------------------------------------- | ------------------------------------------------ |
| `mTLS handshake failed`   | Cert revoked or CA mismatch               | [`rotate-secrets.md`](rotate-secrets.md) §Daemon |
| `heartbeat timeout`       | Network blip, daemon overloaded           | Check daemon logs; check connectivity             |
| `reconnect backoff`       | Daemon process restarting in a loop       | `journalctl -u prexorcloud-daemon`                |
| `stale session`           | Daemon reconnected; old session not cleared | Wait one heartbeat; auto-clears                |

Daemon-side diagnostics:

```bash
sudo journalctl -u prexorcloud-daemon -n 200 --no-pager
sudo systemctl status prexorcloud-daemon

# Local node cert expiry + fingerprint (PKCS12 keystore; default config/security/node.p12).
openssl pkcs12 -in config/security/node.p12 -nokeys -passin file:config/security/.node-password \
  | openssl x509 -noout -enddate -fingerprint
```

## 7. Instance won't start

```bash
prexorctl instance info <id>           # reported state and reason
prexorctl crash list --group <group>   # crash reports for unexpected exits
```

| Reported reason                            | Cause                                    | Fix                                              |
| ------------------------------------------ | ---------------------------------------- | ------------------------------------------------ |
| `placement: no eligible node`              | Drained / cordoned / labels mismatch     | Uncordon a node or relax `placement.nodeSelector` |
| `start preparation failed: download`       | Template download failed                  | Check templates registry connectivity             |
| `start preparation failed: signature`      | Module signature invalid                  | Re-sign module or relax `modules.signing`         |
| `process exited code=137`                  | OOM-killed by host                        | Increase memory; tune `-Xmx`                      |
| `process exited code=139`                  | JVM segfault / native crash               | Check `crash list`; review JVM args               |
| Crash loop, threshold tripped              | Startup crashes hit `crashes.crashLoopThreshold` (default 3) | Pause group, fix root cause, then resume   |

## 8. Group stuck in `DEPLOYING` / `ROLLING`

```bash
prexorctl group info <group>
prexorctl deploy list <group>
```

A deployment is in flight. Common stalls:

- A node holding instances of this group went `OFFLINE` mid-deploy.
  Drain it explicitly: [`drain-node.md`](drain-node.md).
- A new instance won't start (see §7).
- The batch size is `1` and one instance is stuck. Raise the batch
  size if appropriate (`--batch-size`), or roll back and re-deploy
  (take the `<rev>` from `deploy list`):
  ```bash
  prexorctl deploy rollback <group> <rev>
  prexorctl deploy <group> --batch-size 3
  ```

## 9. Dashboard SSE keeps reconnecting

Open browser devtools → Network → `EventStream`. Look for:

| Pattern                                                  | Cause                                     | Fix                                                 |
| -------------------------------------------------------- | ----------------------------------------- | --------------------------------------------------- |
| `connect 200`, `closed` after ~30s                        | Reverse proxy idle timeout < 60s         | Configure proxy to allow long-lived SSE             |
| `connect 401`                                             | JWT expired                              | Re-login                                             |
| `connect 503`                                             | Controller not ready                      | §3                                                  |
| `connect ok`, no events for >30s                          | Heartbeat not arriving                   | Check controller log for SSE thread errors          |

The SSE client implements ticket-based auth, exponential backoff
(1s → 30s), and last-sequence recovery — see
[the event stream concept](../public/en/concepts/events.md).

## 10. Module install rejected

Check the response body (CLI prints it; for browser uploads, watch
network response).

| Error                                          | Cause                                        | Fix                                                 |
| ---------------------------------------------- | -------------------------------------------- | --------------------------------------------------- |
| `signature required`                           | `modules.signing.required=true`, no `.sig`   | Sign the jar; place sidecar `.sig` next to it       |
| `signature invalid`                            | Wrong key / tampered jar                     | Verify trust root; re-sign                           |
| `manifest schema invalid`                      | Older module-sdk version                     | Rebuild against current SDK                          |
| `capability cycle detected`                    | Module declares circular capability deps      | Refactor module declarations                         |
| `runtime version incompatible`                 | Module's `requires.controller` doesn't match  | Upgrade controller or use a compatible module version |

## 11. Module classloader leak

The classloader leak detector exposes Prometheus metrics
(`prexorcloud_module_classloader_leaked_total`,
`prexorcloud_module_classloader_pending`) and logs at WARN when a
module classloader survives unload.

```bash
# Inspect:
curl -s -H "Authorization: Bearer $TOKEN" \
    https://controller:8080/api/v1/modules/platform/leaked-classloaders \
  | jq

# Force cleanup as last resort.
curl -X POST -H "Authorization: Bearer $TOKEN" \
    https://controller:8080/api/v1/modules/platform/leaked-classloaders/force-cleanup
```

Force-cleanup attempts a programmatic GC + finalizer pass. If a leak
persists after force-cleanup, the module almost certainly holds a
reference to a thread, scheduled task, or static map. File a bug
against the module author with the leak metric output.

## 12. Audit log missing entries

The audit log lives in MongoDB collection `audit_log`. A 90-day TTL
index (`audit_ttl`, on the `createdAt` field) rotates it automatically.

Entries can disappear because:

- The TTL index trimmed them — anything older than 90 days is gone.
  Confirm: `db.audit_log.getIndexes()` — look for `audit_ttl` /
  `expireAfterSeconds`.
- The write never landed. Each audit entry is a single synchronous
  `insertOne`, not buffered. If MongoDB was unreachable at the moment
  the action ran, that one write failed then and the entry was never
  stored.
- A restore brought the collection back to an older state. Check the
  manifest of the most recent restore.

If you suspect a deliberate tamper, treat as a security incident
(see [`incident.md`](incident.md)).

## 13. Performance

Quick checks:

```bash
# Controller GC pressure.
sudo journalctl -u prexorcloud-controller | grep -i 'GC pause'

# Mongo slow ops.
mongosh "$MONGO_URI" --eval 'db.currentOp({"secs_running": {"$gt": 1}})'

# Valkey latency.
redis-cli -u "$REDIS_URI" --latency

# Scheduler tick duration (logged at DEBUG).
sudo journalctl -u prexorcloud-controller | grep 'scheduler tick'
```

| Symptom                              | Likely cause                              | Fix                                              |
| ------------------------------------ | ----------------------------------------- | ------------------------------------------------ |
| REST p95 >500ms                      | Controller CPU saturated                  | Add a controller (HA); profile hot path          |
| Scheduler tick >200ms                | Mongo slow                                | See [`recover-mongo.md`](recover-mongo.md) §Scenario 1 |
| Deploy batch very slow               | Batch size too small                      | Raise `--batch-size` (or the group's update-strategy default) |
| Dashboard sluggish                   | SSE replay catching up                    | Brief; clears within 30s after reconnect          |

A nightly perf-baseline job in CI surfaces drift > 25% as a soft signal
(see [benchmarks](../public/en/benchmarks.md)). Capture local numbers
and trend them in your own monitoring.

## 14. Logs: where and how

| Component       | Default              | Override                          |
| --------------- | -------------------- | --------------------------------- |
| Controller      | systemd journal      | `logging.format` = HUMAN / JSON; pipe to file via systemd `StandardOutput=` |
| Daemon          | systemd journal      | same                              |
| MC instance     | `data/instances/<id>/logs/latest.log` | server-side, per template         |
| Module          | controller log, prefixed `[module:<id>]`  | use `LoggerFactory.getLogger`     |

Useful filters:

```bash
# Errors only.
sudo journalctl -u prexorcloud-controller -p err --since today

# JSON parse for structured search (when logging.format=JSON).
sudo journalctl -u prexorcloud-controller -o cat --since today \
  | jq 'select(.level=="WARN" or .level=="ERROR")'

# Tail with timestamps.
sudo journalctl -u prexorcloud-controller -f -o short-iso
```

Daemon logs:

```bash
sudo journalctl -u prexorcloud-daemon -f -o short-iso
```

## When to escalate

If after 30 minutes of working through the symptom you still don't
know the cause, capture diagnostics (
[`recover-controller.md`](recover-controller.md) §Step 1) and follow
[`incident.md`](incident.md).

## Related

- [`incident.md`](incident.md)
- [`recover-controller.md`](recover-controller.md)
- [`recover-mongo.md`](recover-mongo.md), [`recover-redis.md`](recover-redis.md)
