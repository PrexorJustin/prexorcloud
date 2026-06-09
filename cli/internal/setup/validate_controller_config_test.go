package setup

import (
	"strings"
	"testing"
)

func TestValidateControllerYAML_TheBugFromTheField(t *testing.T) {
	// This is the controller.yml the v1.0 wizard wrote — the one that
	// crash-looped 115,000 times on the operator's VPS. The Go-side
	// validator MUST reject it before we ever write the unit file.
	const wizardYAML = `
runtime:
  profile: production
http:
  port: 8080
grpc:
  port: 9090
database:
  uri: 'mongodb://localhost:27017'
redis:
  uri: 'redis://localhost:6379'
modules:
  signing:
    required: true
    mode: KEYED
    allowUnsignedDevelopment: true
`
	errs := ValidateControllerYAML(wizardYAML)
	if len(errs) != 1 {
		t.Fatalf("expected exactly 1 error, got %d: %#v", len(errs), errs)
	}
	if errs[0].Code != ValErrMissingTrustRoot {
		t.Errorf("Code = %s, want %s", errs[0].Code, ValErrMissingTrustRoot)
	}
	if errs[0].Field != "modules.signing.trustRoot" {
		t.Errorf("Field = %s, want modules.signing.trustRoot", errs[0].Field)
	}
	if !strings.Contains(errs[0].Message, "production") {
		t.Errorf("Message should mention the production profile: %s", errs[0].Message)
	}
	if len(errs[0].Recovery) < 2 {
		t.Errorf("expected ≥2 recovery hints, got %d", len(errs[0].Recovery))
	}
}

func TestValidateControllerYAML_TrustRootSuppliedAccepts(t *testing.T) {
	// Same shape as the bug case but WITH a trust-root path. Wizard's auto-
	// provisioning lands here.
	const yaml = `
runtime:
  profile: production
http:
  port: 8080
grpc:
  port: 9090
database:
  uri: 'mongodb://localhost:27017'
redis:
  uri: 'redis://localhost:6379'
modules:
  signing:
    required: true
    trustRoot: config/security/module-trust-root.pem
`
	if errs := ValidateControllerYAML(yaml); len(errs) != 0 {
		t.Fatalf("expected no errors, got %#v", errs)
	}
}

func TestValidateControllerYAML_DevelopmentDefaultsAcceptBlank(t *testing.T) {
	// Development profile + signing.required absent → required derives to
	// false → trustRoot may be blank.
	const yaml = `
runtime:
  profile: development
database:
  uri: 'mongodb://localhost:27017'
http:
  port: 8080
grpc:
  port: 9090
`
	if errs := ValidateControllerYAML(yaml); len(errs) != 0 {
		t.Fatalf("expected no errors, got %#v", errs)
	}
}

func TestValidateControllerYAML_DevelopmentRequiredTrueRejectsBlankTrustRoot(t *testing.T) {
	// Operator explicitly enabled signing in dev without providing a root —
	// still invalid, but the message wording changes (no "production" hint).
	const yaml = `
runtime:
  profile: development
http: { port: 8080 }
grpc: { port: 9090 }
database:
  uri: 'mongodb://localhost:27017'
modules:
  signing:
    required: true
`
	errs := ValidateControllerYAML(yaml)
	if len(errs) != 1 {
		t.Fatalf("expected 1 error, got %d: %#v", len(errs), errs)
	}
	if errs[0].Code != ValErrMissingTrustRoot {
		t.Errorf("Code = %s, want %s", errs[0].Code, ValErrMissingTrustRoot)
	}
	if strings.Contains(errs[0].Message, "production") {
		t.Errorf("dev-profile message should not reference production: %s", errs[0].Message)
	}
}

func TestValidateControllerYAML_DuplicatePort(t *testing.T) {
	const yaml = `
runtime: { profile: development }
http: { port: 8080 }
grpc: { port: 8080 }
database: { uri: 'mongodb://localhost:27017' }
`
	errs := ValidateControllerYAML(yaml)
	if len(errs) != 1 || errs[0].Code != ValErrDuplicatePort {
		t.Fatalf("expected duplicate-port error, got %#v", errs)
	}
}

func TestValidateControllerYAML_InvalidProfile(t *testing.T) {
	const yaml = `
runtime: { profile: staging }
http: { port: 8080 }
grpc: { port: 9090 }
database: { uri: 'mongodb://localhost:27017' }
`
	errs := ValidateControllerYAML(yaml)
	found := false
	for _, e := range errs {
		if e.Code == ValErrInvalidProfile {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected invalid-profile error, got %#v", errs)
	}
}

func TestValidateControllerYAML_ProductionRequiresRedis(t *testing.T) {
	const yaml = `
runtime: { profile: production }
http: { port: 8080 }
grpc: { port: 9090 }
database: { uri: 'mongodb://localhost:27017' }
modules:
  signing:
    required: false
`
	errs := ValidateControllerYAML(yaml)
	found := false
	for _, e := range errs {
		if e.Code == ValErrMissingRedisURI {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected missing-redis error, got %#v", errs)
	}
}

// TestValidateControllerYAML_RejectsJWTSecretPlaceholder pins the second
// crash-loop bug we hit in the field: the wizard used to write
// `<auto-generate-on-first-start>` as the JWT secret. Java's JwtManager
// Base64-decodes the field unconditionally and dies on the `<` byte (0x3c).
// The validator catches this before it ships to disk.
func TestValidateControllerYAML_RejectsJWTSecretPlaceholder(t *testing.T) {
	const yaml = `
runtime: { profile: development }
http: { port: 8080 }
grpc: { port: 9090 }
database: { uri: 'mongodb://localhost:27017' }
security:
  jwtSecret: '<auto-generate-on-first-start>'
`
	errs := ValidateControllerYAML(yaml)
	if len(errs) != 1 || errs[0].Code != ValErrInvalidJWTSecret {
		t.Fatalf("expected invalid-jwt-secret error, got %#v", errs)
	}
	if errs[0].Field != "security.jwtSecret" {
		t.Errorf("Field = %q, want security.jwtSecret", errs[0].Field)
	}
}

func TestValidateControllerYAML_AcceptsValidBase64Secret(t *testing.T) {
	const yaml = `
runtime: { profile: development }
http: { port: 8080 }
grpc: { port: 9090 }
database: { uri: 'mongodb://localhost:27017' }
security:
  jwtSecret: 'TG9yZW1JcHN1bUJhc2U2NEZvclRlc3RpbmdQdXJwb3Nlc09ubHk='
`
	if errs := ValidateControllerYAML(yaml); len(errs) != 0 {
		t.Fatalf("expected no errors, got %#v", errs)
	}
}

func TestValidateControllerYAML_AcceptsAlphanumericSecret(t *testing.T) {
	// The wizard's genSecret() produces an alphanumeric string; every
	// alphanumeric character is a valid Base64 character, so this must pass.
	const yaml = `
runtime: { profile: development }
http: { port: 8080 }
grpc: { port: 9090 }
database: { uri: 'mongodb://localhost:27017' }
security:
  jwtSecret: 'aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789AbCdEfGhIjKlMnOpQrStUvWxYz01'
`
	if errs := ValidateControllerYAML(yaml); len(errs) != 0 {
		t.Fatalf("expected no errors, got %#v", errs)
	}
}

func TestValidateControllerYAML_MalformedYAML(t *testing.T) {
	errs := ValidateControllerYAML("\tindented with a tab: which YAML forbids")
	if len(errs) != 1 || errs[0].Code != ValErrInvalidYAML {
		t.Fatalf("expected single InvalidYAML error, got %#v", errs)
	}
}
