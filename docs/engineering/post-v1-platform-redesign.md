# Post-v1 platform & UX redesign — design note

**Status: candidate decisions, not yet committed.** This is a working design note captured during
the v1 live acceptance run (see `northstar-plan.md`). Each section is a direction with its rationale
and trade-offs; promote the ones you commit to into `decisions.md` as ADRs, and fold the CLI-facing
parts into the planned post-acceptance `prexorctl` redesign. Nothing here is implemented yet.

## The problem this note addresses

Bringing up "a server players can join" today means assembling raw CRUD in dependency order:
register a catalog entry (URL + sha by hand) → create a backend group → register another catalog
entry → create a proxy group → create a Network (REST/JSON, no CLI) → open a firewall port →
connect. The primitives (catalog / group / network) are sound, but they're exposed as a database,
not a product. Every step asks the operator to think about *plumbing* before they get the *outcome*.
The threads below remove that friction without throwing away the primitives.

---

## 1. The catalog ships filled — a hand-curated default manifest

**Decision.** The catalog is never empty. PrexorCloud ships a **hand-curated** standard catalog —
a "big" default manifest a human maintains and updates when notable releases drop. It is **not**
auto-resolved from provider APIs, and **never** fetched at controller start.

Two independent axes; keep them separate:

**Axis A — how the manifest gets its content: manual curation.**
A curated `catalog/standard-catalog.yml` lives **in the main repo**, reusing the existing `catalog.yml`
schema (`CatalogConfig` / `CatalogConfigLoader`) — no new format. A human edits it (entry = platform,
version, downloadUrl, sha256) and bumps it when a new MC/Paper/Velocity build is worth shipping.
Adding/updating a standard entry = a reviewed PR. Full control, every build vetted, zero provider-API
integration. (CI auto-resolution — a scheduled job querying PaperMC/Geyser to fill latest builds —
is an explicitly **deferred, optional** convenience, not the baseline. It trades control for less
manual sha-lookup and risks pulling unvetted/`-SNAPSHOT` builds; revisit only if hand-maintenance
becomes a burden.)

**Axis B — how the manifest reaches controllers: decide one.**
- **B1 — bundled in the release (recommended start).** The hand-curated file ships inside PrexorCloud
  and loads at startup, fully offline / air-gapped. The standard catalog changes when you cut a
  release. No separate repo, no hosting, no runtime fetch — simplest possible.
- **B2 — also hosted at a URL (add later if needed).** Publish the *same hand-curated file* so
  **existing** controllers pick up catalog updates **without** waiting for a PrexorCloud release, via an
  async, non-blocking, offline-tolerant sync (`prexorctl catalog sync`). B2 is still the
  hand-maintained file — it does **not** mean auto-pulled-from-providers. This is the only reason to
  want a separate repo / `catalog.prexor.cloud` URL, and the only place cosign-signing the manifest +
  the module-registry pattern (ADR 31) come into play.

**Recommendation: start A1 + B1** (hand-curated file, bundled). Add B2 hosting only when "update the
standard catalog without cutting a release" becomes a real need.

**Two tiers that never collide** (applies under either delivery): *managed* entries (from the default
manifest) vs *operator* entries (pinned, never clobbered). Groups pin a version, so "what's available"
is decoupled from "what runs" — a restart or manifest update never changes what a running group deploys.

