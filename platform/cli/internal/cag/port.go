package cag

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// defaultLocalPort is the local ADB port when none was chosen. It stays out
// of 5554-5585, which the local adb server scans for emulators.
const defaultLocalPort = 15555

const settingsName = "settings.json"

type Settings struct {
	LocalPort int `json:"local_port,omitempty"`
}

func (app *App) loadSettings() (Settings, error) {
	var settings Settings
	raw, err := os.ReadFile(app.path(settingsName))
	if errors.Is(err, os.ErrNotExist) {
		return settings, nil
	}
	if err != nil {
		return settings, fmt.Errorf("read settings: %w", err)
	}
	if err := json.Unmarshal(raw, &settings); err != nil {
		return settings, errors.New("the saved settings are corrupt; delete " + app.path(settingsName))
	}
	return settings, nil
}

func (app *App) saveSettings(settings Settings) error {
	if err := app.ensureConfig(); err != nil {
		return err
	}
	raw, err := json.Marshal(settings)
	if err != nil {
		return err
	}
	return writeAtomic(app.path(settingsName), append(raw, '\n'), 0o600)
}

func validLocalPort(port int) bool { return port >= 1024 && port <= 65535 }

func parseLocalPort(value string) (int, error) {
	port, err := strconv.Atoi(value)
	if err != nil || !validLocalPort(port) {
		return 0, fmt.Errorf("invalid port %q: use 1024-65535", value)
	}
	return port, nil
}

// localPort returns the port to use: the requested one, which is remembered,
// otherwise the remembered one, otherwise the default.
func (app *App) localPort(requested int) (int, error) {
	settings, err := app.loadSettings()
	if err != nil {
		return 0, err
	}
	if requested != 0 {
		if !validLocalPort(requested) {
			return 0, fmt.Errorf("invalid port %d: use 1024-65535", requested)
		}
		if settings.LocalPort != requested {
			settings.LocalPort = requested
			if err := app.saveSettings(settings); err != nil {
				return 0, fmt.Errorf("remember port: %w", err)
			}
		}
		return requested, nil
	}
	if validLocalPort(settings.LocalPort) {
		return settings.LocalPort, nil
	}
	if validLocalPort(app.defaultPort) {
		return app.defaultPort, nil
	}
	return defaultLocalPort, nil
}

func loopbackAddress(port int) string { return "127.0.0.1:" + strconv.Itoa(port) }

// localPortFree reports whether nothing listens on 127.0.0.1:port. A failed
// bind means a listener holds the address and is never connected to. A
// successful bind is followed by a connect, because on macOS a wildcard
// listener on the same port does not stop a bind to 127.0.0.1.
func localPortFree(port int) bool {
	listener, err := net.Listen("tcp4", loopbackAddress(port))
	if err != nil {
		return false
	}
	listener.Close()
	conn, err := net.DialTimeout("tcp4", loopbackAddress(port), 300*time.Millisecond)
	if err == nil {
		conn.Close()
		return false
	}
	return true
}

type portBusyError struct{ port int }

func (err *portBusyError) Error() string {
	return fmt.Sprintf("%s is in use by another program; free it or pick another port: cag connect --port N",
		loopbackAddress(err.port))
}

func (app *App) readLocalPort() (int, error) {
	data, err := os.ReadFile(app.path("local-port"))
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(data)))
}

// adbSerialState returns how the local adb server lists serial ("" when it
// does not list it) and whether adb itself is missing from PATH.
func (app *App) adbSerialState(ctx context.Context, serial string) (string, bool) {
	output, err := app.runner.Run(ctx, nil, "adb", "devices")
	if err != nil {
		return "", errors.Is(err, exec.ErrNotFound)
	}
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == serial {
			return fields[1], false
		}
	}
	return "", false
}

// adbConnect asks the local adb server to attach serial. adb connect exits 0
// even when it fails, so its words decide.
func (app *App) adbConnect(ctx context.Context, serial string) error {
	output, err := app.runner.Run(ctx, nil, "adb", "connect", serial)
	if err != nil {
		return fmt.Errorf("adb connect %s failed: %w", serial, err)
	}
	if !strings.Contains(strings.ToLower(string(output)), "connected to") {
		return fmt.Errorf("adb could not reach the car's ADB through the tunnel at %s: %s",
			serial, strings.TrimSpace(string(output)))
	}
	return nil
}
