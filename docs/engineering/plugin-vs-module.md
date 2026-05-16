# Plugin vs. Module — which to write

PrexorCloud supports two first-class deployment models for extending the
cluster. They are **not** a hierarchy: a standalone plugin is not a "lite
module", it is a different model with its own tooling, docs, and scaffold.

## Decision flowchart

```
                      ┌────────────────────────────────────────┐
                      │ Do you need any of:                    │
                      │   • cluster-wide state                 │
                      │   • a REST API                         │
                      │   • dashboard UI                       │
                      │   • coordination across nodes          │
                      │   • capabilities consumed by other     │
                      │     plugins or modules                 │
                      └─────────────────┬──────────────────────┘
                                        │
                              yes       │       no
                  ┌─────────────────────┴────────────────────┐
                  ▼                                          ▼
          ┌───────────────┐                          ┌───────────────┐
          │  MODULE       │                          │ Do you need a │
          │  (Path B)     │                          │ plugin on     │
          │               │                          │ multiple game │
          │ prexorctl     │                          │ platforms     │
          │ module new    │                          │ (paper+folia, │
          └───────────────┘                          │  paper+velo)? │
                                                     └──────┬────────┘
                                                            │
                                                  yes       │       no
                                              ┌─────────────┴─────────────┐
                                              ▼                           ▼
                                   ┌────────────────────┐       ┌──────────────────┐
                                   │  MODULE that       │       │ STANDALONE       │
                                   │  bundles a plugin  │       │ PLUGIN (Path A)  │
                                   │  extension         │       │                  │
                                   │                    │       │ prexorctl        │
                                   │ prexorctl          │       │ plugin new       │
                                   │ module new         │       └──────────────────┘
                                   │ (with plugin       │
                                   │  targets)          │
                                   └────────────────────┘
```

## Side-by-side

|                                | Standalone plugin (Path A)                       | Module (Path B)                                                         |
| ------------------------------ | ------------------------------------------------ | ----------------------------------------------------------------------- |
| Lives at                       | `java/cloud-plugin/cloud-plugin-<name>/`         | `java/cloud-module/cloud-module-<name>/`                                |
| Manifest                       | none — `@CloudPlugin` annotation only            | `module.yaml` + generated `META-INF/prexor-module.json`                 |
| Deployment                     | drop the shaded jar into `plugins/`              | `prexorctl module install <bundle>` against the controller             |
| Frontend                       | n/a                                              | optional Vue package via `dashboard/packages/module-sdk`                |
| REST endpoints                 | n/a                                              | `/api/v1/modules/<id>/<sub>` via `ModuleRouteRegistry`                  |
| Per-module storage             | n/a                                              | Mongo + Redis primitives, isolated by module id                         |
| Capability registry            | consume only (via `cloud-api`)                   | provide and consume                                                     |
| Cross-platform variants        | one platform per scaffold; rerun for more        | per-platform plugin extensions ship inside the same module              |
| Scaffold                       | `prexorctl plugin new --platform=<p>`            | `prexorctl module new` (TUI wizard)                                     |
| Signing                        | optional, plugin author's choice                 | cosign + Rekor; verifier enforces on install                            |

## When to pick what

- **Need cluster-wide state, REST API, dashboard UI, or coordination across
  nodes?** → Module (Path B).
- **Need only in-game / in-proxy behaviour on a single platform?** → Standalone
  plugin (Path A).
- **Need both — server-side game logic plus dashboard / cluster state?** →
  Module that bundles a plugin extension.

The single platform case is genuinely common (e.g. an admin command suite that
only runs on Paper). For that case, the module wrapper would be theatre — pick
Path A.

## See also

- [`docs/engineering/modules.md`](modules.md) — module system reference (lifecycle,
  capabilities, REST, storage, frontend SDK).
- [`docs/API_OVERHAUL.md`](API_OVERHAUL.md) — guiding principles behind the
  plugin / module split (Layer 9 codifies the symmetric tooling).
