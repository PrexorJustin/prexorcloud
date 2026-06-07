package cmd

import (
	"errors"

	"github.com/prexorcloud/prexorctl/internal/setup"
)

func ensureDockerCompose() error {
	if err := ensureDependency("Docker", setup.DetectDocker, func() error {
		return errors.New("Docker is required for compose setup. Install Docker Engine or Docker Desktop and re-run setup")
	}); err != nil {
		return err
	}
	return ensureDependency("Docker Compose", setup.DetectDockerCompose, func() error {
		return errors.New("Docker Compose is required for compose setup. Install the Docker Compose plugin and re-run setup")
	})
}
