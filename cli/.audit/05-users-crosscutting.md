# prexorctl Audit 05 — User/Role Command Groups + Cross-Cutting Framework

Scope: `cli/cmd/{user,root,help,picker,helpers,completion,completion_dynamic}.go`,
`cli/internal/api/client.go`, `cli/internal/theme/*`, `cli/internal/tui/*`.
READ-ONLY audit. All file:line references are against the tree at audit time.

`roleCmd` lives in `cmd/user.go` (not a separate file). It is added to root in
`cmd/root.go:207` alongside `userCmd:206`.

---

## 1. USER command group

Parent: `userCmd` — `cmd/user.go:13`
- Use: `user`
- Short: "Manage users"
- No flags, no RunE (group only). Subcommands wired in `init()` at `cmd/user.go:329`.

### `user list`
- Source: `cmd/user.go:18`
- Purpose: List all users.
- Usage: `prexorctl user list`
- Flags: none (local). Inherits global persistent flags.
- Behavior: `fetchList("/api/v1/users", nil, &users)` (`helpers.go:17`) → `GET /api/v1/users`.
  `fetchList` calls `requireAuth()` first; unwraps the pagination envelope `{data:[...]}`.
- `--json`: YES. Handled inside `fetchList` (`helpers.go:25`) — prints the raw slice and early-returns before any table rendering.
- Non-JSON output: `tui.SimpleListHeader("Listing users", shortHost(...))` + `tui.PrintTable` with columns `ID, USERNAME, ROLE, CREATED AT` + `tui.ListFooter("users", n)`. ID/createdAt mute-styled, username via `theme.Code`, role as `theme.Pill(PillBrand, role)`.
- Interactive: none.

### `user create`
- Source: `cmd/user.go:46`
- Purpose: Create a user.
- Usage: `prexorctl user create --username <name> [--role <role>]`
- Flags (defined `cmd/user.go:325`):
  - `--username` string, default "", **required** (`MarkFlagRequired`, user.go:327). "Username (required)".
  - `--role` string, default `"VIEWER"`. "Role (ADMIN, OPERATOR, VIEWER)". No shorthand, no completion, no client-side validation.
- Behavior: `requireAuth()` → password collected via **interactive huh password prompt** (`user.go:59`, validates non-empty) → `POST /api/v1/users` with `{username, password, role}`.
- `--json`: partial. On success prints `theme.PrintJSON(result)` (user.go:83). BUT the huh password prompt runs unconditionally before that, even under `--json` / non-TTY (see FINDINGS).
- Non-JSON output: `theme.PrintSuccess("User '<name>' created with role <role>")`.
- Interactive: ALWAYS prompts for password (no `--password` / stdin flag). No `interactive()` TTY guard.

### `user delete <username>`
- Source: `cmd/user.go:91`
- Purpose: Delete a user.
- Usage: `prexorctl user delete <username>`
- Args: `cobra.ExactArgs(1)`.
- Flags: none.
- Behavior: `requireAuth()` → **interactive huh confirm** (user.go:102) → `DELETE /api/v1/users/<username>`.
- `--json`: NONE. No JSON branch; always prints `theme.PrintSuccess` plain text. The confirm prompt runs even under `--json`.
- Interactive: ALWAYS shows a yes/no confirm. No `--yes`/`--force` bypass. In a non-TTY this huh confirm fails (no picker `interactive()` guard used here).
- Completion: none (no `completeUserNames`); falls to `NoFileComp` via `suppressFileCompletion`.

---

## 2. ROLE command group

Parent: `roleCmd` — `cmd/user.go:124`
- Use: `role`; Short: "Manage roles". Group only. Subcommands wired `cmd/user.go:338`.

