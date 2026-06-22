# prexorctl — `module` & `plugin` command groups (audit)

Read-only audit of the cobra command tree under `cli/cmd/` (module*.go, plugin.go)
and the supporting packages `cli/internal/scaffold/` and `cli/internal/manifest/`.

Source files:
- `cmd/module.go` (list, upload, delete, new/scaffold)
- `cmd/module_install.go` (install)
- `cmd/module_search.go` (search)
- `cmd/module_upgrade.go` (upgrade)
- `cmd/module_dev.go` (dev, test)
- `cmd/module_doctor.go` (doctor)
- `cmd/module_new_wizard.go` (TUI wizard for `module new`)
- `cmd/plugin.go` (plugin, plugin new)
- `internal/scaffold/{scaffold.go,locate.go,plugin.go,wizardspec.go}`
- `internal/manifest/manifest.go`

Global flags (registered in `cmd/root.go`): `--json`, `--controller`, `--token`,
`--context`, `--no-color`, `--ascii`, `--verbose`. `--json` forces no-color. A
pre-link gate in `PersistentPreRunE` (root.go:47-83) blocks every command whose
top-level name is not in `commandsAllowedBeforeLink` and that is not annotated
`local-only`, when no controller context is resolved. Only `module new`
(module.go:149) carries that annotation.

---

## Command tree (documented: 2 groups + 11 leaf commands = 13)

### `module`  (parent group)
- **Purpose:** "Manage modules". No RunE; container only (module.go:15-18).
- Not `local-only` and not in the before-link allowlist, so leaf subcommands that
  don't talk to a controller (`doctor`, `test`) are still gated before a cluster
  is linked. See FINDINGS.

---

### `module list`
1. Path: `prexorctl module list`
2. Purpose: list installed modules.
3. Usage: `module list` (no args).
4. Flags: none local; honours global `--json`.
5. Function: `requireAuth()` then `GET /api/v1/modules` (bare JSON array →
   `[]map[string]any`). Renders a table NAME/ENABLED/FRONTEND/PLUGINS with
   enabled/disabled footer counts. `frontend` presence → yes/no; `plugins`
   array length → count.
6. `--json`: yes — prints the raw array (module.go:34-36).
7. Interactive: none.

### `module upload <file.jar>`
1. Path: `prexorctl module upload <file.jar>`
2. Purpose: upload + install a module jar (no signature).
3. Usage: `module upload <file.jar>` (ExactArgs(1)).
4. Flags: none local; global `--json`.
5. Function: rejects non-`.jar`, `requireAuth()`, `POST
   /api/v1/modules/platform/upload` (multipart). Prints `Module "<moduleId>"
   installed`.
6. `--json`: yes (module.go:96-98) — prints upload result map.
7. Interactive: none. No confirmation. Overlaps `module install` (see FINDINGS).

### `module delete <name>`
1. Path: `prexorctl module delete <name>`
2. Purpose: remove an installed module.
3. Usage: `module delete <name>` (ExactArgs(1)).
4. Flags: none local. **Does not consult `--json`.**
5. Function: `requireAuth()`, `DELETE /api/v1/modules/platform/<name>`. Prints a
   success line.
6. `--json`: **NO** — always prints human text even under `--json` (module.go:105-122).
7. Interactive: none — no confirmation prompt for a destructive op.

### `module install <jar|bundle.tar | id[@version]>`
1. Path: `prexorctl module install <source>`
2. Purpose: install a signed module from a local bundle or a configured registry.
3. Usage forms:
   - local jar: `module install foo.jar` (sidecar auto-detected `<jar>.cosign.bundle`
     or `<jar>.sig`, or via `--signature`)
   - tar bundle: `module install foo.tar` / `.tar.gz` / `.tgz` (must contain exactly
     one jar and ≤1 sidecar; path-traversal entries rejected)
   - registry spec: `module install stats-aggregator` or `...@1.2.0` (or `@latest`)
