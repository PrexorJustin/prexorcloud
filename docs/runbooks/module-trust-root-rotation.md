# Module Trust-Root Rotation

Deep-dive companion to [`rotate-secrets.md` §Module Trust Root](rotate-secrets.md#module-trust-root).
The trust root is the set of public keys / CA certs PrexorCloud accepts
when verifying a signed platform-module JAR. Rotate it when a module
author rotates their signing key, when you onboard a new first-party or
community signer, or when a key is suspected compromised.

> This is **not** the daemon mTLS CA. For that, see
> [`ca-rotation.md`](ca-rotation.md). The trust root only governs *module
> install verification*.

---

## What the trust root is

The signature verifier (`PlatformModuleSignatureVerifier`) is built once
at controller startup from `modules.signing` and runs in one of two modes:

- **`COSIGN_BUNDLE`** (recommended) — verifies a `<jar>.cosign.bundle`
  (cosign `sign-blob` format). The trust-root PEM file may contain raw
  **PUBLIC KEY** blocks (RSA / EC / Ed25519 keyed signing) and/or
  **CERTIFICATE** blocks (PKIX CA roots, for modules carrying an embedded
  Fulcio-style leaf). Optional offline Rekor transparency-log enforcement
  via `modules.signing.rekor.policy=REQUIRE_SET` + a `rekor.publicKey`
  PEM.
- **`KEYED`** (legacy) — verifies a sidecar `<jar>.sig` against PEM
  **PUBLIC KEY** blocks in the trust root.

### Relevant config (`modules.signing`)

| Key | Meaning |
| --- | --- |
| `modules.signing.required` | whether verification is mandatory (fail-closed when true) |
| `modules.signing.trustRoot` | **path to the PEM bundle** of trusted keys/certs |
| `modules.signing.mode` | `KEYED` or `COSIGN_BUNDLE` |
| `modules.signing.allowUnsignedDevelopment` | permit unsigned JARs in the `dev` profile only |
| `modules.signing.rekor.policy` | `DISABLED` or `REQUIRE_SET` |
| `modules.signing.rekor.publicKey` | path to Rekor's public-key PEM (for `REQUIRE_SET`) |

`modules.signing.trustRoot` is a **sensitive path** in the cluster config
(masked in REST reads unless `?reveal=true` + `CLUSTER_MANAGE`).

---

## Two important properties

1. **The trust root does not reload live.** `PrexorCloudBootstrap`
   instantiates the verifier once and hands it to the module manager —
   there is no cluster-config subscriber for `modules.signing` (unlike
   CORS / rate-limit / JWT, which *do* hot-reload). **A trust-root change
   takes effect only after a controller restart.**
2. **Already-installed modules are not re-verified.** Verification happens
   at *install* time. Rotating the trust root does not retroactively evict
   a module that was installed under the old key — but a re-install, or a
   fresh controller that re-loads stored modules, verifies against the new
   root. This is why step 3 below (re-sign + re-upload) is mandatory, not
   optional.

---

## Procedure: add a new signer, retire the old (overlap)

This is the zero-gap path — both keys are trusted during the overlap so
no module is ever un-installable.

1. **Append the new key/cert to the trust-root PEM.** Concatenate the new
   **PUBLIC KEY** (or **CERTIFICATE**) block onto the existing bundle so
   both old and new are trusted:
   ```bash
   cat new-signer.pub.pem >> /path/to/module-trust-root.pem
   ```
   If you manage signing config in the **cluster config** (HA), patch it
   there instead of editing each `controller.yml`:
   ```bash
   # Read the active version number first:
   curl -sk -H "Authorization: Bearer $TOKEN" \
       https://controller:8080/api/v1/cluster/config | jq .activeVersion

   curl -sk -X POST -H "Authorization: Bearer $TOKEN" \
       -H 'Content-Type: application/json' \
       https://controller:8080/api/v1/cluster/config \
       -d '{"parentVersion": <active>,
            "reason": "trust-root: add new signer",
            "patch": {"modules": {"signing": {"trustRoot": "/path/to/module-trust-root.pem"}}}}'
   ```
   (The PEM file content lives on each controller host; the config key is
   only the path. Distribute the updated PEM to every controller.)
2. **Restart controllers in turn** so the verifier picks up the larger
   trust root. In HA, one at a time:
   ```bash
   sudo systemctl restart prexorcloud-controller
   ```
3. **Re-sign every module with the new key and re-upload** so each stored
   JAR carries a signature the new root accepts:
   ```bash
   # (re-sign out of band with cosign / your keyed signer, producing the
   #  .cosign.bundle or .sig sidecar), then:
   prexorctl module upload ./my-module.jar
   ```
4. **Remove the old key/cert** from the trust-root PEM once every module
   is re-signed. Re-distribute the trimmed PEM (or patch the cluster
   config again).
5. **Restart controllers** a final time.

> **Order matters.** A module still signed only with the old key
> **fails closed at install** after step 4. Do not skip step 3.

## Procedure: emergency revocation (compromised signer)

If a signing key is compromised, you cannot wait for the overlap:

1. **Remove the compromised key immediately** from the trust-root PEM
   (and patch the cluster config / edit `controller.yml`).
2. **Restart controllers.** New installs signed with the bad key now
   fail-closed.
3. **Audit installed modules.** Anything installed under the compromised
   key should be treated as suspect — re-sign from clean source with a
   new key and re-upload, or `prexorctl module delete <name>` if you
   cannot vouch for it.
4. If `rekor.policy=REQUIRE_SET` is enabled, the transparency log gives
   you an independent record of what was legitimately signed and when —
   use it to scope the blast radius.

---

## Verify

```bash
# Confirm the active trust-root path the cluster believes is current:
curl -sk -H "Authorization: Bearer $TOKEN" \
    "https://controller:8080/api/v1/cluster/config?reveal=true" | jq '.patch.modules.signing'

# Prove the new root works end-to-end: a module signed ONLY with the new
# key should install (201); one signed only with a removed key should be
# rejected 422 SIGNATURE_VERIFICATION_FAILED.
prexorctl module upload ./module-signed-with-new-key.jar
```

## Common failures

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Trust-root change seems ignored | verifier only re-reads on boot | restart the controller |
| `422 SIGNATURE_VERIFICATION_FAILED` after rotation | module still signed with a removed key | re-sign with the new key, re-upload (step 3) |
| Patch to `modules.signing.trustRoot` shows `***` on read | sensitive-path masking | re-read with `?reveal=true` and `CLUSTER_MANAGE` |
| `REQUIRE_SET` installs all fail | `rekor.publicKey` path missing/wrong on the host | point it at the Rekor public-key PEM; restart |
| New key added but installs still fail | controllers in HA restarted unevenly; one still on old root | restart the lagging controller |

## Related

- [`rotate-secrets.md`](rotate-secrets.md) — quick summary and the full secret-rotation cadence.
- [`ca-rotation.md`](ca-rotation.md) — the daemon mTLS / cluster CAs (a different trust domain).
- `docs/engineering/decisions.md` — ADR on module signing (cosign + offline Rekor).
- The cluster-config surface: `GET/POST /api/v1/cluster/config` (versioned, rollback-able).
