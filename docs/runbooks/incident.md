# Incident Response

What an on-call operator does between "page" and "post-mortem." The
goal of this runbook is to make first response **fast and boring**:
known signals, known steps, known escalation path.

## 1. Severity

| Severity | Definition                                                                 | Page? | Target first response |
| -------- | -------------------------------------------------------------------------- | ----- | --------------------- |
| SEV-1    | Player-facing outage. Cluster cannot start instances; >50% of running instances inaccessible; auth completely broken. | Yes  | 5 min                 |
| SEV-2    | Major degradation. One group cannot deploy; HA not converging; high error rate. | Yes  | 15 min                |
| SEV-3    | Limited impact. One node offline; one module crash-looping; slow deploys.    | No, ticket | Next business day |
| SEV-4    | Cosmetic / non-prod. Dashboard typo; stale audit entry.                       | No    | Triage weekly         |

If unsure, page. The cost of one false page is much smaller than the
cost of a missed SEV-1.

## 2. Page-Worthy Signals

Hook these to your alert pipeline. The controller exposes Prometheus
metrics on `/metrics`; build dashboards against the series listed in
`MetricsCollector.java`.

| Signal                                                            | Severity | Source                                       |
| ----------------------------------------------------------------- | -------- | -------------------------------------------- |
| `/api/v1/system/ready` 503 for >2 min                             | SEV-1    | External health probe                        |
| `state.store.available=false` for >2 min                          | SEV-1    | Same                                         |
| `coordination.store.available=false` for >5 min                   | SEV-2    | Same                                         |
| Controller process exits and restarts >3× in 5 min                | SEV-1    | systemd `OnFailure=`                          |
| All controllers report no acquired leases for >60s                | SEV-1    | Metrics                                       |
| `prexorcloud.module.classloader.leaked` rate increases            | SEV-3    | Metrics                                       |
| Daemon `OFFLINE` for >5 min on >2 nodes                           | SEV-2    | `prexorctl node list` watcher                  |
| MC instance crash rate >threshold per group                       | SEV-2/3  | `prexorctl crash list`; `crashes.threshold` triggered |
| Authentication failure rate >baseline by 10×                      | SEV-2    | Metrics; could be brute-force                 |
| Login lockout rate >baseline by 10×                               | SEV-2    | Metrics                                       |
| Audit log entries gap >5 min during business hours                | SEV-2    | External diff against expected event rate     |
| Disk free <10% on controller / Mongo / Valkey host                | SEV-2    | Host metrics                                  |
| TLS certificate <14 days from expiry                              | SEV-3    | External monitoring                           |

## 3. First Response (Any Severity)

The first 10 minutes follow the same script. Do not improvise.

1. **Acknowledge the page.** Within 5 minutes, on the established
   channel.
2. **Establish a coordination channel.** Slack `#incident-<id>` or
   equivalent. Put the timestamp, severity, and one-line summary in
   the topic.
3. **Capture state before changing it.** From any controller host:
   ```bash
   sudo journalctl -u prexorcloud-controller --since "30 min ago" \
       > /tmp/inc-<id>-ctl.log
   sudo journalctl -u prexorcloud-daemon --since "30 min ago" \
       > /tmp/inc-<id>-dmn.log
   curl -fsSL http://localhost:8080/api/v1/system/health > /tmp/inc-<id>-health.json || true
   curl -fsSL http://localhost:8080/api/v1/system/ready  > /tmp/inc-<id>-ready.json  || true
   prexorctl status > /tmp/inc-<id>-status.txt || true
   prexorctl node list > /tmp/inc-<id>-nodes.txt || true
   prexorctl crash list --since "1 hour ago" > /tmp/inc-<id>-crashes.txt || true
   ```
   For SEV-1/2, take heap and thread dumps too — see
   [`recover-controller.md`](recover-controller.md) §Step 1.
4. **Identify the layer.** Use `/system/ready` body and the symptom
   index in [`troubleshoot.md`](troubleshoot.md) §1 to narrow scope:
   - Controller process? → [`recover-controller.md`](recover-controller.md)
   - Mongo? → [`recover-mongo.md`](recover-mongo.md)
   - Valkey? → [`recover-redis.md`](recover-redis.md)
   - Daemon / node? → [`troubleshoot.md`](troubleshoot.md) §6
   - Group / instance? → [`troubleshoot.md`](troubleshoot.md) §7–8
   - Auth? → [`troubleshoot.md`](troubleshoot.md) §4–5
5. **Stabilize, don't fix.** Goal of first response is to stop the
   bleeding. Resist the urge to root-cause before stability returns.
   - For an HA failover incident, bring one controller back, verify,
     then proceed.
   - For a Mongo / Valkey outage, restore connectivity; do not rebuild.
   - For a stuck workflow, cancel it (per `troubleshoot.md` §8).
6. **Communicate.** Every 15 minutes for SEV-1/2, post: current
   theory, current actions, next check-in. Even "still investigating"
   is informative.

