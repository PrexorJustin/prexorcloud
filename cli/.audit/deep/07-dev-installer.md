# prexorctl deep review — 07: developer experience + installer

Scope: the `module` group (`list`, `install`, `upload`, `delete`, `search`,
`upgrade`, `dev`, `test`, `doctor`, `new`/`scaffold`), `plugin new`, and the
`setup` wizard (controller / daemon / dashboard; native vs docker; the browser
wizard). `stop` is OUT (audit 04 / doc 06).

Sources read: `cmd/module.go`, `cmd/module_install.go`, `cmd/module_search.go`,
`cmd/module_upgrade.go`, `cmd/module_dev.go`, `cmd/module_doctor.go`,
`cmd/module_new_wizard.go`, `cmd/plugin.go`, `cmd/setup*.go`,
`internal/scaffold/{scaffold.go,plugin.go,locate.go,wizardspec.go}`,
`internal/manifest/manifest.go`, `internal/setup/*`, `internal/setupweb/*`,
`cmd/root.go`. Prior-pass: `.audit/03-module-plugin.md`, `.audit/04-setup-config.md`.

**Two state-of-the-world corrections vs the prior pass** (verified in source):
- The pre-link gating findings (`plugin new`, `module doctor`, `module test`
  wrongly blocked before a cluster link) are **FIXED**: `pluginCmd`
  (plugin.go:28), `moduleNewCmd` (module.go:149), `moduleDoctorCmd`
  (module_doctor.go:35) and `moduleTestCmd` (module_dev.go:385) all now carry
  `Annotations{"local-only":"true"}`, and `isLocalOnly` walks the leaf
  (root.go:91-98). The local inner-loop now works pre-link. Good.
- `module new --no-rest` is now actually implemented (`stripRest`,
  scaffold.go:420-452) — but the help text and wizard copy still say it isn't
  (see below). `--no-mongo` remains a no-op (scaffold.go:73-74, 376-378).

---

## THE BIG PICTURE: is the inner loop cohesive and modern? (mostly no)

Benchmarks: cargo (`new`→`build`→`test`→`publish`), npm/pnpm, wrangler
(`init`→`dev`→`deploy`), supabase, stripe, helm.

The verbs that exist are individually good, but two systemic facts cap the
ceiling far below cargo/wrangler:

1. **The entire authoring loop is monorepo-bound and first-party-only.**
   `scaffold.Generate`, `LocateModule`, `module dev`, and `module test` all
   require a checkout of the *PrexorCloud monorepo*: they discover the repo by
   walking up to `java/settings.gradle.kts` (scaffold.go:722), generate the
   module *inside* `java/cloud-modules/<name>/`, and **patch the monorepo's
   `settings.gradle.kts`** (scaffold.go:671-718). A third-party module author
   cannot `module new` into their own project, cannot `module dev` their own
   repo, and cannot `module test` outside the monorepo. This is the opposite of
   cargo/wrangler, where the tool scaffolds a *standalone* project. The signed
   `module install <jar>` / registry path is the only third-party-friendly
   surface. **This is the #1 thing keeping the dev story from feeling modern.**

2. **The loop has holes between the verbs.** There is `new`→(edit)→`test`/`dev`,
   and there is `doctor`→`install`/`upgrade`, but nothing bridges them:
   - No `module build` / `module package` — the author must know the raw
     `cd java && ./gradlew :cloud-modules:<name>:build` incantation (printed by
     `runScaffold`, module.go:387). cargo/npm own the build verb.
   - No `module sign` / `module bundle` — `install` consumes
     `<jar>.cosign.bundle` sidecars and `.tar` bundles (module_install.go), and
     `upload`/`doctor` reason about signatures, but **nothing in the CLI
     produces them**. The provenance story is "verify what someone else
     signed", never "sign mine".
   - `doctor` is never run automatically as an `install`/`dev` preflight, even
     though it reproduces the controller's exact reject checks offline.
   - No `module enable` / `module disable` even though `module list` renders an
     ENABLED/DISABLED pill (module.go:50-57) — the state exists but has no CLI verb.
   - No `module describe`/`module info` for a single installed module.

Net: the pieces are high-quality but the loop is a collection of tools, not a
paved road. A first-time module author cannot discover the path from `module
new` to a running module without reading docs.

---

## `prexorctl module` (parent group)

**Verdict: IMPROVE.** Best-in-class target: a coherent package-manager group
where `list`/`install`/`upgrade`/`enable`/`disable`/`describe` form one mental
model and the authoring verbs (`new`/`dev`/`test`/`doctor`/`build`/`sign`) form
another, both discoverable from `module --help`.

