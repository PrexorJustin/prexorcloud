# scripts/ — operator scripts

Bash and Node scripts for build/publish/setup chores that don't belong in Gradle.

- `build-installer.sh` — packs the Vue installer into the single-file `installer.html` artifact bundled with the controller
- `new-module.mjs` — scaffolds a new first-party module under `java/cloud-modules/<name>/` (project skeleton, manifest, MC-plugin stubs, gradle wiring)
- `perf-baseline-check.sh` — runs the perf-baseline harness, compares against `infra/perf/baselines.json`, fails if drift exceeds threshold (used by `nightly.yml`)
- `publish.sh` — release helper: tags + pushes + triggers the release workflow
- `uninstall.sh` — guided removal of a PrexorCloud install (native or compose)

Each script supports `--help`. None of them touch production credentials directly; secrets stay in `.env` or via cosign-keyless.
