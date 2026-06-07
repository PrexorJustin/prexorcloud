package main

import (
	"os"

	"github.com/prexorcloud/prexorctl/cmd"
)

// version is set via ldflags at build time: -X main.version=x.y.z
var version = "dev"

func main() {
	cmd.SetVersion(version)
	if err := cmd.Execute(); err != nil {
		os.Exit(1)
	}
}