- [2][table-stakes][needs-server] **Endpoint noun split.** `list` hits
  `/api/v1/modules` (module.go:30) while every other verb hits
  `/api/v1/modules/platform/...`. Two nouns for one concept; pick one and alias.
- [13][modern][client-only] Group help has no examples and no "authoring vs
  operating" sectioning; `module --help` just lists 10 flat verbs.

### `prexorctl module list`
Current: `GET /api/v1/modules`, table NAME/ENABLED/FRONTEND/PLUGINS.
**Verdict: IMPROVE.** Target: `kubectl get`-style with `-o` and a describe sibling.
- [6][modern][client-only] Only table or raw `--json` (module.go:34). No
  `-o wide|name|yaml`, no version column, no "module describe <id>".
- [13][modern][needs-server] No `module enable/disable <id>` despite the
  ENABLED pill — operators must use the dashboard to toggle a module.
- [4][table-stakes][client-only] No empty-state hint ("no modules — install one
  with `module install`").

### `prexorctl module install <jar|bundle.tar | id[@version]>`
Current: local-jar / tar-bundle / registry spec, controller verifies sha+cosign.
**Verdict: KEEP (improve UX).** Target: the one true install verb with a
visible provenance receipt and a `--wait` for activation.
- [10][modern][needs-server] **Provenance UX is opaque.** On success it prints
  `installed (signature: foo.cosign.bundle)` or `(no signature attached)`
  (module_install.go:84-88) but never surfaces *who signed it* (cosign
  identity/issuer) or the verified sha256 — the controller verified it but the
  receipt is invisible. stripe/cosign show the trust decision; this hides it.
- [4][modern][needs-server] No `--wait`/`--timeout`: install returns when the
  jar is staged; if module enable/health is async the CLI can't confirm RUNNING.
- [5][table-stakes][client-only] `isLocalModuleSource` (module_install.go:141)
  treats *any* existing path as local, so a registry id that collides with a
  CWD file/dir is misrouted to the local path and rejected — surprising.
- [8][modern][client-only] `--check-requires` is opt-in and off by default;
  the safer default is to always preflight and let `--no-check` opt out.
- [9][modern][client-only] No `--dry-run` (resolve + verify, don't install) —
  helm/kubectl both have it; valuable given verification is server-side.

### `prexorctl module upload <file.jar>`
Current: unsigned local-jar upload via `/upload`. **Verdict: DECLUTTER (merge
into install).** It is a strict subset of `install` (local jar, no sidecar, no
registry, no `--check-requires`) — module.go:76-103. Two verbs, one job.
- [1][table-stakes][client-only] Fold into `install`; keep `upload` as a hidden
  deprecated alias that warns. Having both invites "which do I use?".
- [10][table-stakes][needs-server] It uploads *unsigned* with no warning gate,
  while `install` at least narrates signature presence — a security regression
  hiding behind a friendlier name.

### `prexorctl module search [query]`
Current: `GET .../registry?q=`, table MODULE/VERSION/REGISTRY/INSTALLED/TAGS.
**Verdict: KEEP.** Solid. Target: add registry management + richer output.
- [13][modern][needs-server] No `module registry list/add/remove` — registries
  live only in `controller.yml` (`modules.registries`); the CLI can browse but
  not manage them. wrangler/npm let you manage sources from the CLI.
- [6][modern][client-only] No `-o wide` (description/published-at) and the
  `(unsigned)` marker is easy to miss in a dim suffix (module_search.go:68-70).

### `prexorctl module upgrade [id] / --all`
Current: classify catalog, re-install at advertised version. **Verdict: KEEP.**
- [4][modern][needs-server] No `--dry-run` to preview what `--all` would change.
- [6][table-stakes][client-only] The "already up to date" branch prints human
  text even under `--json` (module_upgrade.go:167-169) — every other exit
  respects `--json`. Inconsistent machine contract.
- [9][modern][client-only] No diff/changelog between installed→target version.

### `prexorctl module dev <name>`
Current: poll jar mtime + frontend dist, hot-upload/upgrade to controller.
**Verdict: IMPROVE.** Genuinely good idea (a `wrangler dev`-style loop); held
back by being monorepo-only and polling-based.
- [3][modern][client-only] **Monorepo-only** (see big picture): `LocateModule`
  requires `java/cloud-modules/<name>` and parses `archiveName` from
  `build.gradle.kts`. No third-party module can use it.
- [11][modern][client-only] Polls every 750ms (`os.Stat`, module_dev.go:218)
  instead of fsnotify — the doc even apologizes for it (module_dev.go:47). Fine,
  but modern dev loops are event-driven.
- [9][table-stakes][client-only] Pins one controller (`client.BaseURL`); no
  failover, and a controller restart mid-loop just streams `reload failed`
  errors with no reconnect/backoff messaging.
- [6][innovative][client-only] No structured/event output for the watch loop;
  can't be consumed by an IDE or a TUI.

### `prexorctl module test <name>`
Current: `./gradlew :cloud-modules:<name>:test`, forwards exit code.
**Verdict: KEEP (one bug).**
- [5][table-stakes][client-only] `--gradle-arg` is a `StringSlice`
  (module_dev.go:441) so an arg containing a comma is wrongly split — the same
  file uses `StringArray` elsewhere for exactly this reason (module.go:445).
- [3][modern][client-only] Monorepo-only (same `LocateModule` constraint).

### `prexorctl module doctor <jar>`
Current: offline validation of the module jar vs the controller contract; exit
codes 0/1/2. **Verdict: KEEP — best command in the group; add machine output.**
- [6][table-stakes][client-only] **No `--json`** despite being the most
  CI-relevant verb here (module_doctor.go:64-71 prints only themed lines). A
  machine-readable findings report (errors[]/warnings[] + counts) is table stakes.
- [12][modern][client-only] Not wired as an automatic preflight in `install` /
  `dev`; it reproduces the controller's reject logic offline yet authors must
  run it by hand.
- [5][modern][client-only] Rejects directory input ("not supported yet",
  module_doctor.go:57) — a `doctor <module-dir>` that finds the built jar would
  close the loop with `new`/`test`.

### `prexorctl module new [name]` (alias `scaffold`)
Current: scaffold a module under `java/cloud-modules/<name>` — full TUI wizard,
or `--interactive`, or composable flags, or `--all-defaults`. **Verdict:
IMPROVE — the flow is over-branched and the wizard over-promises.**
- [13][table-stakes][client-only] **The wizard asks far more than the generator
  honours.** It collects MongoDB toggle, REST toggle, per-platform multi-version
  strategy (`single`/`for-version`/`jar-split`), per-version subprojects, and
  paperweight — but the generator implements only target selection, frontend
  strip, REST strip, and capabilities (scaffold.go:365-414). `--no-mongo`,
  `for-version`, `jar-split`, paperweight, and spigot/bungeecord subdirs all
  fall back to template defaults (module_new_wizard.go:321-341). Asking ~10
  questions and honouring ~4 erodes trust; gate the unimplemented questions
  behind a feature flag or drop them.
- [9][table-stakes][client-only] **Stale copy contradicts behaviour.** Wizard
  descriptions for REST say the strip-out "lands in a follow-up turn"
  (module_new_wizard.go:60-64) and flag help says the same (module.go:451-453),
  but `stripRest` is implemented. The bedrock-geyser "no template subdir yet"
  notice (module_new_wizard.go:328) is also stale — `example/plugin/bedrock-geyser`
  exists.
- [2/5][table-stakes][client-only] **Three divergent target lists for one
  command:** legacy `--interactive` offers 3 (paper/folia/velocity,
  module.go:395-399), the wizard offers 6 (module_new_wizard.go:101-109),
  `AllTargets()` returns 4 (scaffold.go:92). Unify to one source of truth.
- [1][modern][client-only] **Four flow branches** (`--interactive` /
  composable-spec / `--targets`/`--all-defaults` / wizard) for what is one
  decision tree (module.go:213-289). `--interactive` is a strictly weaker
  subset of the wizard and should go.
- [9][client-only][table-stakes] **`--wizard` is a lie:** help says it forces
  the wizard even with `--targets`, but `len(targets)>0` routes to the
  non-interactive path regardless (module.go:260).
- [5][modern][client-only] `--all-defaults`'s boolean value is never read (only
  `.Changed`, module.go:295-301); `--mc-plugin` duplicates `--targets`;
  `--browser` is a shipped stub that hard-errors (module.go:213-216). Dead/half
  surface area.
- [5][modern][client-only] Flags-vs-wizard default mismatch for capabilities:
  flags default a bare provide to `1.0.0`/require to `[1.0,2.0)`
  (module.go:348-363); the wizard *requires* explicit `@version`/`@range`
  (module_new_wizard.go:217-219). Same concept, two behaviours.
- [9][modern][client-only] `runScaffold` output is bare `fmt.Printf("→ …")`
  (module.go:368-389), unthemed, no `--json`, and only the wizard path prints a
  summary / gap notices — scripted paths get neither preview nor warnings.

### `prexorctl plugin new <name>`
Current: scaffold a standalone single-platform `@CloudPlugin` subproject.
**Verdict: KEEP (fix help; consider parity).**
- [9][table-stakes][client-only] Long help advertises `--name=<kebab>`
  (plugin.go:50) but no such flag exists — name is positional only.
- [5][modern][client-only] `--platform` is required but there is no enum
  validation at parse time / no completion; an interactive picker (like `module
  new`'s) would match the sibling's UX. `plugin` group has only `new` — no
  `plugin doctor`/`build`, vs the richer `module` group.
- [3][modern][client-only] Monorepo-only (patches the monorepo
  `settings.gradle.kts`, scaffold/plugin.go) — same first-party constraint.
- [12][modern][client-only] Output is themed (plugin.go:104-123) while `module
  new` is not — the two scaffolders look like different products.

---

## `prexorctl setup` (the installer)

**Verdict: IMPROVE.** This is the onboarding front door and it is ambitious (a
browser wizard with SSE log streaming, SSH-tunnel auto-detection, cosign-verified
downloads, auto-login). But it has grammar drift, dead flags, a native/compose
capability split between CLI and wizard, and secret-on-argv hazards.

### Grammar / shape
- [2][table-stakes][client-only] **Component is a flag, not a subcommand.**
  `prexorctl setup controller` does not work — you must pass
  `--component=controller` (setup.go:287), yet `prexorctl stop controller`
  *is* a subcommand. Inconsistent install/lifecycle grammar; `setup controller`
  / `setup daemon` / `setup dashboard` subcommands would match the rest of the CLI.
- [9][table-stakes][client-only] `--component` help says "controller or daemon"
  (setup.go:287) but `dashboard` is a valid, validated value
  (setup_helpers.go:193-201) — stale help omits a whole component.

### Lifecycle flags
- [1/9][table-stakes][client-only] **Four flags for two questions.** Canonical
  `--boot-mode`/`--start-mode` coexist as first-class `--help` entries with
  legacy `--service-mode`/`--startup-validation-mode` (setup.go:289-292); the
  legacy pair is only a silent `firstNonEmpty` fallback
  (setup_systemd.go:57,66). No "deprecated" signposting. Pick two, hide the rest.
- [10][modern][client-only] `validateSetupModeCompatibility` only range-checks
  the *legacy* `--service-mode` against compose (setup_helpers.go:215-228); the
  canonical `--boot-mode`/`--start-mode` are not validated against compose.
- [5][modern][client-only] Dead production helpers kept alive only by tests:
  `promptServiceRegistration`/`resolveServiceRegistration`/
  `resolveControllerStartupValidation` (setup_systemd.go:11,25,112) — residue
  from the migration; confusing.

### Native vs docker parity
- [3][table-stakes][needs-server] **Native dashboard is reachable from the
  browser wizard but NOT from the CLI.** `runDashboardSetup` hard-refuses
  native (setup_dashboard.go:30: "re-run with --install-mode=compose"), yet the
  wizard accepts `installMode:"native"` with a `webServer` choice (nginx/caddy)
  and a full native installer exists (`internal/setup/install_dashboard_native.go`,
  `setupweb/install.go:619-690`). Same binary, two capabilities — the TTY/flag
  path is a second-class citizen.
- [5][modern][client-only] **Dead CLI flags:** `--dashboard-serve-mode` and
  `--dashboard-tls-email` are registered (setup.go:312,314) but never consumed
  by the compose-only CLI dashboard path (setup_dashboard.go reads only
  PublicURL/ControllerURL/ListenPort/TLSMode/admin). `--dashboard-tls-mode` is
  near-dead too (only meaningful on the native path the CLI refuses). They
  mislead operators into thinking these are configurable.
- [12][modern][client-only] The `webServer=caddy` option exists only in the
  wizard — no CLI equivalent at all.

### Secrets & auth bootstrap
- [10][table-stakes][client-only] **Secrets on argv.** `--dashboard-admin-password`
  (setup.go:317) and `--daemon-join-token` (setup.go:308) are passed as flags
  → leak into shell history and `ps`. No stdin / file / env path for either.
- [10][modern][needs-server] `autoLoginAfterControllerReady` hardcodes username
  `admin` (setup_controller.go:388) and reads the bootstrap password file; on a
  returning/custom-admin install it silently times out and the only feedback is
  "admin user may already exist" (setup_controller.go:424). Fine for first boot,
  opaque otherwise.
- [10][modern][client-only] The bootstrap admin password is read from
  `<dir>/config/.initial-admin-password` and exchanged for a JWT stored
  plaintext in `~/.prexorcloud/config.yml` — acceptable, but there is no
  rotation/refresh story and the JWT never expires from the CLI's view (audit 04).

### Idempotency / re-run / scriptability
- [4][table-stakes][client-only] **No re-run / idempotency story.** Re-running
  `setup` re-prompts, re-downloads, and overwrites `controller.yml` /
  compose files with no "existing install detected — reconfigure? [y/N]" and no
  `--upgrade` path. helm/wrangler treat re-run as upgrade; this treats it as a
  fresh install.
- [6][table-stakes][client-only] **No `--json` and no machine result.** `setup`
  emits only styled lipgloss output (audit 04); `--non-interactive` works but a
  CI job can't parse what was installed, where, or whether it started.
- [7][modern][client-only] No `--dry-run`/plan ("would download X, write Y to
  Z, install systemd unit") — a high-blast-radius root operation with no preview.
- [9][modern][client-only] No uninstall/cleanup verb pairing (`stop` stops, but
  there's no `setup --uninstall` to remove units/dirs); partial-failure leaves
  half-written installs the operator must clean by hand.

### Browser wizard quality
- [13][modern][client-only] The wizard is a real asset: loopback-default,
  SSH-tunnel auto-detection from `$SSH_CONNECTION` (setup.go:208-234,
  setup_browser.go:40-44), `--public` with TLS+token+firewall management, SSE
  log streaming, and live CORS registration. This is ahead of most installers.
- [9][modern][client-only] But discoverability is thin: the only signal that a
  browser wizard is the default is buried in `setup --help`; a first-time `setup`
  on a desktop silently opens a browser (good) while the same on a headless box
  silently falls to TTY (good) — but neither path is announced before it happens.
- [12][modern][client-only] Capability drift between wizard and TTY (native
  dashboard, caddy) means "the docs said X works" depends on *which UI* you used.

---

## Cross-cutting / systemic (fix once, CLI-wide)

1. **De-monorepo the authoring loop.** `new`/`dev`/`test` should support a
   standalone module project (own dir, own settings, `module init` that does not
   patch a shared `settings.gradle.kts`). This is the single biggest gap vs
   cargo/wrangler and gates any third-party module ecosystem.
2. **Own the build/sign verbs.** Add `module build`, `module bundle`, and
   `module sign` so the CLI can *produce* the signed `.cosign.bundle` / `.tar`
   bundles that `install`/`doctor` already consume. Close the provenance loop on
   both ends.
3. **One module mental model.** Merge `upload`→`install`; add `enable`/`disable`/
   `describe`; wire `doctor` as an automatic install/dev preflight. Standardize
   `-o`/`--json` on every read verb (especially `doctor`).
4. **Single source of truth for platform targets.** Collapse the 3/6/4 target
   lists into one list the wizard, `--interactive`, `--targets`, settings
   patcher, and manifest pruner all read.
5. **Wizard honesty.** Either implement or hide every `module new` question the
   generator can't honour (`--no-mongo`, for-version, jar-split, paperweight,
   spigot/bungeecord subdirs); purge the stale "lands in a follow-up turn" copy.
6. **`setup` as subcommands, not a flag.** `setup controller|daemon|dashboard`
   to match `stop`; keep `--component` as a hidden alias during migration.
7. **One lifecycle question pair.** Retire `--service-mode`/
   `--startup-validation-mode` (deprecate visibly), keep `--boot-mode`/
   `--start-mode`, validate both against compose, delete the test-only dead code.
8. **CLI/wizard capability parity.** Expose native dashboard (and caddy) from
   the flag/TTY path, or stop registering the flags that imply it
   (`--dashboard-serve-mode`, `--dashboard-tls-email`).
9. **Secrets never on argv.** `--daemon-join-token`, `--dashboard-admin-password`,
   and `config set token` should read from stdin/`-`/file/env (shared with the
   prompt path).
10. **Installer idempotency + plan.** Detect existing installs and offer
    reconfigure/upgrade; add `setup --dry-run`; add a machine result (`--json`)
    so CI can assert the outcome; pair with an uninstall verb.
11. **Theming + output uniformity.** `module new`/`runScaffold` should use the
    same theme + `--json` contract as `plugin new` and the rest of the CLI;
    stdout = data, stderr = chatter, everywhere.
</content>
</invoke>
