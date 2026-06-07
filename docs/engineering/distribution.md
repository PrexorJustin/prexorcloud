# Distribution

How PrexorCloud ships, how operators install it, and what artefacts are signed.

## Artefacts

Each release tag (`v*`) produces:

| Artefact | Where | Signed how |
|---|---|---|
| `prexorctl` Linux/macOS/Windows × x86_64 + arm64 binaries | GitHub release | Cosign keyless OIDC (Fulcio + Rekor, GitHub Actions identity); signature on `checksums.txt` covers every archive |
| CycloneDX SBOM, one per archive | GitHub release | n/a (data, not executable) |
| sha256 checksums | GitHub release | Cosign keyless on the checksums file |
| Multi-arch container images for controller / daemon / dashboard | GHCR (`ghcr.io/<owner>/prexorcloud-{controller,daemon,dashboard}:<semver>` and `:latest`) | Cosign keyless on each tag-by-digest |
| BuildKit-native image SBOM + max-mode provenance attestation | attached to the OCI image | n/a (sigstore-signed via the same image signing) |

Verification of `prexorctl` (the recipe ships in the release notes):

```bash
cosign verify-blob \
  --certificate-identity-regexp "^https://github.com/<owner>/prexorcloud/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --signature checksums.txt.sig \
  --certificate checksums.txt.pem \
  checksums.txt
sha256sum -c checksums.txt
```

Verification of an image:

```bash
cosign verify \
  --certificate-identity-regexp "^https://github.com/<owner>/prexorcloud/.github/workflows/release-images.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/<owner>/prexorcloud-controller:<semver>
```

The release workflow also runs `cosign verify` against its own freshly-signed images as the last step, so a broken signature fails CI before operators see it.

### Why keyless

We do not maintain a long-lived private key. Cosign's keyless flow uses GitHub Actions' OIDC identity to receive a short-lived Fulcio cert, signs the artefact, logs the entry to Rekor, and discards the key. Verification proves "this artefact was signed by *that* GitHub Actions workflow on that repo" — which is exactly what operators want to verify.

For module signing (a different problem — third-party authors, not us), see [`modules.md`](modules.md).

## Reference deployments

### Docker Compose (recommended)

`deploy/compose/` ships a Compose v2 reference stack:

- `compose.yml` — controller, daemon, dashboard, MongoDB, Valkey on a private `prexor-internal` network. Healthchecks. Version-pinned images. `.env`-driven host ports / heap sizes.
- `controller.yml` and `daemon.yml` — config bind-mounts.
- `.env.example` — copy to `.env` and fill in.
- `README.md` — start, stop, upgrade, expose-the-dashboard guide.

```bash
cp .env.example .env
$EDITOR .env
docker compose up -d
```

The Compose stack matches what `prexorctl setup --install-mode=compose` generates. They are two faces of the same install path.

### systemd (bare metal)

`deploy/systemd/` ships unit examples:

- `prexorcloud-controller.service` — hardened with `ProtectSystem=strict`, `NoNewPrivileges`, scoped `ReadWritePaths`.
- `prexorcloud-daemon.service` — relaxed for child MC processes (`LimitNOFILE=131072`, `TasksMax=infinity`, drain-friendly `TimeoutStopSec=120s`).
- `README.md` — directory layout, user creation, daemon enrolment.

Use this on hosts where Docker is not appropriate (e.g. you already manage a fleet via Ansible, or you have hard kernel-level requirements).

### prexorctl setup

`prexorctl setup` is the one-shot installer. Two modes:

| Mode | Platforms | Notes |
|---|---|---|
| `native` | Linux only | Installs Java / MongoDB / Valkey on supported distros. Optionally registers systemd services. |
| `compose` | Linux + macOS + Windows | Requires Docker. Generates a Compose project around the downloaded JARs. |

On macOS and Windows, use `prexorctl setup --install-mode=compose`.

### Helm chart

Not in v1. See [`decisions.md`](decisions.md) §"Compose-first install, Helm is a stretch."

## Upgrade flow

1. Read the release notes for the target version.
2. Run `prexorctl backup create` (always).
3. Pull the new artefact (`prexorctl upgrade`, `docker compose pull`, `apt`, whatever your install mode prescribes).
4. Restart the controller. Daemons reconnect automatically; gRPC stream reconciliation handles in-flight state.
5. Run `prexorctl status` and check `/api/v1/system/ready`.

Daemon and dashboard upgrades are independent of the controller — backward / forward compatibility is guaranteed across one minor version. Bigger jumps require reading the release notes.

See [`runbooks/upgrade.md`](runbooks/upgrade.md).

## Disaster recovery

Documented separately in [`dr.md`](dr.md). Summary RPO / RTO:

| Tier | RPO | RTO |
|---|---|---|
| MongoDB | ≤ 1h | 30 min |
| Valkey | best-effort | 5 min |
| Filesystem | ≤ 24h | 30 min |
| Daemon hosts | n/a | 15 min/host |

Nightly CI runs a real DR drill — the `dr-drill` job in `.github/workflows/nightly.yml` seeds groups + templates, takes a backup, wipes Mongo + Valkey, restarts the controller, and runs `POST /api/v1/restore` (dry-run + apply). Tagged `@Tag("dr")` so the regular test pass skips it.

## CI / supply chain

| Job | Purpose |
|---|---|
| `cve-scan` (Trivy) | Vulnerability scan against the controller / daemon / dashboard images. |
| `sbom` (Syft) | Generates CycloneDX SBOM per image. |
| `goreleaser-check` | Validates `cli/.goreleaser.yaml` so release config can't drift silently. |
| `dr-drill` (nightly, scheduled) | The end-to-end DR drill. |
| `perf-baselines` (nightly, scheduled) | Perf drift comparator. |

Trivy results gate PRs by default. SBOMs are uploaded as build artefacts and attached to releases.

## License posture

Three third-party stores get particular attention:

- **MongoDB** — SSPL. We **never** embed Mongo. The reference install runs Mongo as its own service; SSPL applies to the *Mongo distribution*, not to PrexorCloud as a downstream consumer.
- **Redis** — BSL since 7.4. We default to **Valkey** (BSD-3) to avoid the question entirely. Operators who prefer Redis can use it; the controller speaks the Redis protocol unchanged.
- **Valkey** — BSD-3. Vendored as the default coordination store.

Full license matrix at [`security/licenses.md`](security/licenses.md).

## Release cadence

There is no fixed cadence. Release when there is enough to release. Breaking changes are reserved for major versions; minor versions are additive; patch versions are bug fixes.

Pre-release tags (`v1.x.y-rc.1`) are signed and published the same way as release tags but flagged as pre-release in GitHub. Use them when you want early access to a feature without waiting for the corresponding stable release.
