package setup

import (
	"encoding/base64"
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"
)

// ControllerConfigValidationError points at the YAML field whose value the
// Java validator would reject at startup. The wizard surfaces these via the
// install stream so the operator sees them BEFORE a 90-second silent wait on
// a JVM that's never going to come up — which is the failure mode that
// produced 115,000 crash-loop restarts on the install host that motivated
// this validator.
//
// Field is the dotted path (`modules.signing.trustRoot`) and Recovery is a
// list of concrete next actions the wizard renders as a bulleted block in
// the error UI. Code is one of the stable validator error codes below so
// the frontend can branch on it.
type ControllerConfigValidationError struct {
	Code     string   // one of valErr* codes
	Field    string   // dotted YAML path
	Message  string   // operator-facing sentence
	Recovery []string // bulleted next actions
}

func (e ControllerConfigValidationError) Error() string {
	return fmt.Sprintf("%s: %s", e.Field, e.Message)
}

// Stable codes the wizard maps to UI copy + docs deep-links. Keep these
// aligned with the Java validator's branches (ConfigValidator.java) — when
// the Java validator gains a new check, mirror it here so the operator gets
// the error before the JVM ever starts.
const (
	ValErrInvalidYAML        = "VALIDATE_INVALID_YAML"
	ValErrInvalidProfile     = "VALIDATE_INVALID_PROFILE"
	ValErrMissingTrustRoot   = "VALIDATE_MISSING_TRUST_ROOT"
	ValErrInvalidPort        = "VALIDATE_INVALID_PORT"
	ValErrDuplicatePort      = "VALIDATE_DUPLICATE_PORT"
	ValErrMissingDatabaseURI = "VALIDATE_MISSING_DATABASE_URI"
	ValErrMissingRedisURI    = "VALIDATE_MISSING_REDIS_URI"
	ValErrInvalidJWTSecret   = "VALIDATE_INVALID_JWT_SECRET"
)

// validatedControllerConfig is the subset of controller.yml the Go-side
// validator inspects. It deliberately mirrors only the fields the Java
// ConfigValidator can reject (or has rejected in production) — anything
// the JVM accepts without a startup error is left untyped here so adding
// new optional knobs doesn't churn this struct.
type validatedControllerConfig struct {
	Runtime struct {
		Profile string `yaml:"profile"`
	} `yaml:"runtime"`
	HTTP struct {
		Port int `yaml:"port"`
	} `yaml:"http"`
	GRPC struct {
		Port int `yaml:"port"`
	} `yaml:"grpc"`
	Database struct {
		URI string `yaml:"uri"`
	} `yaml:"database"`
	Redis *struct {
		URI string `yaml:"uri"`
	} `yaml:"redis"`
	Modules struct {
		Signing struct {
			// *bool so we can distinguish "absent" from "explicitly false" —
			// the Java validator falls back to the runtime profile when the
			// key is missing.
			Required  *bool  `yaml:"required"`
			TrustRoot string `yaml:"trustRoot"`
		} `yaml:"signing"`
	} `yaml:"modules"`
	Security struct {
		JWTSecret string `yaml:"jwtSecret"`
	} `yaml:"security"`
}

