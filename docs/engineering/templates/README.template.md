<!--
  Canonical README template — see ../DOCS_STYLE.md.
  Copy this file, fill every {{PLACEHOLDER}}, delete the sections that don't apply,
  and delete this comment. Keep the section order. Sentence-case headings, no emoji
  in prose (CLI glyphs ✓ → ● are fine), runnable copy-paste examples, link don't repeat.
  Module/sub-package READMEs may drop the hero badges but keep the order.
-->

<p align="center">
  <strong>{{NAME}}</strong><br>
  <em>{{ONE_LINE_TAGLINE — what it is, in under ten words}}</em>
</p>

<!-- Badge row: keep ≤5. CI · License · Docs · Version is canonical. Reef accent = 0c8aa8. -->
<p align="center">
  <a href="https://github.com/prexorjustin/prexorcloud/actions"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/ci.yml?branch=main&style=flat-square" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>

---

## What is {{NAME}}?

{{One paragraph. What this package is and the single job it does. Name the audience
implicitly by the task — operator, integrator-developer, or contributor. No adjectives
doing the work; state what it does and for whom.}}

## Quickstart

{{The shortest path from nothing to it working. Copy-paste, no prose between steps.
Show the result.}}

```bash
{{command}}
```

```
{{expected output}}
```

## How it fits

{{A short ASCII diagram or one sentence placing this in the system. Link the deep-dive,
don't inline it.}}

```
{{ascii diagram using ┌ ┐ └ ┘ │ ─ ├ ┤ v — or delete this block and use a sentence}}
```

## Usage

{{The common operations, each a runnable minimal example with its result. One concept
per example. Real names and values.}}

## {{For developers | For operators}}

{{Optional. When the package serves two audiences, split here instead of blending.}}

## Links

- [Documentation]({{https://prexor.cloud/...}})
- [{{Related package}}]({{../path}})
- [Contributing](../CONTRIBUTING.md)

## License

{{Apache 2.0}} — see [LICENSE](./LICENSE).
