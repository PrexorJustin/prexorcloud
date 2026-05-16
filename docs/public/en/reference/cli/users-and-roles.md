---
title: Users + Roles
description: prexorctl user, role, and token — managing operator accounts, listing built-in roles, and issuing API/join tokens.
---

PrexorCloud's RBAC has two surfaces: **users** (humans, with
username + password) and **roles** (named permission sets). Tokens are
issued by `prexorctl token` and covered on
[Setup + Auth](/reference/cli/setup-and-auth/); this page focuses on
people management.

## What you'll learn

- How to list and create users and assign them a role.
- How to inspect the built-in role set and its permissions.

## `prexorctl user`

### `user list`

```bash
prexorctl user list
prexorctl user list --json
```

Lists user id, username, role, and creation timestamp.

### `user create`

```bash
prexorctl user create --username alice --role OPERATOR
```

Flags:

- `--username` *(required)* — the login name.
- `--role` — `ADMIN`, `OPERATOR`, or `VIEWER`. Default `VIEWER`.

The CLI prompts for a password — passwords are not accepted via flags
to keep them out of shell history.

### `user delete`

```bash
prexorctl user delete <username>
```

Prompts for confirmation before removing the user. Sessions tied to
the deleted user are invalidated server-side.

## `prexorctl role`

### `role list`

```bash
prexorctl role list
prexorctl role list --json
```

Lists role name, whether it's built-in (`ADMIN`, `OPERATOR`, `VIEWER`
ship with the controller), and its full permission list. Custom
roles created through the REST API also show up here.

## Tokens

Operator-facing tokens — both daemon **join tokens** and personal API
tokens — are managed by `prexorctl token`. See
[Setup + Auth → token](/reference/cli/setup-and-auth/#prexorctl-token)
for the full subcommand list.

## Scripting example

Assert that no user other than `admin` has the `ADMIN` role:

```bash
EXTRA_ADMINS=$(prexorctl user list --json \
  | jq -r '.[] | select(.role == "ADMIN" and .username != "admin") | .username')
[ -z "$EXTRA_ADMINS" ] || { echo "Unexpected admins: $EXTRA_ADMINS"; exit 1; }
```

## Next up

- [Users + RBAC concept](/concepts/security/) — the
  permission table and what each built-in role grants.
- [Setup + Auth](/reference/cli/setup-and-auth/) — `prexorctl login`
  and the token commands.