// ValidateControllerYAML parses the given controller.yml and returns every
// validation error the Java ConfigValidator would raise at controller
// startup. It mirrors ConfigValidator.validate() (java/cloud-controller/.../
// config/ConfigValidator.java) — when the Java validator changes, mirror
// the change here too. Returning multiple errors at once matches the Java
// behaviour ("fix all issues in one pass").
//
// The handler that calls this runs it BEFORE writing the file to disk or
// registering the systemd unit, so a wizard config that would crash-loop the
// JVM is rejected with a 422 + a list of structured errors the UI can render
// inline, with recovery hints.
func ValidateControllerYAML(rawYAML string) []ControllerConfigValidationError {
	var cfg validatedControllerConfig
	if err := yaml.Unmarshal([]byte(rawYAML), &cfg); err != nil {
		return []ControllerConfigValidationError{{
			Code:    ValErrInvalidYAML,
			Field:   "(root)",
			Message: "controller.yml is not valid YAML: " + err.Error(),
			Recovery: []string{
				"Re-render the wizard's review screen to regenerate controller.yml.",
				"If you hand-edited the file, check for tab indentation or unclosed quotes.",
			},
		}}
	}

	var errs []ControllerConfigValidationError

	// Profile must be one of the two supported values. Anything else hits
	// RuntimeConfig.supported() == false in the JVM.
	profile := strings.TrimSpace(cfg.Runtime.Profile)
	if profile == "" {
		profile = "development" // matches RuntimeConfig.java default
	}
	isProduction := profile == "production"
	if profile != "development" && profile != "production" {
		errs = append(errs, ControllerConfigValidationError{
			Code:    ValErrInvalidProfile,
			Field:   "runtime.profile",
			Message: fmt.Sprintf("runtime.profile must be one of [development, production], got: %s", profile),
			Recovery: []string{
				"Set runtime.profile to 'development' or 'production' on the Essentials screen.",
			},
		})
	}

	// Ports — same range as validatePort() in the Java validator.
	if cfg.HTTP.Port != 0 && (cfg.HTTP.Port < 1 || cfg.HTTP.Port > 65535) {
		errs = append(errs, ControllerConfigValidationError{
			Code:     ValErrInvalidPort,
			Field:    "http.port",
			Message:  fmt.Sprintf("http.port must be between 1 and 65535, got: %d", cfg.HTTP.Port),
			Recovery: []string{"Pick an HTTP port in 1024–65535 on the Essentials screen."},
		})
	}
	if cfg.GRPC.Port != 0 && (cfg.GRPC.Port < 1 || cfg.GRPC.Port > 65535) {
		errs = append(errs, ControllerConfigValidationError{
			Code:     ValErrInvalidPort,
			Field:    "grpc.port",
			Message:  fmt.Sprintf("grpc.port must be between 1 and 65535, got: %d", cfg.GRPC.Port),
			Recovery: []string{"Pick a gRPC port in 1024–65535 on the Essentials screen."},
		})
	}
	if cfg.HTTP.Port != 0 && cfg.HTTP.Port == cfg.GRPC.Port {
		errs = append(errs, ControllerConfigValidationError{
			Code:     ValErrDuplicatePort,
			Field:    "grpc.port",
			Message:  fmt.Sprintf("http.port and grpc.port must be different (both are %d)", cfg.HTTP.Port),
			Recovery: []string{"Change either HTTP or gRPC port on the Essentials screen."},
		})
	}

	// Storage URIs. The validator allows database.uri blank only if redis is
	// absent AND the profile is non-production; replicate exactly.
	if strings.TrimSpace(cfg.Database.URI) == "" {
		errs = append(errs, ControllerConfigValidationError{
			Code:    ValErrMissingDatabaseURI,
			Field:   "database.uri",
			Message: "database.uri must be configured",
			Recovery: []string{
				"Set MongoDB URI on the Essentials screen, or switch MongoDB to 'use existing URI'.",
			},
		})
	}
	if isProduction && (cfg.Redis == nil || strings.TrimSpace(cfg.Redis.URI) == "") {
		errs = append(errs, ControllerConfigValidationError{
			Code:    ValErrMissingRedisURI,
			Field:   "redis.uri",
			Message: "redis.uri must be configured when runtime.profile=production",
			Recovery: []string{
				"Set Redis URI on the Essentials screen, or switch to development profile.",
			},
		})
	}

	// JWT secret — Java's JwtManager (security/jwt/JwtManager.java:170)
	// Base64-decodes this field unconditionally and crash-loops on any
	// invalid character. A literal placeholder like
	// "<auto-generate-on-first-start>" passes the Java validator (which
	// doesn't check the secret) but kills the JVM at JwtManager init. Catch
	// both the placeholder pattern and any other non-Base64 input here so
	// the operator sees a clean wizard error instead of 115k restart-counter
	// entries.
	if jwt := strings.TrimSpace(cfg.Security.JWTSecret); jwt != "" {
		isPlaceholder := strings.ContainsAny(jwt, "<>") // <auto-generate-…>, <REDACTED>, etc.
		_, decodeErr := base64.StdEncoding.DecodeString(jwt)
		// Also accept the URL-safe variant (RFC 4648 §5) so an operator
		// pasting `base64url`-encoded output isn't rejected by accident.
		_, urlDecodeErr := base64.URLEncoding.DecodeString(jwt)
		if isPlaceholder || (decodeErr != nil && urlDecodeErr != nil) {
			errs = append(errs, ControllerConfigValidationError{
				Code:    ValErrInvalidJWTSecret,
				Field:   "security.jwtSecret",
				Message: "security.jwtSecret must be a Base64-encoded string of at least 32 random bytes",
				Recovery: []string{
					"Click 'Generate' next to the JWT secret on the Security screen to produce a valid value, or",
					"Paste an existing Base64 secret (e.g. `openssl rand -base64 48`).",
				},
			})
		}
	}

	// Module signing — the trigger for the bug this validator was added to
	// catch. Mirrors ConfigValidator.java:83-95. signingRequired defaults to
	// production==true when the YAML doesn't set it explicitly.
	signing := cfg.Modules.Signing
	signingRequired := isProduction
	if signing.Required != nil {
		signingRequired = *signing.Required
	}
	if signingRequired && strings.TrimSpace(signing.TrustRoot) == "" {
		msg := "modules.signing.trustRoot must be configured when modules.signing.required=true"
		recovery := []string{
			"Re-run the wizard so it auto-provisions a cosign trust root, or",
			"Set modules.signing.trustRoot on the Security screen to a PEM file you control, or",
			"Set modules.signing.required=false on the Security screen to opt out.",
		}
		if isProduction {
			msg = "modules.signing.trustRoot must be configured when runtime.profile=production" +
				" (or set modules.signing.required=false to opt out)"
		}
		errs = append(errs, ControllerConfigValidationError{
			Code:     ValErrMissingTrustRoot,
			Field:    "modules.signing.trustRoot",
			Message:  msg,
			Recovery: recovery,
		})
	}

	return errs
}