4. Flags:
   - `--signature` (string, "") — explicit sidecar path; overrides autodetection.
   - `--check-requires` (bool, false) — preflight: read `module.yaml` from the jar,
     `GET /api/v1/modules/platform/capabilities`, warn per required capability with
     no active provider. Non-fatal.
   - `--registry` (string, "") — pin one configured registry URL for a registry install.
5. Function: `isLocalModuleSource` decides local vs registry. Local →
   `UploadWithSignature POST /api/v1/modules/platform/upload`. Registry →
   `POST /api/v1/modules/platform/registry/install` with `{moduleId, version?,
   registryUrl?}`; the controller does sha256 + signature verification.
6. `--json`: yes — both local (module_install.go:79) and registry
   (module_install.go:176) paths print the result map.
7. Interactive: none.

### `module search [query]`
1. Path: `prexorctl module search [query]`
2. Purpose: browse modules offered by configured registries.
3. Usage: `module search [query]` (MaximumNArgs(1)); query → `?q=` substring filter.
4. Flags: none local; global `--json`.
5. Function: `requireAuth()`, `GET /api/v1/modules/platform/registry?q=`. Table
   MODULE/VERSION/REGISTRY/INSTALLED/TAGS; `(unsigned)` suffix when `signed=false`.
   Warns when `registries` is empty.
6. `--json`: yes (module_search.go:39) — prints `{registries, modules}`.
7. Interactive: none.

### `module upgrade [id]`
1. Path: `prexorctl module upgrade [id]`
2. Purpose: upgrade an installed module to the newest registry version.
3. Usage: `module upgrade <id>` or `module upgrade --all` (MaximumNArgs(1)).
4. Flags:
   - `--all` (bool, false) — upgrade every installed module with a newer version.
   - `--registry` (string, "") — pin one configured registry.
5. Function: `GET /api/v1/modules/platform/registry` for the catalog, classifies
   each module (not-in-registry / not-installed / up-to-date / available), then
   `POST .../registry/install` pinned to the advertised version. `--all` iterates;
   counts failures and returns an error if any failed. Mutually exclusive arg/`--all`
   validation.
6. `--json`: partial — single-module success (module_upgrade.go:176) and `--all`
   (module_upgrade.go:214) emit JSON, **but the "already up to date" branch
   (module_upgrade.go:167-169) always prints human text even under `--json`.**
7. Interactive: none.

### `module dev <name>`
1. Path: `prexorctl module dev <name>`
2. Purpose: watch a module's jar (and frontend dist) and hot-reload to the controller.
3. Usage: `module dev <name>` (ExactArgs(1)).
4. Flags:
   - `--repo-root` (string, "") — repo root; default discovered upward.
   - `--poll` (duration, 750ms) — jar/dist stat interval.
   - `--no-build` (bool, false) — don't spawn `./gradlew :…:assemble -t`.
5. Function: `LocateModule` → parse `archiveName` from build.gradle.kts → watch
   `build/libs/<archiveName>.jar` mtime/size. On jar change: `POST .../upload`
   (first) or `POST .../{moduleId}/upgrade`, falling back to install on 404. If
   `frontend/package.json` exists, watches `frontend/dist/` and on change zips it
   under `META-INF/frontend/` and `POST .../{moduleId}/frontend/reload`. Jar change
   short-circuits the frontend path on the same tick. First observation of each
   track is a baseline (no upload). Starts a background continuous gradle build
   unless `--no-build`. Ctrl+C stops. `moduleId` discovered via `GET
   /api/v1/modules/platform` matching `jarFile == archiveName+".jar"`.
6. `--json`: no (long-running watch; not meaningful).
7. Interactive: foreground watch loop with themed KV header; no prompts.

### `module test <name>`
1. Path: `prexorctl module test <name>`
2. Purpose: run a module's gradle `test` task.
3. Usage: `module test <name>` (ExactArgs(1)).
4. Flags:
   - `--repo-root` (string, "") — repo root.
   - `--gradle-arg` (StringSlice, nil, repeatable) — extra args forwarded to gradle.
5. Function: `LocateModule`, run `./gradlew :cloud-modules:<name>:test --console=plain
   [extra]` from `java/`, forwarding stdio. Propagates gradle exit code via typed
   `ExitCodeError`. **Does not call `requireAuth()`** (local-only operation), yet is
   gated by the pre-link check — see FINDINGS.
