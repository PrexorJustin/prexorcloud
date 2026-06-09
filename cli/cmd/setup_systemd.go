package cmd

import (
	"errors"
	"fmt"
	"strings"

	"github.com/prexorcloud/prexorctl/internal/tui"
)

func promptServiceRegistration(registerFn func() error) (bool, error) {
	register, err := resolveServiceRegistration()
	if err != nil {
		return false, err
	}
	if !register {
		return false, nil
	}
	if err := tui.SpinWith("Registering systemd service...", registerFn); err != nil {
		return false, fmt.Errorf("failed to register service: %w", err)
	}
	return true, nil
}

func resolveServiceRegistration() (bool, error) {
	mode := strings.ToLower(strings.TrimSpace(setupServiceMode))

	if setupNonInteractive {
		switch mode {
		case "", serviceModeDisable:
			return false, nil
		case serviceModeEnable:
			return true, nil
		case serviceModePrompt:
			return false, errors.New("--service-mode=prompt cannot be used with --non-interactive")
		default:
			return false, fmt.Errorf("invalid service mode %q: use %q, %q, or omit it", mode, serviceModeEnable, serviceModeDisable)
		}
	}

	switch mode {
	case "", serviceModePrompt:
		return confirmSetup("Register as systemd service? (recommended for production)", false)
	case serviceModeEnable:
		return true, nil
	case serviceModeDisable:
		return false, nil
	default:
		return false, fmt.Errorf("invalid service mode %q: use %q, %q, or %q", mode, serviceModePrompt, serviceModeEnable, serviceModeDisable)
	}
}

func resolveControllerStartupValidation(serviceRegistered bool) (bool, error) {
	if !serviceRegistered {
		return false, nil
	}

	mode := strings.ToLower(strings.TrimSpace(setupStartupValidationMode))
	if setupNonInteractive {
		switch mode {
		case "", startupValidationEnable:
			return true, nil
		case startupValidationDisable:
			return false, nil
		case startupValidationPrompt:
			return false, errors.New("--startup-validation-mode=prompt cannot be used with --non-interactive")
		default:
			return false, fmt.Errorf(
				"invalid startup validation mode %q: use %q, %q, or omit it",
				mode,
				startupValidationEnable,
				startupValidationDisable,
			)
		}
	}

	switch mode {
	case "", startupValidationPrompt:
		return confirmSetup("Start controller now and wait for health validation? (recommended)", true)
	case startupValidationEnable:
		return true, nil
	case startupValidationDisable:
		return false, nil
	default:
		return false, fmt.Errorf(
			"invalid startup validation mode %q: use %q, %q, or %q",
			mode,
			startupValidationPrompt,
			startupValidationEnable,
			startupValidationDisable,
		)
	}
}
