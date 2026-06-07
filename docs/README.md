# PrexorCloud documentation

PrexorCloud is a self-hosted orchestrator for Minecraft networks. One controller, one operator team, 50–5000 servers, runs on bare metal or small VM fleets.

This directory holds the published documentation. Three audiences:

| Subdir | Audience | Purpose |
|---|---|---|
| [`public/`](public/) | **Website / end-users** | Source of truth for everything on `prexor.cloud`. Astro Starlight (in [`website/`](../website/)) renders `public/en/` into the site. All concept docs, reference, recipes, and guides live here. |
| [`runbooks/`](runbooks/) | **On-call operators** | Numbered, copy-pastable incident runbooks (recover-controller, recover-mongo, recover-redis, drain-node, rotate-secrets, scale, upgrade, troubleshoot). |
| [`security/`](security/) | **Maintainers + auditors** | Threat model, license registry. |

Plus one top-level artefact:

- [`openapi.json`](openapi.json) — REST spec consumed by `starlight-openapi` + Scalar at site build time. Auto-generated from `@OpenApi` annotations on controller route handlers; refreshed by `./gradlew :cloud-controller:syncOpenApi` (runs as a `finalizedBy` on `compileJava`, so any controller build keeps it current).

## Where to start

| If you want to … | Read |
|---|---|
| Read the user-facing intro | [`public/en/getting-started/what-is-prexorcloud.md`](public/en/getting-started/what-is-prexorcloud.md) |
| Understand the system in 10 minutes | [`public/en/internals/architecture.md`](public/en/internals/architecture.md) |
| Know what's stored where | [`public/en/internals/storage-schema.md`](public/en/internals/storage-schema.md) |
| Configure a controller or daemon | [`public/en/operations/configuration.md`](public/en/operations/configuration.md) |
| Recover from an incident | [`runbooks/`](runbooks/) (start with `troubleshoot.md`) |
| Ship a module | [`public/en/concepts/modules/index.md`](public/en/concepts/modules/index.md) and [`public/en/reference/module-sdk/`](public/en/reference/module-sdk/) |
| Wire up monitoring | [`public/en/operations/monitoring.md`](public/en/operations/monitoring.md) |
| Audit the threat model | [`security/threat-model.md`](security/threat-model.md) |
| Read the REST API spec | [`openapi.json`](openapi.json) |

## What PrexorCloud is *not*

- Not multi-tenant. One operator team owns the controller.
- Not multi-region. Run one controller cluster per region if you must.
- Not a SaaS. Self-hosted only.
- Not GitOps. Templates and groups are imperative.
- Not OIDC / SSO / SAML / passkeys / MFA. Username + password + JWT, optional password reset by email.
- Not a Grafana pack publisher. We expose Prometheus metrics on `/metrics`; bring your own dashboards.
- Not OpenTelemetry. Prometheus is enough at this scale.
- Not Kubernetes-native. Compose ships; Helm is a stretch goal at best.
- Not a marketplace. Modules are cosign-signed bundles. Authors host their own.
- Not a sandbox. Daemon spawns processes with `ProcessBuilder`; cgroup/container isolation is not in scope.
