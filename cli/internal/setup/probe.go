package setup

import (
	"bufio"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"
)

// ClusterProbeResult summarises what a Redis instance already contains
// in terms of PrexorCloud cluster state.
type ClusterProbeResult struct {
	Reachable   bool
	NodeOwners  int // count of keys matching prexor:nodeowner:*
	GroupLeases int // count of keys matching prexor:lease:group:*
}

// ProbeRedisCluster connects to a Redis instance via a minimal RESP client
// (no external dependencies) and counts PrexorCloud ownership/lease keys so
// the setup wizard can show the user whether they are about to join an
// existing cluster or start a fresh one.
func ProbeRedisCluster(uri string) (ClusterProbeResult, error) {
	host, password, err := parseRedisURI(uri)
	if err != nil {
		return ClusterProbeResult{}, err
	}

	conn, err := net.DialTimeout("tcp", host, 5*time.Second)
	if err != nil {
		return ClusterProbeResult{}, fmt.Errorf("cannot connect to %s: %w", host, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	r := bufio.NewReader(conn)

	if password != "" {
		if _, err := sendRESP(conn, r, "AUTH", password); err != nil {
			return ClusterProbeResult{}, fmt.Errorf("redis AUTH failed: %w", err)
		}
	}

	if _, err := sendRESP(conn, r, "PING"); err != nil {
		return ClusterProbeResult{}, fmt.Errorf("redis PING failed: %w", err)
	}
	result := ClusterProbeResult{Reachable: true}

	nodeOwners, err := scanCount(conn, r, "prexor:nodeowner:*")
	if err != nil {
		return result, fmt.Errorf("SCAN prexor:nodeowner:* failed: %w", err)
	}
	result.NodeOwners = nodeOwners

	groupLeases, err := scanCount(conn, r, "prexor:lease:group:*")
	if err != nil {
		return result, fmt.Errorf("SCAN prexor:lease:group:* failed: %w", err)
	}
	result.GroupLeases = groupLeases

	return result, nil
}

// parseRedisURI extracts host:port and optional password from a redis://
// URI. Supports redis://host:port and redis://:password@host:port (and
// redis://user:password@host:port, though Redis ignores the user on classic
// AUTH). Returns defaults (localhost:6379) for missing port.
func parseRedisURI(uri string) (hostPort, password string, err error) {
	s := uri
	for _, p := range []string{"redis://", "rediss://"} {
		if strings.HasPrefix(s, p) {
			s = s[len(p):]
			break
		}
	}
	if at := strings.LastIndexByte(s, '@'); at >= 0 {
		creds := s[:at]
		s = s[at+1:]
		if colon := strings.IndexByte(creds, ':'); colon >= 0 {
			password = creds[colon+1:]
		} else {
			password = creds
		}
	}
	if slash := strings.IndexByte(s, '/'); slash >= 0 {
		s = s[:slash]
	}
	if s == "" {
		return "", "", fmt.Errorf("empty redis host in URI")
	}
	if !strings.Contains(s, ":") {
		s += ":6379"
	}
	return s, password, nil
}

// scanCount iterates SCAN with a MATCH pattern until cursor returns to 0
// and returns the total number of matched keys.
func scanCount(conn net.Conn, r *bufio.Reader, pattern string) (int, error) {
	cursor := "0"
	total := 0
	for {
		reply, err := sendRESP(conn, r, "SCAN", cursor, "MATCH", pattern, "COUNT", "100")
		if err != nil {
			return 0, err
		}
		arr, ok := reply.([]any)
		if !ok || len(arr) != 2 {
			return 0, fmt.Errorf("unexpected SCAN reply shape")
		}
		nextCursor, ok := arr[0].(string)
		if !ok {
			return 0, fmt.Errorf("unexpected SCAN cursor type")
		}
		keys, ok := arr[1].([]any)
		if !ok {
			return 0, fmt.Errorf("unexpected SCAN keys type")
		}
		total += len(keys)
		if nextCursor == "0" {
			return total, nil
		}
		cursor = nextCursor
	}
}

// sendRESP writes a RESP array command and reads a single reply.
func sendRESP(conn net.Conn, r *bufio.Reader, args ...string) (any, error) {
	var sb strings.Builder
	sb.WriteString("*")
	sb.WriteString(strconv.Itoa(len(args)))
	sb.WriteString("\r\n")
	for _, a := range args {
		sb.WriteString("$")
		sb.WriteString(strconv.Itoa(len(a)))
		sb.WriteString("\r\n")
		sb.WriteString(a)
		sb.WriteString("\r\n")
	}
	if _, err := conn.Write([]byte(sb.String())); err != nil {
		return nil, err
	}
	return readRESP(r)
}

// readRESP reads a single RESP reply. Supports simple string, error, integer,
// bulk string and array — enough for PING, AUTH and SCAN.
func readRESP(r *bufio.Reader) (any, error) {
	line, err := readLine(r)
	if err != nil {
		return nil, err
	}
	if len(line) == 0 {
		return nil, fmt.Errorf("empty RESP line")
	}
	switch line[0] {
	case '+':
		return line[1:], nil
	case '-':
		return nil, fmt.Errorf("redis error: %s", line[1:])
	case ':':
		n, err := strconv.Atoi(line[1:])
		if err != nil {
			return nil, err
		}
		return n, nil
	case '$':
		n, err := strconv.Atoi(line[1:])
		if err != nil {
			return nil, err
		}
		if n < 0 {
			return nil, nil
		}
		buf := make([]byte, n+2) // +2 for trailing \r\n
		if _, err := readFull(r, buf); err != nil {
			return nil, err
		}
		return string(buf[:n]), nil
	case '*':
		n, err := strconv.Atoi(line[1:])
		if err != nil {
			return nil, err
		}
		if n < 0 {
			return nil, nil
		}
		arr := make([]any, n)
		for i := 0; i < n; i++ {
			v, err := readRESP(r)
			if err != nil {
				return nil, err
			}
			arr[i] = v
		}
		return arr, nil
	}
	return nil, fmt.Errorf("unknown RESP type %q", line[0])
}

func readLine(r *bufio.Reader) (string, error) {
	line, err := r.ReadString('\n')
	if err != nil {
		return "", err
	}
	if len(line) < 2 || line[len(line)-2] != '\r' {
		return "", fmt.Errorf("malformed RESP line")
	}
	return line[:len(line)-2], nil
}

func readFull(r *bufio.Reader, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := r.Read(buf[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}
