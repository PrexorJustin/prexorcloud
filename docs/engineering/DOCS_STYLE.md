# Docs & README style guide

The single standard for everything written for humans in this repo: the public docs under `docs/public/`, every `README.md`, and the engineering notes under `docs/engineering/`. It exists so a reader can drop into any document and find the same voice, structure, and visual language — and so the upcoming docs rewrite (northstar Track I) is mechanical, not a debate.

This guide follows its own rules. If a sentence here reads like a machine wrote it, that's a bug.

---

## 1. Who we write for

Every document declares one primary audience in its first lines and is written for that reader's task — not for the engineer who built the feature.

| Audience | Who they are | What they need | Where it lives |
|---|---|---|---|
| **Operator** | Runs PrexorCloud in production. On call at 2 AM. | Install, configure, upgrade, recover, observe. Copy-paste commands that work. | `docs/public/{getting-started,guides,operations,recipes}/`, deploy + installer READMEs |
| **Integrator-developer** | Builds plugins, mods, or modules against our APIs. | A working minimal example, the capability surface, the contract. | `docs/public/{guides,reference}/`, `cloud-modules/*/README`, plugin READMEs |
| **Contributor** | Hacks on PrexorCloud itself. | Architecture, build, test, where things live, why. | root `README`, `java/README`, `docs/engineering/`, module READMEs |

When a document serves two audiences, split it with headings (`## For operators` / `## For developers`) rather than blending — never make an operator read internals to find a command.

**The test:** could the intended reader finish their task from this page alone, without asking us? If not, it isn't done.

---

## 2. Voice

House voice: **direct, concrete, calm.** Like the root README — "Built for the op on call at 2 AM, not the demo." We earn trust by being specific, not by adjectives.

**Do**
- Write in the active voice. *"The controller schedules the instance"* — not *"the instance is scheduled by the controller."*
- Use second person for anything task-oriented. *"Run `prexorctl up`."*
- Lead with the point. First sentence of every section answers "why am I reading this?"
- Be specific and verifiable: name the file, the flag, the port, the exact error. "Fails with `422 SIGNATURE_VERIFICATION_FAILED`" beats "may not work."
- Show, don't claim. A runnable example outranks a paragraph describing it.
- Keep sentences short. One idea each. Prefer a list to a comma-spliced paragraph.

**Don't**
- No marketing adjectives as load-bearing words: *powerful, robust, seamless, blazing-fast, cutting-edge, enterprise-grade, effortless.* If it's robust, show the DR drill — don't say "robust."
- No robotic hedging and filler. Banned openers and crutches: *"It's worth noting that", "In order to" (→ "to"), "simply" / "just", "leverage" (→ "use"), "utilize" (→ "use"), "in the world of", "at the end of the day", "a wide range of", "delve into", "this section will discuss", "as we can see".*
- No "we" navel-gazing in user docs. The reader doesn't care how we felt about the design — they care what to type. (Engineering notes under `docs/engineering/` are the exception: there, "why we chose X over Y" is the point.)
- No future/conditional tense for shipped behavior. "The daemon retries three times" — not "the daemon will retry."

