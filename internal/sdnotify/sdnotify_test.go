package sdnotify

import (
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

func TestNotify(t *testing.T) {
	p := filepath.Join(t.TempDir(), "n.sock")
	conn, err := net.ListenUnixgram("unixgram", &net.UnixAddr{Name: p, Net: "unixgram"})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	t.Setenv("NOTIFY_SOCKET", p)
	if err := Ready(); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 64)
	conn.SetReadDeadline(time.Now().Add(time.Second))
	n, _ := conn.Read(buf)
	if string(buf[:n]) != "READY=1" {
		t.Errorf("got %q", buf[:n])
	}
	t.Setenv("WATCHDOG_USEC", "300000000")
	t.Setenv("WATCHDOG_PID", strconv.Itoa(os.Getpid()))
	if WatchdogInterval() != 5*time.Minute {
		t.Error("watchdog interval")
	}
	t.Setenv("WATCHDOG_PID", "1")
	if WatchdogInterval() != 0 {
		t.Error("watchdog for another pid must be ignored")
	}
	t.Setenv("NOTIFY_SOCKET", "")
	if err := Ready(); err != nil {
		t.Error("no socket should be a no-op")
	}
}
