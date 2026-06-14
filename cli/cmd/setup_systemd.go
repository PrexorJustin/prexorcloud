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

// resolveEnableOnBoot answers "should this component start automatically on boot?".
// Native → systemctl enable; Docker → restart=unless-stopped. Driven by --boot-mode,
// falling back to the legacy --service-mode for non-interactive back-compat.
func resolveEnableOnBoot(component string) (bool, error) {
	mode := firstNonEmpty(setupBootMode, setupServiceMode)
	return resolveLifecycleMode(mode, "--boot-mode",
		fmt.Sprintf("Start the %s automatically on boot?", component), true)
}

// resolveStartNow answers "should setup start this component now?". Native →
// systemctl start; Docker → docker compose up -d. Driven by --start-mode, falling
// back to the legacy --startup-validation-mode for non-interactive back-compat.
func resolveStartNow(component string) (bool, error) {
	mode := firstNonEmpty(setupStartMode, setupStartupValidationMode)
	return resolveLifecycleMode(mode, "--start-mode",
		fmt.Sprintf("Start the %s now?", component), true)
}

// resolveLifecycleMode is the shared decision logic for the boot/start prompts.
// "" or "prompt" asks the operator (default def); "enable"/"disable" force the
// answer. In non-interactive mode "" defaults to enabled and "prompt" is an error.
func resolveLifecycleMode(mode, flagName, title string, def bool) (bool, error) {
	mode = strings.ToLower(strings.TrimSpace(mode))
	if setupNonInteractive {
		switch mode {
		case "", lifecycleModeEnable:
			return true, nil
		case lifecycleModeDisable:
			return false, nil
		case lifecycleModePrompt:
			return false, fmt.Errorf("%s=prompt cannot be used with --non-interactive", flagName)
		default:
			return false, fmt.Errorf("invalid %s %q: use %q, %q, or omit it",
				flagName, mode, lifecycleModeEnable, lifecycleModeDisable)
		}
	}

	switch mode {
	case "", lifecycleModePrompt:
		return confirmSetup(title, def)
	case lifecycleModeEnable:
		return true, nil
	case lifecycleModeDisable:
		return false, nil
	default:
		return false, fmt.Errorf("invalid %s %q: use %q, %q, or %q",
			flagName, mode, lifecycleModePrompt, lifecycleModeEnable, lifecycleModeDisable)
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
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
