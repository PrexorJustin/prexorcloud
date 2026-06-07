package tui

import (
	"fmt"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
)

// ListHeader renders the standard "Listing X in cluster Y • filter: Z • sort: W"
// header line used by every list command. Empty filter/sort are shown as "(none)"
// and "name" respectively to match the design.
func ListHeader(verb, cluster, filter, sortBy string) string {
	if filter == "" {
		filter = "(none)"
	}
	if sortBy == "" {
		sortBy = "name"
	}
	return fmt.Sprintf("%s %s  %s filter: %s  %s sort: %s",
		theme.Heading(verb),
		theme.Code(cluster),
		theme.Bullet(),
		theme.StyleDim().Render(filter),
		theme.Bullet(),
		theme.StyleDim().Render(sortBy),
	)
}

// ListFooter renders a count summary line. label is the entity ("nodes", "groups");
// segments are pre-formatted "N up", "N draining" pieces. They're joined with bullets.
func ListFooter(label string, total int, segments ...string) string {
	pieces := []string{fmt.Sprintf("%d %s", total, label)}
	pieces = append(pieces, segments...)
	return theme.Hint(strings.Join(pieces, "  "+theme.Bullet()+"  "))
}

// SimpleListHeader is a stripped-down header for commands that don't have
// filter/sort flags (most of them). Just "Listing X • <count> in <cluster>".
func SimpleListHeader(verb, cluster string) string {
	if cluster == "" {
		return theme.Heading(verb)
	}
	return fmt.Sprintf("%s in cluster %s",
		theme.Heading(verb),
		theme.Code(cluster),
	)
}
