---
title: Users, roles + shares
description: prexorctl user, role, and share — manage operator accounts, custom permission sets, and audit or revoke paste shares.
---

PrexorCloud's RBAC has two surfaces: **users** (humans, with a
username + password + role) and **roles** (named permission sets, built-in
or custom). `prexorctl share` is the operator-facing reverse of the
`--share` flag carried by `crash`, `logs`, `diagnostics`, and
`instance console`: it lists, inspects, and revokes the paste shares those
flags created.

This page documents `prexorctl user`, `prexorctl role`, and
`prexorctl share`. Tokens — daemon join tokens and personal API tokens —
are issued by `prexorctl token`; see
[Setup + Auth](/reference/cli/setup-and-auth/#prexorctl-token).

Every command authenticates against the controller resolved from
`--controller`/`--context` (or the stored login). The global `-j`/`--json`
flag switches any command to raw JSON output.

## `prexorctl user`

Manage operator accounts. Backed by the controller's `/api/v1/users`
endpoints.

### `user list`

```bash
prexorctl user list
prexorctl user list --json
```

Lists every user. Columns: `ID`, `USERNAME`, `ROLE`, `CREATED AT`. The
command takes no flags of its own; with `-j`/`--json` it emits the raw user
array from `GET /api/v1/users`.

```text
Listing users · controller.example.com

  ID        USERNAME   ROLE       CREATED AT
  3f2a…     admin      ADMIN      2026-05-05T08:12:04Z
  9c11…     alice      OPERATOR   2026-06-01T14:33:10Z

✓ 2 users
```

### `user create`

```bash
prexorctl user create --username alice --role OPERATOR
```

Creates a user via `POST /api/v1/users`. The CLI prompts for the password
over a TTY (echo hidden); passwords are never accepted as flags, keeping
them out of shell history. An empty password is rejected at the prompt.

Flags:

| Flag | Required | Default | Description |
|---|---|---|---|
| `--username <name>` | yes | — | Login name. |
| `--role <role>` | no | `VIEWER` | Role to assign: `ADMIN`, `OPERATOR`, `VIEWER`, or any custom role name. |

On success it prints `User '<username>' created with role <role>`. With
`-j`/`--json` it prints the created user object returned by the controller.

```text
✓ User 'alice' created with role OPERATOR
```

### `user delete`

```bash
prexorctl user delete <username>
```

Deletes a user via `DELETE /api/v1/users/<username>`. Takes exactly one
positional argument. Prompts for confirmation (`Delete user '<username>'?`);
answering no aborts and prints `Cancelled.`. On success it prints
`User '<username>' deleted`.

## `prexorctl role`

Manage roles. Built-in roles (`ADMIN`, `OPERATOR`, `VIEWER`) ship with the
controller and cannot be modified or deleted. Custom roles are created and
edited here, backed by `/api/v1/roles`.

Permissions are dotted identifiers such as `groups.view`,
`instances.start`, `nodes.view`. The complete set granted to the admin role
is the canonical reference — run `prexorctl role show ADMIN` to enumerate
it.

### `role list`

```bash
prexorctl role list
prexorctl role list --json
```

Lists every role from `GET /api/v1/roles`. Columns: `NAME`, `BUILT-IN`
(`yes`/`no`), `PERMISSIONS` (comma-separated). With `-j`/`--json` it emits
the raw role array.

```text
Listing roles · controller.example.com

  NAME             BUILT-IN   PERMISSIONS
  ADMIN            yes        *
  OPERATOR         yes        groups.view, instances.start, instances.stop, …
  VIEWER           yes        groups.view, instances.view, nodes.view
  viewer-readonly  no         groups.view, instances.view, nodes.view

✓ 4 roles
```

### `role show`

```bash
prexorctl role show <name>
prexorctl role show ADMIN --json
```

Shows one role via `GET /api/v1/roles/<name>`, with its full permission
list. Takes exactly one positional argument (the role name). Built-in roles
are annotated `(built-in role — cannot be modified or deleted)`. With
`-j`/`--json` it prints the raw role object.

```text
ADMIN
(built-in role — cannot be modified or deleted)

Permissions (1):
  - *
```

### `role create`

```bash
prexorctl role create --name viewer-readonly \
  --permissions groups.view,instances.view,nodes.view
```

Creates a custom role via `POST /api/v1/roles`. Permissions are passed as a
single comma-separated string; whitespace around each entry is trimmed and
empty segments (trailing or doubled commas) are dropped before the request
is sent. Pass an empty `--permissions` value to create a stub role and fill
it in later with `role update`.

Flags:

| Flag | Required | Default | Description |
|---|---|---|---|
| `--name <name>` | yes | — | Role name. |
| `--permissions <csv>` | no | `""` | Comma-separated permission list, e.g. `groups.view,instances.start`. |

On success it prints
`Role '<name>' created with <n> permission(s)`. With `-j`/`--json` it prints
the created role object.

### `role update`

```bash
prexorctl role update viewer-readonly \
  --permissions groups.view,instances.view,nodes.view,crashes.view
```

Replaces the permission set on an existing custom role via
`PATCH /api/v1/roles/<name>`. Takes exactly one positional argument (the
role name). The `--permissions` value **replaces** the existing set; it is
not additive. The same trim/drop-empty normalization as `role create`
applies.

Built-in roles cannot be modified — the controller rejects the request with
`422`. Derive a custom role from a built-in one with `role create` instead.

Flags:

| Flag | Required | Default | Description |
|---|---|---|---|
| `--permissions <csv>` | yes | — | Comma-separated permission list that replaces the current set. |

On success it prints `Role '<name>' updated to <n> permission(s)`.

### `role delete`

```bash
prexorctl role delete viewer-readonly
```

Deletes a custom role via `DELETE /api/v1/roles/<name>`. Takes exactly one
positional argument. Prompts for confirmation
(`Delete role '<name>'?`); answering no prints `Cancelled.`.

Built-in roles cannot be deleted. If any user still holds the role, the
controller rejects the request with `422` — reassign those users first. On
success it prints `Role '<name>' deleted`.

## `prexorctl share`

List, view, and revoke persisted paste shares. Alias: `shares`. Backed by
`/api/v1/shares`. This tree is the audit side of sharing; the creation side
is the `--share` flag on `crash info`, `logs controller`, `logs daemon`,
`diagnostics bundle`, and `instance console`.

A share record carries: `id`, `kind`, `resourceId`, `url`, `rawUrl`,
`expiresAt`, `burnAfterRead`, `isPrivate`, `sizeBytes`, `sharedByUser`,
`sharedAt`, `revokedAt`, and `revocable` (true when a delete token was
captured at creation time).

### `share list`

```bash
prexorctl share list
prexorctl share list --kind CRASH --active-only --limit 20
prexorctl share list --json
```

Lists recent shares (newest first) via `GET /api/v1/shares`. Columns: `ID`
(first 8 chars), `KIND`, `WHEN` (relative, e.g. `3h ago`), `BY`, `BYTES`,
`URL`, `STATUS` (`active`, `revoked`, or `non-revocable`). With no results
it prints `No shares found.`. With `-j`/`--json` it emits the full paginated
page object (`data`, `total`, `page`, `pageSize`).

Flags:

| Flag | Default | Description |
|---|---|---|
| `--kind <kind>` | — | Filter by surface. Case-insensitive; one of `CRASH`, `CONTROLLER_LOGS`, `DAEMON_LOGS`, `DIAGNOSTICS`, `INSTANCE_CONSOLE`. |
| `--active-only` | `false` | Hide revoked entries. |
| `--limit <n>` | `50` | Max rows. The server caps the page size at `200`. |

```text
ID        KIND    WHEN     BY     BYTES   URL                          STATUS
a1b2c3d4  CRASH   3h ago   alice  20481   https://pste.example/abc123  active
e5f6a7b8  CRASH   2d ago   admin  10240   https://pste.example/def456  revoked
```

### `share view`

```bash
prexorctl share view <id>
prexorctl share view a1b2c3d4 --json
```

Shows one share via `GET /api/v1/shares/<id>`. Takes exactly one positional
argument (the full id; the truncated id shown by `share list` is for display
only). Prints kind, resource id, both URLs, who shared it and when, size,
and expiry. Burn-after-read shares are flagged
`burn-after-read: enabled (single-read link)`. The footer states whether the
share is revocable and how:

- revoked already → `revoked at: <timestamp>`
- revocable → `revocable via: prexorctl share revoke <id>`
- otherwise → `non-revocable (no delete token captured)`

With `-j`/`--json` it prints the raw share record.

### `share revoke`

```bash
prexorctl share revoke <id>
prexorctl share revoke a1b2c3d4 --yes
```

Revokes a share via `POST /api/v1/shares/<id>/revoke`: the controller calls
the paste service's delete endpoint and marks the record revoked. Takes
exactly one positional argument.

Without `-y`/`--yes` it first fetches the record, prints
`About to revoke <kind> share <id> shared by <user> at <when>`, and reads a
confirmation from stdin — you must type `yes` exactly (case-insensitive) or
the command aborts with `revoke aborted`.

Flags:

| Flag | Short | Default | Description |
|---|---|---|---|
| `--yes` | `-y` | `false` | Skip the confirmation prompt. |

On success it prints `Revoked share <id> (<kind>)` and the revocation
timestamp. With `-j`/`--json` it prints the updated record.

#### Share error messages

Share commands translate the controller's structured errors:

| Server condition | CLI message |
|---|---|
| `SHARE_DISABLED` / `409` | `sharing is not configured on this controller (share.enabled=false)` |
| `PASTE_UPSTREAM_ERROR` / `502` | `paste service unreachable: <error>` |

## Scripting examples

Assert that no user other than `admin` holds the `ADMIN` role:

```bash
EXTRA_ADMINS=$(prexorctl user list --json \
  | jq -r '.[] | select(.role == "ADMIN" and .username != "admin") | .username')
[ -z "$EXTRA_ADMINS" ] || { echo "Unexpected admins: $EXTRA_ADMINS"; exit 1; }
```

Clone the built-in `VIEWER` role into an editable custom role:

```bash
PERMS=$(prexorctl role show VIEWER --json | jq -r '.permissions | join(",")')
prexorctl role create --name viewer-custom --permissions "$PERMS"
```

Revoke every active crash share except the most recent, non-interactively:

```bash
prexorctl share list --kind CRASH --active-only --json \
  | jq -r '.data[1:][].id' \
  | while read -r id; do prexorctl share revoke "$id" --yes; done
```

## Next up

- [Setup + Auth](/reference/cli/setup-and-auth/) — `prexorctl login` and
  the `token` commands.
- [Users + RBAC concept](/concepts/security/) — the permission table and
  what each built-in role grants.