## 4. Decision Points

The biggest single category of mistakes is taking **destructive**
actions early. Before doing any of these, check the box and post in
the channel:

- [ ] Stop / restart a controller (HA: peer takes over; single-controller: outage extends)
- [ ] Restart Mongo / Valkey (be sure it's the cause)
- [ ] Run a Mongo `--drop` / restore (you will lose any data after the backup)
- [ ] `FLUSHALL` on Valkey (you will lose JWT revocations, lockouts, leases)
- [ ] Force-release a lease (existing in-flight operation may be interrupted; usually safe due to fencing)
- [ ] Force-cleanup a module classloader (interrupts any code in flight)
- [ ] Revoke an admin user's JWT
- [ ] Rotate the JWT secret without `jwtPreviousSecrets` (signs everyone out)
- [ ] Roll back to a previous controller version (may require Mongo restore)

If anyone hesitates, pause and confirm in the channel. Two-key the
destructive call.

## 5. Escalation

| Need                                            | Who                                 |
| ----------------------------------------------- | ----------------------------------- |
| Operator help / decision authority              | Operator-on-call (your roster)      |
| MongoDB / Valkey infrastructure expertise       | DBA / SRE                           |
| Network / load-balancer changes                 | Infra-on-call                       |
| Security implications (potential compromise)    | Security-on-call (and only then [`SECURITY.md`](../../SECURITY.md)) |
| Suspected PrexorCloud bug                       | Open a GitHub issue with diagnostics; tag `severity:sev-2` etc.  |
| Suspected vulnerability                         | **Do not** open a public issue. Follow [`SECURITY.md`](../../SECURITY.md). |

Set escalation timeouts: if the first responder isn't making progress
in 30 minutes (SEV-1) or 1 hour (SEV-2), pull in the next tier.

## 6. Resolution Checklist

Before declaring an incident resolved:

- [ ] `/system/ready` returns 200 on every controller for ≥10 minutes.
- [ ] `prexorctl status` shows expected state.
- [ ] Crash rate for the affected groups returned to baseline.
- [ ] No unresolved alerts.
- [ ] All destructive actions taken during response are recorded in
  the channel transcript with timestamps.
- [ ] `/tmp/inc-<id>-*` diagnostics moved to the incident archive.

## 7. Post-Incident Review

Within 5 business days for SEV-1, 10 for SEV-2:

1. **Timeline.** When did it start (first symptom), when did the page
   fire, when did response begin, when did each material action
   happen, when was it resolved? Use the channel transcript.
2. **Root cause.** What broke and why. Distinguish proximate cause
   (the daemon OOM-killed) from root cause (memory limit too low for
   recent player count growth).
3. **Impact.** How many users / instances / groups affected; how
   long; any data loss; any security exposure.
4. **What worked / didn't.** Was the right page sent? Did diagnostics
   give the right signals? Did the runbook match reality?
5. **Action items.** Concrete, owned, dated. Each maps to a phase or
   issue in the codebase, the runbooks, the alert config, or the
   on-call rotation.
6. **Update the runbooks.** If a procedure didn't match what you
   actually did, fix it. Runbooks decay if not exercised.

Publish the review internally. Anonymize before sharing externally if
ever needed.

## 8. Drills

A runbook unexercised is a runbook unproven. Schedule at least:

- **Quarterly**: a restore drill ([`restore.md`](restore.md) §Drill Cadence).
- **Quarterly**: a controller failover drill (kill one controller; observe lease handoff; record time-to-resume).
- **Annually**: a tabletop incident covering Mongo loss, no recent backup. Don't actually delete prod.

A nightly DR drill in CI exercises the restore path end-to-end (`dr-drill` job in `.github/workflows/nightly.yml`); manual
exercises remain on top of that.

## 9. Severity Examples (Calibration)

| Scenario                                                         | Severity |
| ---------------------------------------------------------------- | -------- |
| Both HA controllers lose Valkey for 1 minute, recover            | SEV-3    |
| Both HA controllers lose Valkey for 10 minutes; mutations queued | SEV-2    |
| Single controller crash-looping, peer healthy                    | SEV-2    |
| Two daemon hosts down, but groups have spare capacity             | SEV-3    |
| `survival` group cannot accept players (group routing broken)   | SEV-1    |
| Audit log writes failing                                         | SEV-2    |
| Brute-force attempt on `admin` triggering lockouts                | SEV-2    |
| Suspected stolen daemon mTLS key                                 | SEV-1, security-on-call |
| Stuck deploy on a non-prod group during business hours           | SEV-3    |
| Stuck deploy on a prod group blocking a security patch           | SEV-2    |

## Related

- [`troubleshoot.md`](troubleshoot.md) — symptom index.
- [`recover-controller.md`](recover-controller.md), [`recover-mongo.md`](recover-mongo.md), [`recover-redis.md`](recover-redis.md).
- [`SECURITY.md`](../../SECURITY.md), [`docs/security/threat-model.md`](../security/threat-model.md).