6. `--json`: no.
7. Interactive: none (stdin forwarded to gradle).

### `module doctor <jar>`
1. Path: `prexorctl module doctor <jar>`
2. Purpose: validate a built module jar against the platform-module contract offline.
3. Usage: `module doctor <jar>` (ExactArgs(1); directory input rejected; must end `.jar`).
4. Flags: none.
5. Function: reads `META-INF/prexor/module.yaml` (manifest pkg), runs the
   controller-equivalent shape checks: manifestVersion 1..2, id `[a-z][a-z0-9-]*`,
   semver version, backend entrypoint class present in jar, capability id/semver,
   manifestVersion-2 deprecation fields, extension variant artifact presence + sha256
   (recomputed and compared; `AUTO`/empty → warning), and a soft signature-sidecar
   check. Exit codes: 0 clean, 1 warnings, 2 errors (via `ExitCodeError`). Local-only,
   no `requireAuth()`.
6. `--json`: **NO** — human warn/error lines only; no machine-readable report.
7. Interactive: none.

### `module new [name]`  (alias `module scaffold`)
1. Path: `prexorctl module new [name]` / `prexorctl module scaffold [name]`
2. Purpose: scaffold a new cloud-module under `java/cloud-modules/<name>/`.
3. Usage: `module new [name]` (MaximumNArgs(1); name optional in wizard mode,
   required for `--interactive` and the composable-flag paths).
4. Flags:
   - `--package` (string, "") — override Java package (default
     `me.prexorjustin.prexorcloud.modules.<name>`).
   - `--repo-root` (string, "") — repo root; default discovered upward.
   - `--strip-comments` (bool, false) — strip `// STEP` / `/** STEP */` teaching comments.
   - `--force` (bool, false) — overwrite an existing module dir.
   - `--dry` (bool, false) — print plan, write nothing.
   - `--interactive` (bool, false) — legacy compact targets-only prompt (NOT the wizard).
   - `--wizard` (bool, false) — "force the full wizard"; **does not override `--targets`
     (see FINDINGS).**
   - `--browser` (bool, false) — stub; returns a "not implemented" error.
   - `--targets` (StringSlice, nil) — comma subset of `paper,folia,velocity,bungeecord,
     bedrock-geyser`; skips wizard.
   - `--mc-plugin` (StringSlice, nil) — alias for `--targets`; passing both errors.
   - `--all-defaults` (bool) — registered but its value is never read; only its
     `Changed` state routes to the non-wizard path.
   - `--capabilities` (StringArray, nil, repeatable) — `id` or `id@version` (default
     version `1.0.0`).
   - `--requires` (StringArray, nil, repeatable) — `id` or `id@range` (default range
     `[1.0,2.0)`).
   - `--no-rest` / `--no-mongo` / `--no-frontend` / `--no-plugin` (bool, false) — strip
     toggles (see FINDINGS for the gap between help text and real generator behaviour).
5. Function (local-only, never touches a controller): resolves repo root via
   `FindRepoRoot`. Flow precedence (module.go:213-289):
   `--browser`→error; `--interactive`→targets-only prompt then `runScaffold`;
   composable spec flags (`--capabilities/--requires/--no-*`)→`buildSpecFromFlags`→
   `ApplyToOptions`→`runScaffold`; `--targets/--mc-plugin/--all-defaults`→non-interactive
   `runScaffold`; else→full TUI wizard (`runModuleNewWizard`)→`printSpecSummary`→
   `runScaffold`. `scaffold.Generate` copies the `example` template with token
   replacement, prunes `module.yaml` extensions + `build.gradle.kts`
   `extensionArtifacts` to selected targets, applies wizard overrides
   (`WithFrontend=false` deletes `frontend/` + manifest block; `WithRest=false`
   deletes `rest/` + strips `onRegisterRoutes` override/imports; provides/requires
   rewrite the capabilities block; **`WithMongo=false` is accepted but not honoured**),
   and patches `java/settings.gradle.kts` after the `// ---- MODULES ---- //` anchor.
