package cmd

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/theme"
	"github.com/spf13/cobra"
)

// installHelp wires custom help/usage rendering on the root command and all
// subcommands so --help and `help <cmd>` match the design language. Cobra
// skips PersistentPreRunE for help, so we initialize theme here on first call.
func installHelp(root *cobra.Command) {
	root.SetHelpFunc(func(cmd *cobra.Command, _ []string) {
		ensureThemeInit()
		printStyledHelp(cmd, cmd.OutOrStdout())
	})
	root.SetUsageFunc(func(cmd *cobra.Command) error {
		ensureThemeInit()
		printStyledHelp(cmd, cmd.OutOrStderr())
		return nil
	})
}

// ensureThemeInit handles the case where --help bypasses PersistentPreRunE.
// Idempotent — Init resets internal state safely.
func ensureThemeInit() {
	noColor := flagNoColor
	if fi, err := os.Stdout.Stat(); err == nil && (fi.Mode()&os.ModeCharDevice) == 0 {
		noColor = true
	}
	theme.Init(theme.AccentPurple, noColor, flagASCII)
}

func printStyledHelp(cmd *cobra.Command, w io.Writer) {
	short := cmd.Short
	if cmd.Long != "" {
		short = cmd.Long
	}

	fmt.Fprintln(w)
	if cmd.HasParent() {
		fmt.Fprintf(w, "%s %s\n", theme.BrandGlyph(), theme.Heading(cmd.CommandPath()))
	} else {
		fmt.Fprintln(w, theme.Heading("▲ prexorctl  •  PrexorCloud CLI"))
	}
	if short != "" {
		fmt.Fprintln(w, theme.Subtitle(short))
	}
	fmt.Fprintln(w)

	// USAGE
	fmt.Fprintln(w, theme.StyleDim().Bold(true).Render("USAGE"))
	if cmd.Runnable() {
		fmt.Fprintf(w, "  %s\n", theme.Code(cmd.UseLine()))
	}
	if cmd.HasAvailableSubCommands() {
		fmt.Fprintf(w, "  %s\n", theme.Code(cmd.CommandPath()+" [command]"))
	}
	fmt.Fprintln(w)

	// ALIASES
	if len(cmd.Aliases) > 0 {
		fmt.Fprintln(w, theme.StyleDim().Bold(true).Render("ALIASES"))
		fmt.Fprintf(w, "  %s\n\n", theme.StyleDim().Render(strings.Join(append([]string{cmd.Name()}, cmd.Aliases...), ", ")))
	}

	// EXAMPLES
	if cmd.Example != "" {
		fmt.Fprintln(w, theme.StyleDim().Bold(true).Render("EXAMPLES"))
		for _, line := range strings.Split(strings.TrimSpace(cmd.Example), "\n") {
			fmt.Fprintf(w, "  %s\n", theme.StyleDim().Render(line))
		}
		fmt.Fprintln(w)
	}

	// SUBCOMMANDS — grouped if cobra groups are present, else flat.
	if cmd.HasAvailableSubCommands() {
		fmt.Fprintln(w, theme.StyleDim().Bold(true).Render("COMMANDS"))
		nameWidth := 0
		for _, sub := range cmd.Commands() {
			if !sub.IsAvailableCommand() && sub.Name() != "help" {
				continue
			}
			if l := len(sub.Name()); l > nameWidth {
				nameWidth = l
			}
		}
		for _, sub := range cmd.Commands() {
			if !sub.IsAvailableCommand() && sub.Name() != "help" {
				continue
			}
			fmt.Fprintf(w, "  %s  %s\n",
				theme.Code(padRight(sub.Name(), nameWidth)),
				theme.StyleDim().Render(sub.Short),
			)
		}
		fmt.Fprintln(w)
	}

	// FLAGS — local then inherited.
	local := cmd.LocalFlags()
	if local.HasAvailableFlags() {
		fmt.Fprintln(w, theme.StyleDim().Bold(true).Render("FLAGS"))
		fmt.Fprint(w, indentFlags(local.FlagUsages()))
		fmt.Fprintln(w)
	}
	inherited := cmd.InheritedFlags()
	if inherited.HasAvailableFlags() {
		fmt.Fprintln(w, theme.StyleDim().Bold(true).Render("GLOBAL FLAGS"))
		fmt.Fprint(w, indentFlags(inherited.FlagUsages()))
		fmt.Fprintln(w)
	}

	if cmd.HasAvailableSubCommands() {
		fmt.Fprintln(w, theme.Hint("Use "+theme.Code(cmd.CommandPath()+" <command> --help")+" for more information about a command."))
	}
}

// indentFlags re-renders cobra's flag usage block so flag names are colored
// and descriptions are dimmed while preserving cobra's column alignment.
func indentFlags(usages string) string {
	var b strings.Builder
	for _, line := range strings.Split(strings.TrimRight(usages, "\n"), "\n") {
		if line == "" {
			b.WriteString("\n")
			continue
		}
		trimmed := strings.TrimLeft(line, " ")
		leading := line[:len(line)-len(trimmed)]
		// cobra separates the flag column from the description with 3+ spaces.
		// Find that gap, color the left side, dim the right; preserve the
		// gap so descriptions stay column-aligned.
		idx := strings.Index(trimmed, "   ")
		if idx == -1 {
			b.WriteString("  " + leading + theme.Code(trimmed) + "\n")
			continue
		}
		flagPart := trimmed[:idx]
		gap := trimmed[idx:]
		gapEnd := 0
		for gapEnd < len(gap) && gap[gapEnd] == ' ' {
			gapEnd++
		}
		descPart := gap[gapEnd:]
		b.WriteString("  " + leading + theme.Code(flagPart) + gap[:gapEnd] + theme.StyleDim().Render(descPart) + "\n")
	}
	return b.String()
}
