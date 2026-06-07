package setupweb

import (
	"crypto/x509"
	"net"
	"strings"
	"testing"
	"time"
)

func TestNewSelfSignedCert_HasExpectedSANs(t *testing.T) {
	cert, err := newSelfSignedCert("203.0.113.42")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	want := net.ParseIP("203.0.113.42")
	matched := false
	for _, ip := range parsed.IPAddresses {
		if ip.Equal(want) {
			matched = true
		}
	}
	if !matched {
		t.Errorf("cert IPs = %v, missing %s", parsed.IPAddresses, want)
	}
	// 127.0.0.1 must always be in SANs so SSH-tunnelled access works.
	loopMatched := false
	for _, ip := range parsed.IPAddresses {
		if ip.Equal(net.IPv4(127, 0, 0, 1)) {
			loopMatched = true
		}
	}
	if !loopMatched {
		t.Errorf("cert IPs = %v, missing 127.0.0.1", parsed.IPAddresses)
	}
	if parsed.NotAfter.Before(time.Now().Add(20 * time.Hour)) {
		t.Errorf("cert expires too soon: %v", parsed.NotAfter)
	}
}

func TestNewSelfSignedCert_DNSHost(t *testing.T) {
	cert, err := newSelfSignedCert("example.test")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	if len(parsed.DNSNames) == 0 || parsed.DNSNames[0] != "example.test" {
		t.Errorf("DNSNames = %v, want [example.test]", parsed.DNSNames)
	}
}

func TestFingerprintSHA256(t *testing.T) {
	cert, err := newSelfSignedCert("127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	fp, err := fingerprintSHA256(cert)
	if err != nil {
		t.Fatal(err)
	}
	// Format: 32 colon-separated hex pairs.
	parts := strings.Split(fp, ":")
	if len(parts) != 32 {
		t.Errorf("fingerprint has %d parts, want 32: %s", len(parts), fp)
	}
}

func TestIsLoopbackHost(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"", true},
		{"localhost", true},
		{"127.0.0.1", true},
		{"127.5.5.5", true},
		{"::1", true},
		{"0.0.0.0", false},
		{"203.0.113.4", false},
		{"example.com", false},
	}
	for _, c := range cases {
		if got := isLoopbackHost(c.host); got != c.want {
			t.Errorf("isLoopbackHost(%q) = %v, want %v", c.host, got, c.want)
		}
	}
}

func TestIsUnspecifiedHost(t *testing.T) {
	if !isUnspecifiedHost("0.0.0.0") {
		t.Error("0.0.0.0 should be unspecified")
	}
	if !isUnspecifiedHost("::") {
		t.Error(":: should be unspecified")
	}
	if isUnspecifiedHost("127.0.0.1") {
		t.Error("127.0.0.1 should not be unspecified")
	}
}
