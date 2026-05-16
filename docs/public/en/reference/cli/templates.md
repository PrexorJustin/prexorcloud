---
title: Templates
description: prexorctl template — list, version history, and rollback for the layered server templates a group composes at instance start.
---

A **template** is a tar.gz of files (configs, plugins, world data) that
the daemon unpacks into an instance's working directory before it boots.
Groups reference templates by name and the controller stores every
upload as a content-addressed version.

## What you'll learn

- How to inspect templates installed on the controller.
- How to view the version history of a template.
- How to roll a template back to its previous version.

## `prexorctl template`

### `template list`

```bash
prexorctl template list
prexorctl template list --json
```

Lists name, current version's content hash (first 8 chars), size, and
description. Templates are uploaded through the dashboard or REST API;
there is no CLI upload command yet.

### `template versions`

```bash
prexorctl template versions <name>
```

Shows the version history for one template — newest first, with the
content hash, size, and creation timestamp of each version. The
controller retains all versions until you explicitly prune them.

### `template rollback`

```bash
prexorctl template rollback <name>
```

Marks the previous version as the current one. Future instance starts
in groups that reference this template will unpack the rolled-back
version; running instances are unaffected until they restart.

Use `--json` to get a structured `{"status":"rolled_back","template":"<name>"}`
response for CI gating.

## Scripting example

Roll back every template touched in the last hour, then bounce all
running instances in their consumer groups:

```bash
prexorctl template list --json \
  | jq -r '.[] | select(.lastUpdatedAt | fromdateiso8601 > (now - 3600)) | .name' \
  | while read tpl; do
      prexorctl template rollback "$tpl"
    done
```

## Next up

- [Group + Instance commands](/reference/cli/group-and-instance/) — how
  groups reference templates with `--template`.
- [Templates concept](/concepts/groups-instances-templates/) — content-addressing,
  layering, and the daemon-side cache contract.
