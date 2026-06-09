package cmd

import (
	"fmt"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/api"
	"github.com/prexorcloud/prexorctl/internal/theme"
)

// fetchList does the auth + GET-list dance shared by every `<entity> list`
// command. Returns the client (kept for follow-up calls) and a flag indicating
// the caller already emitted JSON output and should return immediately.
//
// Callers still own the table-render path so per-entity headers/footers/stats
// stay grep-able.
func fetchList[T any](path string, params map[string]string, items *[]T) (client *api.Client, emittedJSON bool, err error) {
	client, err = requireAuth()
	if err != nil {
		return nil, false, err
	}
	if err = client.GetList(path, params, items); err != nil {
		return nil, false, err
	}
	if flagJSON {
		return client, true, theme.PrintJSON(*items)
	}
	return client, false, nil
}

// fetchOne does the auth + GET-single dance shared by `<entity> info`/`get`
// commands. Mirrors {@link fetchList}: returns whether JSON was already emitted
// so the caller can early-return.
func fetchOne[T any](path string, item *T) (client *api.Client, emittedJSON bool, err error) {
	client, err = requireAuth()
	if err != nil {
		return nil, false, err
	}
	if err = client.Get(path, item); err != nil {
		return nil, false, err
	}
	if flagJSON {
		return client, true, theme.PrintJSON(*item)
	}
	return client, false, nil
}

// str returns the string representation of a map value, or "-" if missing.
func str(m map[string]any, key string) string {
	if v, ok := m[key]; ok {
		return fmt.Sprintf("%v", v)
	}
	return "-"
}

// num returns a float64 map value, or 0 if missing or wrong type.
func num(m map[string]any, key string) float64 {
	if v, ok := m[key].(float64); ok {
		return v
	}
	return 0
}

// strOrDash returns "-" for empty strings, otherwise s.
func strOrDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

// kvLine renders a single "key   value" row with the key dim-muted and padded.
func kvLine(k, v string) string {
	return theme.StyleMute().Render(padRight(k, 14)) + " " + v
}

// kvBlock renders pairs as a multi-line key/value body for cards. Pairs must
// be even-length; the panic guards an internal invariant on call sites.
func kvBlock(pairs ...string) string {
	if len(pairs)%2 != 0 {
		panic("kvBlock requires an even number of arguments")
	}
	lines := make([]string, 0, len(pairs)/2)
	for i := 0; i < len(pairs); i += 2 {
		lines = append(lines, kvLine(pairs[i], pairs[i+1]))
	}
	return strings.Join(lines, "\n")
}