### `role list`
- Source: `cmd/user.go:129`
- Purpose: List roles.
- Usage: `prexorctl role list`
- Flags: none.
- Behavior: `fetchList("/api/v1/roles", nil, &roles)` → `GET /api/v1/roles`.
- `--json`: YES (via `fetchList`).
- Non-JSON output: header + table columns `NAME, BUILT-IN, PERMISSIONS` + footer. `builtIn` bool → "yes"/"no". **PERMISSIONS cell is the full comma-joined permission list** (user.go:143-149) — not a count.
- Interactive: none.

### `role show <name>`
- Source: `cmd/user.go:169`
- Purpose: Show a single role with its full permission list.
- Usage: `prexorctl role show <name>`
- Args: `ExactArgs(1)`.
- Flags: none.
- Behavior: `requireAuth()` → `GET /api/v1/roles/<name>`.
- `--json`: YES (user.go:182).
- Non-JSON output: bespoke text block — `theme.Code(name)`, optional dim "(built-in role…)" line, then `Permissions (N):` and a bulleted list. Does NOT use a card/table component (differs from list rendering).
- Interactive: none.

### `role create`
- Source: `cmd/user.go:201`
- Purpose: Create a custom role with a permission set.
- Usage: `prexorctl role create --name <name> [--permissions a,b,c]`
- Long: documents that ADMIN is the full-permission reference; empty list allowed for a stub role.
- Flags (defined `cmd/user.go:331`):
  - `--name` string, default "", **required** (user.go:333).
  - `--permissions` string, default "". "Comma-separated permission list (e.g. groups.view,instances.start)". Parsed by `splitCSV` (user.go:310) which drops empty/whitespace segments. No completion.
- Behavior: `requireAuth()` → `POST /api/v1/roles` with `{name, permissions:[...]}`.
- `--json`: YES (user.go:231).
- Non-JSON: `PrintSuccess("Role '<name>' created with N permission(s)")`.

### `role update <name>`
- Source: `cmd/user.go:239`
- Purpose: Replace the permission set on an existing custom role.
- Usage: `prexorctl role update <name> --permissions a,b,c`
- Long: notes built-in roles reject with 422.
- Args: `ExactArgs(1)`.
- Flags (defined `cmd/user.go:335`):
  - `--permissions` string, default "", **required** (user.go:336). "…(replaces the existing set)". `splitCSV`. No completion.
- Behavior: `requireAuth()` → `PATCH /api/v1/roles/<name>` with `{permissions:[...]}`.
- `--json`: YES (user.go:265).
- Non-JSON: `PrintSuccess("Role '<name>' updated to N permission(s)")`.

### `role delete <name>`
- Source: `cmd/user.go:273`
- Purpose: Delete a custom role.
- Usage: `prexorctl role delete <name>`
- Long: built-in roles can't be deleted; in-use roles reject with 422 — and references `prexorctl user list --role <name>` (a flag that does not exist, see FINDINGS).
- Args: `ExactArgs(1)`.
- Flags: none.
- Behavior: `requireAuth()` → **interactive huh confirm** (user.go:287) → `DELETE /api/v1/roles/<name>`.
- `--json`: NONE. Always plain `PrintSuccess`. Confirm runs even under `--json`/non-TTY. No `--yes`/`--force`.
- Completion: none (no `completeRoleNames`).

**Commands documented: 10** (2 group parents + 8 subcommands).

---

## 3. CROSS-CUTTING FRAMEWORK

### 3.1 Global persistent flags (`cmd/root.go:177-183`)
| Flag | Short | Type | Default | Effect |
|------|-------|------|---------|--------|
| `--json` | `-j` | bool | false | JSON output; also forces no-color (`root.go:58`) and disables pickers (`picker.go:23`). |
| `--controller` | `-c` | string | "" | Override controller URL (beats env + context). |
| `--token` | `-t` | string | "" | Override auth token. |
| `--context` | — | string | "" | Override active context for this invocation. |
| `--no-color` | — | bool | false | Disable ANSI color. |
| `--ascii` | — | bool | false | ASCII glyphs only (no unicode box/sparkline/pills). |
| `--verbose` | `-v` | bool | false | Print HTTP request/response trace lines. |

