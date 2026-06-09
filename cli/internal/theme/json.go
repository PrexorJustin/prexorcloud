package theme

import (
	"encoding/json"
	"fmt"
	"os"
)

// PrintJSON writes v as indented JSON to stdout. It is the single rendering
// path for `--json` / PREXOR_OUTPUT=json mode, which short-circuits all
// styled output before any theme styling is applied.
func PrintJSON(v any) error {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		return fmt.Errorf("encode json: %w", err)
	}
	return nil
}
