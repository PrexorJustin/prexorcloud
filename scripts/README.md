<p align="center">
  <strong>scripts/</strong><br>
  <em>Build, publish, and setup chores that don't belong in Gradle.</em>
</p>

---

## What is scripts/?

Bash and Node helpers for the jobs that sit between the build systems: packaging the installer into the CLI binary, scaffolding a new module, checking perf drift, cutting a release, and removing an install. Each is standalone and self-documenting — run it with no args (or `--help`) to see its options. None touches production credentials; secrets stay in `.env` or come from cosign keyless signing.

## Quickstart

Scaffold a new first-party module — the script most contributors reach for first:

```bash
node scripts/new-module.mjs survival-stats --package me.prexorjustin.prexorcloud.modules.survivalstats
```

```
→ new module: survival-stats
  package:  me.prexorjustin.prexorcloud.modules.survivalstats
  dest:     java/cloud-modules/survival-stats
  37 files
  patched settings.gradle.kts (+4 includes)

next:
  cd java && ./gradlew :cloud-modules:survival-stats:build
```

It edits `java/settings.gradle.kts` for you — no manual include step.

## How it fits

These wrap the real build systems rather than replacing them — `build-installer.sh` calls Vite then hands the artifact to Go's `//go:embed`, `publish.sh` drives `cli/Makefile` and `gradlew publishCloud`, `perf-baseline-check.sh` is invoked by `nightly.yml`.

## Usage

`build-installer.sh` — builds the Vite installer into a single-file HTML and copies it to `cli/internal/setupweb/static/index.html`, the asset embedded into `prexorctl` (see [`installer/README.md`](../installer/README.md)). Idempotent.

```bash
scripts/build-installer.sh
```

`new-module.mjs` — scaffolds a module under `java/cloud-modules/<name>/` from the `example/` template, substituting tokens in file contents and paths.

```bash
node scripts/new-module.mjs <name> [--package <java.pkg>] [--strip-comments] [--force] [--install] [--dry]
```

`perf-baseline-check.sh` — compares a fresh perf-baseline report against `infra/perf/baselines.json` and surfaces drift in the GitHub Actions summary. Always exits 0 — perf is a soft signal.

```bash
scripts/perf-baseline-check.sh java/cloud-test-harness/build/reports/perf-baselines/baseline-report.json
```

`publish.sh` — drives the per-component publish lanes (CLI binaries, Java JARs) from one place.

```bash
scripts/publish.sh --list            # show registered components
scripts/publish.sh --dry-run cli java # print actions, change nothing
scripts/publish.sh                    # publish every "ready" component
```

`uninstall.sh` — guided removal of a `prexorctl setup` install; each layer (compose, systemd, dirs, binary, packages, CLI config) is independently confirmed.

```bash
sudo scripts/uninstall.sh             # interactive
sudo scripts/uninstall.sh --keep-data # remove services, keep /opt/prexorcloud
```

## Links

- [installer/](../installer/README.md) — the wizard `build-installer.sh` packages
- [tools/](../tools/README.md) — docs generators
- [Contributing](../CONTRIBUTING.md)

## License

Apache 2.0 — see [LICENSE](../LICENSE).