### 3.2 PREXOR_* / env vars
- `PREXOR_OUTPUT=json` → sets `flagJSON=true` at init (`root.go:186`).
- `PREXOR_CONTROLLER` → controller URL, flag > env > context (`config.go:166`).
- `PREXOR_TOKEN` → token, flag > env > context (`config.go:180`).
- `PREXOR_CONTEXT` → selected context name (`config.go:155`).
- `PREXOR_NO_BROWSER` and `CI` → suppress browser open in setup (`setup.go:241`).
- `NO_COLOR` → forces no-color in `theme.Init` (`palette.go:60`), independent of `--no-color`.

Resolution precedence (controller/token/context): **flag > PREXOR_* env > stored context**.

### 3.3 Pre-link gate (`root.go:30-41`, applied `root.go:72-81`)
`commandsAllowedBeforeLink`: `setup, login, logout, version, help, completion, context, cluster`.
If no context controller AND `Resolve(flagController,flagContext)==""`, any other top-level
command returns `"no cluster connected — run 'prexorctl setup'…or 'prexorctl login'…"`.
- `topLevelName` (`root.go:102`) walks to the immediate child of root, so `node list` keys on `node`.
- `local-only` opt-out: `isLocalOnly` (`root.go:91`) walks the command + parents for
  `Annotations["local-only"]=="true"`; such commands (e.g. `module new`/`scaffold`) skip the gate.
- Gate runs in `PersistentPreRunE`, which is **skipped for `--help`** (help wires its own theme init, `help.go:30`).

### 3.4 Exit codes
- `ExitCodeError{Code,Message}` (`root.go:124`) — typed error mapped to `os.Exit(Code)` in `Execute()` (`root.go:164`).
- `api.APIError.ExitCode()` (`client.go:109`): 401→`ExitAuthError(2)`, 403→`ExitForbidden(3)`, 404→`ExitNotFound(4)`, else→`ExitError(1)`.
- Constants (`client.go:65`): `ExitSuccess 0, ExitError 1, ExitAuthError 2, ExitForbidden 3, ExitNotFound 4, ExitConnError 5`.
- `Execute()` (`root.go:158`): prints `theme.PrintError`, then exits with `ExitCodeError.Code`, else `APIError.ExitCode()`, else 1.
- Documented convention in root.go: 0 success, 1 generic, 2 = "validation/diagnostic warnings" (module doctor) — **collides with ExitAuthError=2** (see FINDINGS).

### 3.5 Shell completion
- `completion` command (`completion.go`): generates bash/zsh/fish/powershell. Uses
  `GenBashCompletion` (v1), `GenZshCompletion`, `GenFishCompletion(...,true)`, `GenPowerShellCompletionWithDesc`.
- `suppressFileCompletion` (`root.go:146`): recursively installs a `NoFileComp` `ValidArgsFunction`
  on every leaf with no existing completion that isn't in `fileCompletionCommands` (`upload/install/doctor`) —
  stops bash from listing cwd files for no-arg commands.
- Dynamic completions (`completion_dynamic.go`): `completionClient()` builds an auth client with a
  3s timeout (`completionTimeout`), returns nil (→ no suggestions) when unconfigured; every helper
  fails silently. Candidates use `value\tdescription` via `withDesc`.
  Registered (`init`, completion_dynamic.go:242): groups (`group info/update/delete`, maintenance name+on/off),
  nodes (`node info/drain/undrain`), instances (`instance info/stop/exec/console`, `start`→groups),
  `crash info`, contexts (`context use/remove`, local-only), catalog (`catalog update/recommend/remove`).
  **No user/role/token completions.**

### 3.6 TUI pickers / arg-pickers (`picker.go`)
- `interactive()` (picker.go:22): false if `--json`; else requires stdin AND stdout both TTY.
- `resolveArg(args, usage, pick)` (picker.go:45): returns `args[0]`; else if interactive runs `pick()`;
  else returns the usage string as a plain error (fail-fast for scripts).
