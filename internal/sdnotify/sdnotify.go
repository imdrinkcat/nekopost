// Package sdnotify implements the small subset of systemd's notify protocol
// used by the service: readiness, watchdog keep-alives and stopping.
package sdnotify

import (
	"net"
	"os"
	"strconv"
	"time"
)

func send(state string) error {
	path := os.Getenv("NOTIFY_SOCKET")
	if path == "" {
		return nil
	}
	if path[0] == '@' {
		path = "\x00" + path[1:]
	}
	conn, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: path, Net: "unixgram"})
	if err != nil {
		return err
	}
	defer conn.Close()
	_, err = conn.Write([]byte(state))
	return err
}

// Ready tells systemd the service finished starting.
func Ready() error { return send("READY=1") }

// Stopping tells systemd the service is shutting down.
func Stopping() error { return send("STOPPING=1") }

// Watchdog sends a watchdog keep-alive.
func Watchdog() error { return send("WATCHDOG=1") }

// WatchdogInterval returns the configured watchdog timeout, or 0 if disabled.
func WatchdogInterval() time.Duration {
	usec, err := strconv.ParseInt(os.Getenv("WATCHDOG_USEC"), 10, 64)
	if err != nil || usec <= 0 {
		return 0
	}
	if pid := os.Getenv("WATCHDOG_PID"); pid != "" && pid != strconv.Itoa(os.Getpid()) {
		return 0
	}
	return time.Duration(usec) * time.Microsecond
}