**Why never fetch on controller start.** It couples boot to the internet (fatal in air-gapped /
firewalled prod — this fleet's controller is firewalled). The bundled file (B1) makes the catalog
filled the instant the controller starts, offline; any freshness (B2) is an async background sync off
the hot path.

### On a separate manifest repo publishing to a prexor.cloud URL

Only relevant under **B2**, and only worth a separate repo if it owns a *publish pipeline* (validate
the hand-curated file against the catalog schema → cosign-sign → publish to `catalog.prexor.cloud` or
`prexor.cloud/catalog/…` via the existing Cloudflare Pages deploy). The content is still
hand-curated; the pipeline just validates + signs + serves it. **If you stay on B1 (bundle only), you
need none of this** — no separate repo, no hosting, no signing. Don't build it pre-emptively.

---

## 2. Zero-config direct-join — progressive disclosure

**Decision.** A single backend group is **directly joinable by default**; proxy + Network are required
only once the topology actually needs them.

Today `ServerConfigPatcher` unconditionally forces Velocity modern forwarding + `online-mode=false` on
every Paper/Spigot backend (`TemplatePreparation` → `ServerConfigPatcher.patch`), so a backend can
*never* be joined directly — the most common intent ("one server, friends join") is impossible without
also standing up a proxy and a Network. (This is exactly the wall hit at 2D in the live run.)

- Add a per-group mode (e.g. `proxyForwarding: false`, the default for a standalone group) →
  `online-mode=true`, forwarding off, directly joinable.
- When the operator adds a proxy group + a Network referencing the backend, PrexorCloud flips
  forwarding **on** for it automatically. Complexity appears exactly when it's warranted, never before.
- This is the same code change as the "standalone group mode" question raised at 2D, and it resolves
  the direct-connect-vs-proxy tension cleanly.

---

## 3. Higher-level UX — close the CLI gap, add a golden path

**Decisions.**

- **`prexorctl network` command.** Networks are REST + dashboard-only today (no CLI). Add
  `network create/list/edit/delete` mirroring the dashboard `NetworkDialog.vue` (lobby / fallbacks /
  proxy groups / kick message / Bedrock routing) with the same validation. Closes a real hole exposed
  in the live run (raw `curl` was the only CLI path).
- **Auto-resolve in `catalog add` (optional operator convenience).** `prexorctl catalog add paper@1.21`
  resolves URL + sha from the provider on demand — never paste a URL/sha by hand. This is
  operator-initiated and distinct from the default manifest (§1, which is hand-curated): the bundled
  manifest covers the common case; auto-resolve covers ad-hoc entries an operator adds themselves.
- **Declarative `apply` (the IaC golden path).** One reviewable, version-controllable spec that
  describes a whole topology and is applied atomically + idempotently:
  ```yaml
  network: main
  proxy:  { platform: velocity, scale: 1 }
  lobby:  { platform: paper@1.21, group: survival, min: 1, max: 3 }
  ```
  `prexorctl apply network.yaml` creates the (auto-resolved) catalog entries, groups, and Network in
  dependency order; re-running reconciles instead of erroring. The dashboard equivalent is a
  "Create a network" wizard that produces the same object with concepts explained inline.

**Sequencing.** Hand-curated bundled catalog (§1 A1+B1) + zero-config direct-join (§2) first —
together they make the 90% case work out of the box and erase the empty-catalog/forwarding friction.
Then `network` CLI, on-demand auto-resolve, and `apply` for the multi-server / power-user path.

---

## 4. GitHub org migration checklist

Move PrexorCloud (and the new catalog-manifest / registry repos) into a `prexorcloud` GitHub org. The
org is the right *home* for a multi-repo product and is effectively a prerequisite for clean hosted
catalog/registry URLs and a stable cosign identity. It does **not** fix the architecture/UX items
above — those are orthogonal. Do it as a deliberate one-time migration, ideally **before** standing up
the hosted catalog/registry (so they get the right URLs from day one) and before broad v1
distribution (so the cosign identity is stable). It does **not** block the acceptance test (that uses
manually-deployed working-tree binaries, not the signed release path).

**The migration touches signature verification — coordinate the cutover:**

- [ ] **`website/public/install.sh`** — update hardcoded `GH_REPO="PrexorJustin/prexorcloud"` **and**
      `COSIGN_IDENTITY_REGEX` (pins `…/PrexorJustin/prexorcloud/…`).
- [ ] **Controller module-signing trust root** — update the pinned cosign identity to the org identity.
- [ ] **Release workflows** — `release.yml` / `release-images.yml` / `release-jars.yml` mostly
      self-heal (they use `${{ github.repository }}`), but audit every hardcoded owner string.
- [ ] **GHCR namespace** — images move to `ghcr.io/prexorcloud/…`; set org-level package visibility
      (a Part 0B item). Update any pull references / compose image tags.
- [ ] **Already-released artifacts** — v1.0.0 was cut under `PrexorJustin`; anything signed under the
      old identity **won't verify against the new trust root**. Re-tag/re-sign under the org, or have
      the verify config accept both identities during the transition window.
- [ ] **Hardcoded URLs / badges / docs links** — sweep README badges, docs, `prexor.cloud` links,
      clone URLs.
- [ ] **OIDC / Pages / domains** — confirm org OIDC permissions for cosign keyless + Rekor; attach
      `prexor.cloud` (and any `catalog.`/`registry.` subdomains) under the org.

---

## 5. Monorepo decoupling — decide during the org migration

Worth deciding alongside the org move, but **be selective — the contract-drift gates are a hard
constraint.** `ProtoContractDriftTest`, `StartupContractDriftTest`, and the `sdk:check` /
openapi→api-sdk type gates exist precisely because the controller, daemon, CLI, and SDK share wire
contracts that are verified **in lockstep** in one CI run. Splitting the contract-coupled core into
separate repos makes those gates cross-repo (publish/consume contract artifacts, version skew windows)
— real cost, little benefit.

**Recommendation: polyrepo at the edges, monorepo at the contract-coupled core.**

- **Keep together (contract-coupled):** `cloud-controller`, `cloud-daemon`, `cloud-protocol`,
  `cloud-api`, `cli` (Go SDK/CLI), `dashboard` (consumes generated SDK types). These move in lockstep
  and the drift gates depend on it.
- **Good extraction candidates (loosely coupled, own cadence):** `website` (already near-standalone),
  `design-system` (publish as a versioned token/component package), the new **catalog-manifest** and
  **module-registry** repos (separate anyway, §1), and possibly first-party `cloud-modules/*` (they're
  SDK consumers, not contract owners).
- **Open question to decide:** whether first-party modules ship in-tree (lockstep with the SDK they
  use) or as separate repos consuming a released SDK — the same trade-off as the manifest schema
  coupling in §1.

---

## 6. CLI noun model & enrolment — fold into the `prexorctl` redesign

Two CLI-shape questions raised during the live run (2026-06-14). Both are redesign material, not mid-test changes.

### 6a. Unify enrolment under one verb — but keep two token *types*

Today there are two unrelated-looking commands: `prexorctl token create --node` (daemon enrolment) and
`prexorctl cluster join-token create` (controller join). They should share one discoverable verb, e.g.

```
prexorctl enroll daemon     --node node-fra-3
prexorctl enroll controller --join-addr 10.0.0.3:9190 --label ctrl-3
```

**Do NOT merge the token semantics or auto-detect the role on connect.** They are different trust domains
and privilege levels: a daemon token is signed by the **daemon CA** and is routine (`tokens.create`); a
controller-join token is HMAC'd with the **cluster seed**, changes Raft consensus, and is deliberately
gated on `cluster.manage` (excluded from default ADMIN). A single token usable as either is a
privilege-escalation hole — a cheap daemon token could inject a malicious controller into the quorum. So:
merge the *ergonomics*, keep the token **type fixed at issuance**, and keep `enroll controller` behind the
`cluster.manage` gate.

### 6b. Resolve the `services` vs `instance` naming drift; keep the 3-layer noun model

Don't collapse `instance` / `node` / `cluster` into one `service`/`services` command — they're three
distinct architectural layers (workloads / worker hosts / control plane), and a merged noun makes most
verbs apply to only one sub-type (`drain` node-only, `console` instance-only, `members` cluster-only) —
the same reason `kubectl` keeps pods/nodes/control-plane separate. Keep three clear nouns:

| Layer | Noun | What it is |
|---|---|---|
| Control plane | `cluster` | the controllers / Raft quorum |
| Worker hosts | `node` | the daemon hosts |
| Workloads | `instance` (or `server`) | the MC servers/proxies that run |

**The real fix is the naming inconsistency:** the backend already calls workloads **"services"**
(`/api/v1/services/{id}/console`, `/api/v1/services`) while the CLI says **`instance`**. Pick one term
end-to-end (lean `instance` or `server`; "service" is too vague for an MC server) and align REST + CLI +
docs. That's the consolidation with payoff — not merging the layers.

---

## Sequencing summary

1. §1 hand-curated bundled catalog (A1+B1) + §2 zero-config direct-join — biggest friction kill, smallest surface, no new infra.
2. §3 `prexorctl network` + on-demand `catalog add` auto-resolve + declarative `apply` + dashboard wizard.
3. §1 B2 hosted manifest + async sync (only if "update catalog without a release" is needed; reuses ADR 31 infra).
4. §4 org migration (before any hosted catalog/registry go live).
5. §5 selective repo extraction (edges only; keep the contract-coupled core mono).
6. §1 A2 CI auto-resolution — deferred; only if hand-maintenance becomes a burden.