**Tone by surface:** public docs are instructional and warm; READMEs are crisp and confident; engineering notes are honest and opinionated (including about trade-offs and what's still open).

---

## 3. Headings, terms, and mechanics

- **Sentence case headings.** "Getting started", not "Getting Started". (Enforced in the dashboard by `pnpm lint:voice`; apply it to prose too.)
- **No emoji in product copy.** The prescribed CLI glyphs are fine and encouraged in terminal output and status lines: `✓ ✗ ● ○ → ▶ │ └ ├ … ⌘ ↵`. README hero/marketing badges are not emoji.
- **One spelling per term.** Use the glossary below verbatim — casing matters.
- **Code in fences with a language tag** (` ```bash `, ` ```java `, ` ```yaml `). Inline code for any filename, flag, env var, type, or command: `prexorctl`, `--node-id`, `CLOUD_INSTANCE_ID`, `StateStore`.
- **Commands are copy-pasteable.** No leading `$`. Show expected output below the command when it matters.
- **Link, don't repeat.** Each fact lives in one canonical place; everything else links to it. A broken or duplicated explanation is a bug.

### Glossary (canonical terms)

| Term | Use | Not |
|---|---|---|
| Controller | the control-plane process | "master", "server" |
| Daemon | the per-node agent | "agent", "worker", "slave" |
| Group | a scalable set of instances | "pool", "cluster" |
| Instance | one running MC server/proxy process | "server" (ambiguous) |
| Template | versioned config + files layer | "preset" |
| Network | routing composition over groups | "proxy group" |
| Module | controller-side extension (Capability API) | "addon" |
| Plugin | the in-MC integration (Paper/Velocity/Fabric/NeoForge/Geyser) | "mod" (except actual Forge/Fabric mods) |
| PrexorCloud | the product (one word, capital C) | "Prexor Cloud", "prexorcloud" in prose |
| prexorctl | the CLI (lowercase, code font) | "PrexorCTL" |

---

## 4. Structure

### Public docs are task-first, not subsystem-first

Organize by what the reader is trying to do, not by how the code is split. The directory layout under `docs/public/<lang>/` reflects this:

- `getting-started/` — zero to a running cluster, fast. The single most important page.
- `guides/` — one task per page, start to finish ("Deploy a network", "Write your first module").
- `operations/` — run it in production (upgrade, backup, DR, scaling, observability).
- `recipes/` — short, copy-paste solutions to common setups.
- `reference/` — exhaustive and dry (REST/CLI/config/capability surface). Generated where possible.
- `concepts/` — the mental model (how scheduling works, the trust root). Read once, refer back.
- `internals/` — for contributors; protocol, architecture.

Every doc opens with a one-sentence "what you'll do here" and, for guides, a "before you start" prerequisites line.

### READMEs follow the template

Every `README.md` in the repo uses [`templates/README.template.md`](./templates/README.template.md). It is not optional and it is not per-author taste. See §5 for the required sections and the per-package matrix.

---

## 5. README standard

A README answers four questions in under ten seconds of scrolling: **what is this, why would I use it, how do I start, where do I go next.** Everything else is secondary.

**Required sections, in order:**
1. **Hero** — centered: name, one-line tagline, the badge row. (Root + each top-level surface.)
2. **One-paragraph "what"** — what this package is and the one job it does.
3. **Quickstart** — the shortest path to it working. Copy-paste, no prose between steps.
4. **Architecture / how it fits** — a short ASCII diagram or a sentence placing it in the system. Link to the deep-dive; don't inline it.
5. **Usage / API** — the common operations, each with a runnable example.
6. **Links** — docs, related packages, contributing.
7. **License** — one line.

Module/sub-package READMEs may drop the hero badges but keep the order.

### Visual language

- **Badges:** [shields.io](https://shields.io) `?style=flat-square`. Use the Reef palette for accent badges — `color=0c8aa8` (Reef light) for "docs"/info, standard semantic colors for build/license. Keep the row to ≤ 5; CI · License · Docs · Version is the canonical set.
- **Diagrams:** ASCII for the README itself (renders everywhere, diffs cleanly), using the box glyphs `┌ ┐ └ ┘ │ ─ ├ ┤ ┬ ┴ v`. For docs pages, Mermaid in the design-system palette (primary Reef `#4ec5d4` dark / `#0c8aa8` light over ink/sand — see [`design-system.md`](./design-system.md)).
- **Screenshots / GIFs:** for the dashboard, installer, and website only, where a picture genuinely beats words. Dark theme, the Reef accent, real data (no `lorem`). Store under the package's `docs/` or `assets/`.
- **Color in prose:** none. Status uses the CLI glyphs, not colored text.

### The hero (copy this exactly, swap the content)

```markdown
<p align="center">
  <strong>PrexorCloud</strong><br>
  <em>Minecraft cloud orchestration, production-grade by default.</em>
</p>

<p align="center">
  <a href="…/actions"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/ci.yml?branch=main&style=flat-square" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>
```

### Per-package matrix

Every README in the repo and the audience each is written for. The sweep (Track I.3) brings every one to this standard; each carries the License line (required section 7) even when it drops the hero badges.

| README | Audience | Hero badges |
|---|---|---|
| `/README.md` | Contributor + operator | yes |
| `installer/README.md` | Operator | yes |
| `dashboard/README.md` | Contributor | yes |
| `website/README.md` | Contributor | yes |
| `design-system/README.md` | Contributor | yes |
| `java/README.md` | Contributor | yes |
| `docs/README.md` | Contributor | no |
| `tools/README.md` | Operator/contributor | no |
| `scripts/README.md` | Operator/contributor | no |
| `deploy/compose/README.md` | Operator | no |
| `deploy/systemd/README.md` | Operator | no |
| `java/cloud-modules/example/README.md` | Integrator-developer | no |
| `dashboard/packages/vscode-extension/README.md` | Integrator-developer | yes (marketplace) |
| `java/build-logic/README.md` | Contributor | no |
| `dashboard/tests/visual/README.md` | Contributor | no |
| `docs/public/en/internals/protocol/_generated/README.md` | — (generated) | exempt — auto-generated marker, not hand-maintained |

---

## 6. Code examples

Examples are the docs. Hold them to a higher bar than prose.

- **Runnable and minimal.** The smallest thing that works, nothing decorative. It should compile/run as written.
- **Copy-pasteable.** No `$` prompts, no `<placeholders>` without saying what to put there, no truncated `...` in the middle of something you're meant to run.
- **Show the result.** For a command, show the output or the effect. For an API call, show the response shape.
- **Real names, real values.** `survival-lobby`, `node-fra-1`, port `30000` — not `foo`, `mygroup`, `xxx`.
- **One concept per example.** If it teaches two things, it's two examples.

---

## 7. Enforcement

- `pnpm lint:voice` already guards sentence-case headings and no-emoji in the dashboard; the same rules apply to all prose here by convention.
- A future `readme:lint` (Track I.3) may assert the required README sections and badge style. Until then, the per-package matrix in §5 is the checklist.
- Dead links are a build failure. Two gates cover the two link spaces: the `docs-links` CI job runs [`tools/check-doc-links.mjs`](../../tools/check-doc-links.mjs) over every README and the engineering / runbook / security notes (relative file paths, GitHub-rendered); the website workflow's `pnpm check:links` validates the public site under `docs/public/` (Starlight URL space). Still: every link you add, you click.

---

## 8. The one-line summary

Write for the reader's task, in plain direct prose, with runnable examples and a consistent visual frame — and link to the one canonical place for every fact. If you remember nothing else: **show the command, cut the adjectives, pick the reader.**
