<p align="center">
  <strong>tools/</strong><br>
  <em>Generators that turn source-of-truth into committed docs.</em>
</p>

---

## What is tools/?

Three generators that read an authoritative source — the `.proto` contracts, the `prexorctl` CLI, the perf baselines — and render committed Markdown under `docs/public/`. Run them and commit the result; CI re-runs each one and fails on any working-tree diff, so the docs can never drift from the thing they describe.

These are build-time generators, not runtime code. Nothing here ships in a binary.

## Quickstart

Regenerate everything after touching a source, from the repo root:

```bash
tools/gen-grpc-docs.sh
node --experimental-strip-types tools/gen-cli-docs.ts
node --experimental-strip-types tools/gen-benchmarks.ts
git diff --stat docs/public/    # the diff is the change you commit
```

```
 docs/public/en/internals/protocol/_generated/daemon_service.md | 12 ++++--
 docs/public/en/reference/cli/_generated/module.md              |  6 +++
```

No diff means the docs already match the source — nothing to do.

## How it fits

Each generator writes into an `_generated/` tree that the website's Astro content collection skips (the `_` prefix is excluded in `website/src/content.config.ts`). Hand-curated pages stay the canonical entry points and link into `_generated/` where the deeper reference helps.

```
contracts/*.proto ──┐
prexorctl --help  ──┼──> tools/gen-*  ──> docs/public/en/**/_generated/  ──> CI drift gate
baselines.json    ──┘
```

## Usage

`gen-grpc-docs.sh` — renders every `.proto` under `java/cloud-protocol/src/main/proto/prexorcloud/` into `docs/public/en/internals/protocol/_generated/`. Run after editing a proto contract.

```bash
tools/gen-grpc-docs.sh
```

`gen-cli-docs.ts` — builds `prexorctl`, walks `--help` recursively, and emits one page per subcommand tree into `docs/public/en/reference/cli/_generated/`. Run after adding or changing a CLI command.

```bash
node --experimental-strip-types tools/gen-cli-docs.ts
```

`gen-benchmarks.ts` — projects `infra/perf/baselines.json` into `docs/public/en/benchmarks.md` so the public benchmarks page shows the same numbers a contributor sees in the file. Run after bumping a baseline.

```bash
node --experimental-strip-types tools/gen-benchmarks.ts
```

All three are idempotent: re-running on unchanged inputs produces byte-identical output. That property is what the CI drift gate relies on.

## Links

- [Docs & README style guide](../docs/engineering/DOCS_STYLE.md)
- [scripts/](../scripts/README.md) — build/publish chores
- [Contributing](../CONTRIBUTING.md)

## License

Apache 2.0 — see [LICENSE](../LICENSE).