- `pickOne` (picker.go:30) → styled `huh.NewSelect` with `tui.HuhTheme()`.
- Pickers exist for: group, node, instance, crash, context, catalog platform/version.
  **No `pickUser`/`pickRole`.** User/role commands use raw `ExactArgs(1)` and do not integrate the picker pattern.

### 3.7 Theme / output system (`internal/theme/*`)
- `theme.Init(accent, noColor, ascii)` (`palette.go:59`): sets `NoColor` (flag||`NO_COLOR`), `ASCII`;
  if NoColor sets lipgloss profile to `NoTTY`; swaps Brand/BrandDp per accent.
  Called once in `PersistentPreRunE` (`root.go:62`); `noColor = flagNoColor || flagJSON || non-tty-stdout`.
- Accents (`palette.go:18`): `purple` (default), `cyan`, `green`, `amber`; from `cfg.Accent`.
- Text helpers (`text.go`): `Heading` (bold brand), `Subtitle`/`Hint` (dim/mute), `Code` (brand),
  `Path` (blue), `Number` (cyan), diff helpers, `HRule`.
  `PrintSuccess`→stdout (green Tick), `PrintError`→stderr (red Cross), `PrintWarn`→stderr (amber Warn).
- Glyphs (`glyphs.go`): every glyph has an ASCII fallback gated on `ASCII`; box-drawing sharp/round,
  spinner, sparkline ramp, progress chars.
- Pills (`pill.go`): colored bg+fg; in NoColor OR ASCII degrade to `[TEXT]`. `StatusPill` maps backend
  status vocab to green/amber/red/mute.
- Tables (`tui/table.go`): sharp `NormalBorder` (ASCII border swap), dim bold header, zebra rows.
- List headers/footers (`tui/list_header.go`): `SimpleListHeader`, `ListHeader` (filter/sort variant), `ListFooter`.
- JSON (`theme/json.go`): `PrintJSON` is the single `--json`/`PREXOR_OUTPUT=json` path — indented JSON to stdout.
- `tui/huh_theme.go`: hard-codes brand/cyan/dim/mute/red hex (lipgloss **v1**, vs v2 elsewhere);
  honors `theme.NoColor` by blanking colors but does **not** honor the selected accent (always purple-ish).

### 3.8 Help template (`help.go`)
- `installHelp(root)` sets `SetHelpFunc`/`SetUsageFunc` → `printStyledHelp`.
- `ensureThemeInit` (help.go:30): re-inits theme because help bypasses PersistentPreRunE; **always uses `AccentPurple`** and ignores `--json`/`cfg.Accent`.
- `printStyledHelp` renders sections: brand heading + subtitle, USAGE, ALIASES, EXAMPLES, COMMANDS (name-padded), FLAGS (local), GLOBAL FLAGS (inherited), trailing hint. `indentFlags` recolors cobra's flag block.

### 3.9 API client (`internal/api/client.go`)
- Retry: only idempotent GET/HEAD (`isIdempotent`), 3 attempts, exp backoff+jitter, retries conn errors + 408/429/500/502/503/504. POST/PATCH/DELETE never retried.
- Default HTTP timeout 30s (`New`, client.go:88).
- `APIError{StatusCode,Code,Message}` decoded from error-response envelope; `Error()` → `"<message> (HTTP <code>)"`.
- `--verbose`: prints `→`/`←` trace lines via `fmt.Printf` to **stdout** (client.go:166,185,324,399).
- `GetList` transparently unwraps `{data:[...]}` pagination envelope or bare arrays.

---

## 4. FINDINGS