6. `--json`: no — plain text/themed-ish summary only.
7. Interactive: full huh-based wizard (identity → mongo/rest/frontend confirms →
   plugin yes/no → multi-select platform targets → per-paper/folia multi-version
   strategy → optional jar-split MC-version multiselect + paperweight confirm →
   provides/requires inputs). `--interactive` is a separate, narrower 3-option
   targets-only multiselect.

---

### `plugin`  (parent group)
- **Purpose:** "Author standalone @CloudPlugin jars (Path A)" (plugin.go:13-26).
  Container only, no RunE. **Not annotated `local-only`** despite being purely local —
  see FINDINGS.

### `plugin new <name>`
1. Path: `prexorctl plugin new <name>`
2. Purpose: scaffold a standalone single-platform `@CloudPlugin` subproject.
3. Usage: `plugin new <name>` (MaximumNArgs(1); name required, positional only).
4. Flags:
   - `--platform` (string, "") — **required**: `paper|spigot|folia|velocity|bungeecord`.
   - `--mc-version` (string, "") — paper only, `1.20` (default) or `1.21`; ignored elsewhere.
   - `--package` (string, "") — default `me.prexorjustin.prexorcloud.plugins.<name>`.
   - `--repo-root` (string, "") — repo root; default discovered upward.
   - `--description` (string, "") — written into `@CloudPlugin`.
   - `--author` (string, "") — written into `@CloudPlugin` (default "PrexorCloud").
   - `--force` (bool, false) — overwrite existing dir.
   - `--dry` (bool, false) — plan only.
5. Function (local): `GeneratePlugin` writes `java/cloud-plugin/cloud-plugin-<name>/`
   containing `build.gradle.kts` (applies `prexorcloud.plugin-<platform>` convention,
   plus velocity AP exclusion) and one `<Pascal>Plugin.java extends CloudPluginBase`
   with `@CloudPlugin`. Patches `settings.gradle.kts` after the `// ---- PLUGINS ---- //`
   anchor. Themed output (brand/code/path styles).
6. `--json`: no.
7. Interactive: none (no wizard equivalent to `module new`).

---

## FINDINGS

- **`plugin new` is wrongly gated before a cluster is linked.** Only `moduleNewCmd`
  has `Annotations{"local-only":"true"}` (module.go:149); `pluginCmd`/`pluginNewCmd`
  have none and `plugin` is not in `commandsAllowedBeforeLink` (root.go:30-41), so a
  fresh contributor scaffolding a plugin hits "no cluster connected" even though the
  command only writes local files. (plugin.go:13, root.go:72-81)
- **`module doctor` and `module test` are also wrongly gated before link.** Neither
  calls `requireAuth()` (both are local), but `module` is not local-only/allowlisted,
  so the pre-link gate blocks them. (module_doctor.go:48, module_dev.go:384,
  root.go:72-81)
- **`--wizard` does not do what its help says.** Help: "Force the full wizard even
  when --targets / --all-defaults are passed" (module.go:431-432), but the flow's
  `len(targets) > 0 || ...` check (module.go:260) sends any `--targets` to the
  non-interactive path regardless of `--wizard`. So `module new x --wizard --targets paper`
  runs non-interactive.
- **`--no-mongo` is a silent no-op via flags, but warned via the wizard.** The
  generator never strips Mongo (scaffold.go:75-80, 376-378). The wizard prints a
  notice (`unsupportedToggleNotices`, module_new_wizard.go:321-340 via `printSpecSummary`),
  but the composable-flags path calls `runScaffold` directly (module.go:250-256) with
  no summary/notice, so `module new x --no-mongo` quietly produces a Mongo-wired module.
