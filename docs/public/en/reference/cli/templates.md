---
title: Templates
description: prexorctl template — list templates, inspect version history, and roll a template back to a recorded snapshot.
---

A **template** is a tree of files (configs, plugins, world data) the daemon
unpacks into an instance's working directory before the instance boots.
Groups reference templates by name. The controller content-addresses each
template by a hash over its files and keeps every prior hash as a version
snapshot.

`prexorctl template` exposes three subcommands against the controller's
`/api/v1/templates` REST API:

| Subcommand | Endpoint | Permission |
|---|---|---|
| `template list` | `GET /api/v1/templates` | `templates.view` |
| `template versions <name>` | `GET /api/v1/templates/{name}/versions` | `templates.view` |
| `template rollback <name>` | `POST /api/v1/templates/{name}/rollback` | `templates.update` |

There is no CLI upload, create, file-browse, or delete command. Those
operations exist on the REST API and the dashboard but are not wired into
`prexorctl`. See [REST routes](#rest-routes-not-exposed-by-the-cli) below.

Source: `cli/cmd/template.go`,
`java/cloud-controller/.../rest/route/TemplateRoutes.java`.

## Global flags

Every subcommand inherits the root persistent flags from `cli/cmd/root.go`:

| Flag | Short | Default | Effect |
|---|---|---|---|
| `--json` | `-j` | `false` | Emit JSON instead of a table. Also enabled by `PREXOR_OUTPUT=json`. |
| `--controller <url>` | `-c` | context value | Override the controller URL for this invocation. |
| `--token <token>` | `-t` | context value | Override the auth token. |
| `--context <name>` | | active context | Use a named context for this invocation. |
| `--no-color` | | `false` | Disable colored output. |
| `--ascii` | | `false` | ASCII glyphs only (no unicode box drawing). |
| `--verbose` | `-v` | `false` | Print HTTP request/response details. |

All three subcommands call `requireAuth()` first. With no controller URL
configured and no `--controller`/`PREXOR_CONTROLLER` override, they fail with:

```
no controller URL configured -- run 'prexorctl setup'
```

## `template list`

List every template registered on the controller.

```bash
prexorctl template list
prexorctl template list --json
```

Calls `GET /api/v1/templates`. The controller filters out templates whose
name starts with `_` (synthetic module-plugin templates), so they never
appear in CLI output.

Table columns, in order:

| Column | Source field | Rendering |
|---|---|---|
| `NAME` | `name` | code style |
| `HASH` | `hash` | first 8 characters |
| `SIZE` | `sizeBytes` | humanized (`B` / `KB` / `MB`) |
| `DESCRIPTION` | `description` | dim style |

Example:

```bash
prexorctl template list
```

```
Listing templates on controller.example.com

NAME      HASH      SIZE      DESCRIPTION
lobby     a1b2c3d4  4.2 MB    Hub + lobby plugins
survival  9f8e7d6c  812.0 KB  Survival ruleset
─────────────────────────────────────────────
2 templates
```

`SIZE` formatting (`formatBytes` in `cli/cmd/template.go`):

- `0` → `0 B`
- below 1024 → `N B` (no decimals)
- below 1 MiB → `N.N KB` (one decimal, divided by 1024)
- otherwise → `N.N MB` (one decimal, divided by 1024×1024)

### JSON output

`--json` prints the raw template array straight from the controller. Each
object carries the full `TemplateDtoMapper.toDto` shape — the table hides
`platform`, and `hash` is not truncated:

```json
[
  {
    "name": "lobby",
    "description": "Hub + lobby plugins",
    "platform": "paper",
    "hash": "a1b2c3d4e5f6...",
    "sizeBytes": 4404019
  }
]
```

The CLI transparently unwraps the controller's pagination envelope
(`{"data":[...]}`) via `Client.GetList`, so `--json` always yields a bare
array.

## `template versions <name>`

Show the recorded version snapshots for one template, newest first.

```bash
prexorctl template versions lobby
prexorctl template versions lobby --json
```

- Argument: `<name>` — exactly one (`cobra.ExactArgs(1)`). Omitting it or
  passing more than one fails before any request is made.
- Calls `GET /api/v1/templates/{name}/versions`.
- The controller returns `404 NOT_FOUND` (`Template not found: <name>`) when
  the template does not exist; the CLI surfaces that as the request error.

Table columns:

| Column | Source | Rendering |
|---|---|---|
| `#` | computed | descending index, `len(versions) - i` — highest number is newest |
| `HASH` | `hash` | first 8 characters |
| `SIZE` | `sizeBytes` | humanized (same `formatBytes` rules as `list`) |
| `CREATED` | `createdAt` | dim style, printed verbatim |

The `#` column is a display index, not a stored version number. Row `i` (0 =
first returned = newest) is labeled `len(versions) - i`, so the newest
snapshot always carries the largest number.

Example:

```bash
prexorctl template versions lobby
```

```
Versions of template lobby on controller.example.com

#  HASH      SIZE      CREATED
3  a1b2c3d4  4.2 MB    2026-06-07T11:02:14Z
2  77ad19be  4.1 MB    2026-06-05T08:41:00Z
1  0c44ef21  4.0 MB    2026-05-30T19:15:33Z
───────────────────────────────────────────
3 versions
```

### JSON output

`--json` prints the controller's version array verbatim. Each element is the
`TemplateDtoMapper.toVersionDto` shape:

```json
[
  {
    "templateName": "lobby",
    "hash": "a1b2c3d4e5f6...",
    "sizeBytes": 4404019,
    "createdAt": "2026-06-07T11:02:14Z"
  }
]
```

`versions` uses `Client.Get` (not `GetList`), so it does **not** unwrap a
pagination envelope. The controller returns a bare array for this route.

## `template rollback <name>`

Restore a template to a recorded snapshot.

```bash
prexorctl template rollback lobby
```

- Argument: `<name>` — exactly one (`cobra.ExactArgs(1)`).
- Calls `POST /api/v1/templates/{name}/rollback`.
- On success the CLI prints `Template '<name>' rolled back`.

### JSON output

```bash
prexorctl template rollback lobby --json
```

```json
{"status":"rolled_back","template":"lobby"}
```

The CLI synthesizes this object locally from the request argument. It does
not echo the controller's response body, which is shaped differently
(`{"status":"restored","hash":"<hash>"}` from
`TemplateDtoMapper.rollbackResponse`).

### Required request body — CLI/controller mismatch

The controller's `rollbackTemplate` handler **requires** a target `hash` in
the JSON request body and rejects an empty one:

```
400 BAD_REQUEST  Missing 'hash' in request body
```

`prexorctl template rollback` posts a `nil` body (`client.Post(path, nil,
nil)` in `cli/cmd/template.go`), so against the current controller the
command returns that `400`. There is no CLI flag to supply a hash. To roll
back to a specific recorded version today, call the REST endpoint directly
with the target hash from `template versions --json`:

```bash
prexorctl template versions lobby --json     # find the target hash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"hash":"0c44ef21..."}' \
  https://controller.example.com/api/v1/templates/lobby/rollback
```

Controller responses for `POST /api/v1/templates/{name}/rollback`:

| Status | Code | Condition |
|---|---|---|
| `200` | `restored` | Snapshot restored; body `{"status":"restored","hash":"<hash>"}` |
| `400` | `BAD_REQUEST` | `hash` missing or blank in body |
| `404` | `NOT_FOUND` | Template not found, or no recorded version with that hash |
| `422` | `NO_SNAPSHOT` | Version recorded but its snapshot archive is unavailable |

A successful rollback rewrites the template's current files to the snapshot.
Future instance starts in groups referencing the template unpack the
restored version; running instances are unaffected until they restart.

## Scripting

`list --json` and `versions --json` are stable, scriptable surfaces. List
every template name:

```bash
prexorctl template list --json | jq -r '.[].name'
```

Find the second-newest hash of a template (the typical "previous version"
rollback target), then roll back via REST:

```bash
name=lobby
prev=$(prexorctl template versions "$name" --json | jq -r '.[1].hash')
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"hash\":\"$prev\"}" \
  "https://controller.example.com/api/v1/templates/$name/rollback"
```

The list DTO exposes `name`, `description`, `platform`, `hash`, and
`sizeBytes` — there is no timestamp field on a `list` row. Per-version
timestamps come from `template versions --json` (`createdAt`).

## REST routes not exposed by the CLI

`TemplateRoutes.register()` defines the full API surface. `prexorctl` covers
only `list`, `versions`, and `rollback`. The rest are dashboard/REST-only:

| Method + path | Operation |
|---|---|
| `POST /api/v1/templates` | Create template |
| `GET /api/v1/templates/{name}` | Get one template |
| `PATCH /api/v1/templates/{name}` | Update description/platform |
| `DELETE /api/v1/templates/{name}` | Delete template |
| `DELETE /api/v1/templates/{name}/versions/{hash}` | Delete a version snapshot |
| `GET /api/v1/templates/{name}/files` | Browse files (`path`, `version` query params) |
| `POST /api/v1/templates/{name}/files/mkdir` | Create directory |
| `GET /api/v1/templates/{name}/files/content` | Read file content (text only) |
| `PUT /api/v1/templates/{name}/files/content` | Write file content |
| `GET /api/v1/templates/{name}/files/download` | Download a file |
| `POST /api/v1/templates/{name}/files/upload` | Upload file(s); `extract=true` unzips |
| `POST /api/v1/templates/{name}/files/extract` | Extract a ZIP already in the template |
| `POST /api/v1/templates/{name}/files/rename` | Rename a file |
| `DELETE /api/v1/templates/{name}/files` | Delete a file or directory |
| `POST /api/v1/templates/{name}/rehash` | Recompute the content hash |
| `GET`/`PUT /api/v1/templates/{name}/variables` | Read/replace `{{var}}` substitutions |
| `GET /api/v1/templates/{name}/variables/scan` | Scan files for `{{var}}` placeholders |
| `GET /api/v1/templates/{name}/inheritance` | Inheritance chain (`base` → `base-<platform>` → name) |
| `GET /api/v1/templates/{name}/search` | Full-text search (`q`, `maxResults` ≤ 200) |
| `GET /api/v1/templates/{name}/export` | Export as `tar.gz` |
| `POST /api/v1/templates/import` | Import a `tar.gz` (multipart) |

Names must match `[a-z0-9_][a-z0-9_-]*` and be at most 32 characters
(enforced on import). Templates prefixed `_module-plugins-` are synthetic and
cannot be deleted (`400 PROTECTED`).

## Next up

- [Group + instance commands](/reference/cli/group-and-instance/) — how
  groups reference templates with `--template`.
- [Templates concept](/concepts/groups-instances-templates/) — content
  addressing, layering, and the daemon-side cache contract.
