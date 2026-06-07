package setupweb

import (
	"crypto/sha256"
	"encoding/hex"
	"net"
	"strings"
)

// sha256Hex returns a colon-separated hex SHA-256 of b, formatted the way
// browsers display cert fingerprints (XX:XX:XX…).
func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	hexed := hex.EncodeToString(sum[:])
	parts := make([]string, 0, len(sum))
	for i := 0; i < len(hexed); i += 2 {
		parts = append(parts, strings.ToUpper(hexed[i:i+2]))
	}
	return strings.Join(parts, ":")
}

// firstNonLoopbackIPv4 returns the first non-loopback IPv4 address bound on
// any interface, or an empty string if none is found. Used to suggest a
// --public-host default when the operator didn't specify one.
func firstNonLoopbackIPv4() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ""
	}
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() {
			continue
		}
		ip4 := ipnet.IP.To4()
		if ip4 == nil {
			continue
		}
		return ip4.String()
	}
	return ""
}

// isLoopbackHost reports whether host (a hostname or IP literal) is loopback
// or the empty string. Used to validate that --public bind addresses are
// non-loopback. We don't DNS-resolve here — the caller should pass an IP or
// the literal "localhost".
func isLoopbackHost(host string) bool {
	if host == "" || host == "localhost" {
		return true
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback()
	}
	return false
}

// isUnspecifiedHost reports whether host is the wildcard bind (0.0.0.0 or ::).
func isUnspecifiedHost(host string) bool {
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsUnspecified()
	}
	return false
}
