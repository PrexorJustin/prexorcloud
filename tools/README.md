# tools/ — codegen utilities

Scripts that *generate* committed artifacts (docs, fixtures) so they're reproducible. Not invoked at runtime.

- `gen-grpc-docs.sh` — reads `contracts/*.proto`, writes Markdown gRPC reference into `docs/public/en/reference/grpc/`
- `gen-cli-docs.ts` — invokes `prexorctl --help` recursively, emits the CLI reference page into `docs/public/en/reference/cli/`
- `gen-benchmarks.ts` — reads `infra/perf/baselines.json`, renders the `docs/public/en/benchmarks.md` page

Run before releases or after touching the corresponding sources. All three are idempotent — re-running on unchanged inputs produces byte-identical output.