- **Stale help/wizard text contradicts implemented behaviour for `--no-rest`.** The
  generator *does* strip `rest/` (scaffold.go:400-405, `stripRest`), yet both the flag
  help ("Warning surfaces until the REST-removal pass lands", module.go:451-453) and
  the wizard description ("Selecting No is recorded… the strip-out lands in a follow-up
  turn", module_new_wizard.go:60-64) claim it is unimplemented.
- **Stale wizard notice: bedrock-geyser "no template subdir yet".**
  `unsupportedToggleNotices` (module_new_wizard.go:328-329) says bedrock-geyser has no
  template subdir, but `java/cloud-modules/example/plugin/bedrock-geyser` exists today.
- **Three different platform-target lists across the same command.** Legacy
  `--interactive` prompt offers 3 (paper/folia/velocity, module.go:395-399); the full
  wizard offers 6 (adds spigot/bungeecord/bedrock-geyser, module_new_wizard.go:101-109);
  `scaffold.AllTargets()` returns 4 (paper/folia/velocity/bedrock-geyser, scaffold.go:92).
  Confusing and inconsistent.
- **`module delete` ignores `--json` and has no confirmation.** Always prints human
  text (module.go:105-122); a destructive op with no `--yes`/`--force` guard and no
  machine-readable output, unlike its sibling commands.
- **`module upgrade` "already up to date" ignores `--json`.** `upgradeOne` prints a
  human success line in the up-to-date branch even under `--json` (module_upgrade.go:167-169),
  unlike every other exit of the same command.
- **`module doctor` has no `--json`.** Output is the most CI-relevant in the group
  (it has dedicated exit codes 0/1/2), yet there is no machine-readable report —
  only `theme.PrintWarn/PrintError` lines (module_doctor.go:64-69).
- **`module upload` overlaps `module install` and is the strictly weaker one.**
  `upload` is local-jar-only, unsigned, no sidecar handling, no registry
  (module.go:76-103), all of which `install` does better. Two commands for one job;
  `upload` should be folded into `install` or documented as deprecated.
- **`plugin new` Long help advertises a `--name` flag that does not exist.**
  plugin.go:47 documents `--name=<kebab>`, but no `--name` flag is registered
  (plugin.go:144-159) — name is positional only. Misleading help.
- **Inconsistent output styling between the two scaffolders.** `plugin new` uses themed
  output (theme.StyleBrand/Code/Path/Tick, plugin.go:101-120), while `module new`'s
  `runScaffold` uses bare `fmt.Printf("→ …")` with no theme (module.go:368-389).
- **Only the wizard prints a spec summary / gap notices.** `--interactive` and the
  composable-flag paths skip `printSpecSummary`/`unsupportedToggleNotices`
  (module.go:227-235, 250-256), so scripted runs get no preview and no warnings.
- **`--all-defaults` flag value is dead.** Registered with
  `moduleNewCmd.Flags().Bool("all-defaults", …)` (module.go:440) into no variable;
  only its `Changed` bit is read (`hasNonWizardFlags`, module.go:295-301). The flag
  works but its boolean value is never consulted.
- **`module test --gradle-arg` is a `StringSlice` (comma-split).** Gradle args
  containing commas would be wrongly split (module_dev.go:439); the codebase already
  uses `StringArray` elsewhere for exactly this reason (module.go:445-450 capabilities).
- **Default-version mismatch between flags and wizard for capabilities.**
  `buildSpecFromFlags` defaults a bare provide to `1.0.0` and a bare require to
  `[1.0,2.0)` (module.go:348-363), while the wizard *requires* an explicit `@version`/
  `@range` and errors otherwise (module_new_wizard.go:217-219, 237-239). Same concept,
  two behaviours.
- **`module new --browser` is a shipped stub.** Flag is registered and returns a
  hard "not implemented" error (module.go:213-216, 433-434); consider hiding it until
  Phase B.2 lands.
- **`plugin` group is asymmetric / minimal.** Only `plugin new` exists — no
  `plugin list/doctor/validate`. Expected (plugins aren't controller-managed), but
  worth noting against the richer `module` group for parity expectations.
- **`module install` source detection can misfire on a local path collision.**
  `isLocalModuleSource` returns true for any existing path (module_install.go:141-149);
  a registry spec that happens to match a same-named file/dir in CWD is treated as a
  local source and then rejected by `resolveModuleInstallInputs` instead of being
  pulled from the registry. Narrow but surprising.
</content>
</invoke>
