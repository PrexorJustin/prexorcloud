<p align="center">
  <strong>PrexorCloud for VS Code</strong><br>
  <em>Browse instances, tail logs, and edit templates against a controller — without leaving the editor.</em>
</p>

<p align="center">
  <a href="../../../LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>

---

An MVP extension for working against a [PrexorCloud](../../..) controller without
leaving the editor.

## Features

- **Connect to a controller** — `PrexorCloud: Connect to Controller` prompts for
  the controller URL and credentials. The URL is saved to settings; the bearer
  token is kept in VS Code's `SecretStorage`.
- **Browse instances** — the **PrexorCloud** activity-bar view shows groups and
  their instances with live state, node, and player count.
- **Tail logs in the editor** — click an instance (or use the inline action) to
  stream its console into a dedicated Output channel, scrollback first.
- **Edit templates inline** — the **Templates** view browses every template's
  file tree. Opening a file edits it through the `prexorcloud-template:` file
  system, so a normal save writes back to the controller.

## Development

```sh
pnpm install            # from the dashboard/ workspace root
pnpm --filter @prexorcloud/vscode-extension compile
```

Then press <kbd>F5</kbd> in VS Code to launch an Extension Development Host.

- `compile` / `watch` — bundle `src/extension.ts` with esbuild into `out/`.
- `typecheck` — `tsc --noEmit` over the sources.

The extension consumes the workspace-local `@prexorcloud/api-sdk` package for a
typed controller client; the SSE console stream is read directly via `fetch`.

## Scope

This is an MVP. Not yet covered: creating/renaming/deleting
template files, multi-context switching, and instance lifecycle actions.

## Links

- [dashboard/](../../README.md) — the full operator control panel this extension borrows its SDK from
- [Plugin & module SDK reference](https://prexor.cloud) — the controller APIs the extension calls
- [Contributing](../../../CONTRIBUTING.md) — build and PR conventions

## License

Apache 2.0 — see [LICENSE](../../../LICENSE).