- **`user delete` / `role delete` ignore `--json` and always pop an interactive huh confirm** (`user.go:102`, `user.go:287`). No `--yes`/`--force`/`--confirm` bypass and no `interactive()`/TTY guard, so in scripts/pipes (the exact `--json` use case) the confirm fails or blocks. These are the only destructive commands and they are unscriptable.
- **`user create` always prompts for the password interactively** (`user.go:59`) with no `--password`/stdin flag and no TTY guard — runs even under `--json` and in non-TTY. Automated user creation is impossible; output is half-JSON, half-prompt.
- **`role delete` Long text references `prexorctl user list --role <name>`** (`user.go:279`) but `user list` (`user.go:18`) has **no `--role` flag** and no filtering at all. Documented remediation path does not exist.
- **No `user show` and no `user update`** — asymmetric with `role` (which has show + update). Cannot inspect a single user or change a user's role/password from the CLI; only create/delete.
- **No dynamic completion for users, roles, or token names.** `user delete <TAB>`, `role show/update/delete <TAB>` get only `NoFileComp` (suppressFileCompletion). `--role` (create) and `--permissions` (create/update) flags have no completion either, though `ADMIN/OPERATOR/VIEWER` is a closed set and permissions are enumerable from `role show ADMIN`.
- **No `pickUser`/`pickRole`** — user/role commands don't use the `resolveArg`/`pickOne` interactive picker pattern that group/node/instance/crash/context all use (`picker.go`). Inconsistent UX: `user delete` with no arg just errors instead of offering a selector.
- **Bash dynamic completions are dead.** `completion bash` uses `GenBashCompletion` (v1, `completion.go:23`); v1 bash completion does not call back into the binary, so the `ValidArgsFunction` resource completions (groups/nodes/instances/etc.) only work in zsh/fish/pwsh, never bash. Needs `GenBashCompletionV2`.
- **Exit-code 2 is overloaded.** `ExitAuthError = 2` for HTTP 401 (`client.go:68`, `client.go:111`) collides with root.go's documented convention that exit 2 = "validation/diagnostic failure with warnings" (module doctor, `root.go:121`). A 401 and a doctor-warning are indistinguishable by exit code.
- **`ExitConnError = 5` is never used.** Connection failures return a plain `fmt.Errorf("connection error: …")` (`client.go:174,329,404,465`) which is not an `APIError`, so `Execute()` maps them to exit 1. The dedicated connection exit code is dead.
- **`--verbose` writes the HTTP trace to stdout, not stderr** (`client.go:166,185,324,399`). Combined with `--json` (or any piped output) this **corrupts the stdout payload** with `→ GET …` / `← 200 …` lines, breaking JSON parsing.
- **Errors are never emitted as JSON under `--json`.** All errors go through `theme.PrintError` as plain text on stderr (`root.go:162`); there is no JSON error envelope. A `--json` consumer that fails gets empty stdout and unparseable stderr.
- **`role list` PERMISSIONS column dumps the entire comma-joined permission set into one cell** (`user.go:143-149`). For ADMIN (~41 perms) this explodes the table width and is unreadable; should show a count (as `role show` does) or truncate.
- **`role show` non-JSON output is bespoke text**, not a card/table component (`user.go:185-196`), inconsistent with every other single-resource view and with `role list`.
- **Help output ignores the user's accent and `--json`.** `ensureThemeInit` (`help.go:35`) hard-codes `AccentPurple` and only checks `flagNoColor`+non-tty, so `--help` never reflects `cfg.Accent` and stays colored under `--json`.
- **`huh_theme.go` hard-codes hex colors and uses lipgloss v1** (the rest of the CLI is v2). It honors `NoColor` but not the selected accent, so interactive prompts/pickers are always purple regardless of `cfg.Accent` — a theming split-brain.
- **Mixed input model inside `user create`**: `--username` is a required flag, `--role` is a flag, but `password` is an interactive prompt (`user.go:55-72`). No single consistent path (all-flags or all-prompts).
- **`user create --role` has no client-side validation or completion** (`user.go:326`); a typo'd role only surfaces as a server error.
