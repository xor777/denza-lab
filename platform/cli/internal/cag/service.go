package cag

import (
	"bufio"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	serviceLabel    = "ru.adbgw.cag"
	systemdUnitName = "cag.service"
	serviceLogName  = "service.log"
)

// serviceSpec is what a launchd agent or systemd user unit needs to run
// `cag connect --watch` the way the installing shell would.
type serviceSpec struct {
	Executable string
	ConfigDir  string
	Path       string
	LogPath    string
}

// servicePath puts the directory of the adb found at install time first, so
// the service talks to the same adb server as the user's tools.
func servicePath(adbPath string) string {
	var dirs []string
	if adbPath != "" {
		dirs = append(dirs, filepath.Dir(adbPath))
	}
	dirs = append(dirs, "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
	seen := map[string]bool{}
	var unique []string
	for _, dir := range dirs {
		if !seen[dir] {
			seen[dir] = true
			unique = append(unique, dir)
		}
	}
	return strings.Join(unique, ":")
}

func xmlText(value string) string {
	var escaped strings.Builder
	_ = xml.EscapeText(&escaped, []byte(value))
	return escaped.String()
}

func renderLaunchAgent(spec serviceSpec) string {
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>` + serviceLabel + `</string>
	<key>ProgramArguments</key>
	<array>
		<string>` + xmlText(spec.Executable) + `</string>
		<string>connect</string>
		<string>--watch</string>
	</array>
	<key>EnvironmentVariables</key>
	<dict>
		<key>CAG_CONFIG_DIR</key>
		<string>` + xmlText(spec.ConfigDir) + `</string>
		<key>PATH</key>
		<string>` + xmlText(spec.Path) + `</string>
	</dict>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>StandardOutPath</key>
	<string>` + xmlText(spec.LogPath) + `</string>
	<key>StandardErrorPath</key>
	<string>` + xmlText(spec.LogPath) + `</string>
</dict>
</plist>
`
}

// systemdValue quotes one word for a unit file: C-style escapes inside double
// quotes and %% for specifiers. ExecStart also expands $, Environment does not.
func systemdValue(value string, command bool) string {
	replacements := []string{`\`, `\\`, `"`, `\"`, `%`, `%%`}
	if command {
		replacements = append(replacements, `$`, `$$`)
	}
	return `"` + strings.NewReplacer(replacements...).Replace(value) + `"`
}

func renderSystemdUnit(spec serviceSpec) string {
	logPath := strings.ReplaceAll(spec.LogPath, "%", "%%")
	return `[Unit]
Description=Car ADB Gateway tunnel (cag connect --watch)

[Service]
Type=simple
ExecStart=` + systemdValue(spec.Executable, true) + ` connect --watch
Environment=` + systemdValue("CAG_CONFIG_DIR="+spec.ConfigDir, false) + `
Environment=` + systemdValue("PATH="+spec.Path, false) + `
Restart=always
RestartSec=10
# SIGTERM reaches only cag, which closes the tunnel it opened.
KillMode=mixed
StandardOutput=append:` + logPath + `
StandardError=append:` + logPath + `

[Install]
WantedBy=default.target
`
}

func (app *App) launchAgentPath() string {
	return filepath.Join(app.homeDir, "Library", "LaunchAgents", serviceLabel+".plist")
}

func (app *App) systemdUnitPath() string {
	base := os.Getenv("XDG_CONFIG_HOME")
	if !filepath.IsAbs(base) {
		base = filepath.Join(app.homeDir, ".config")
	}
	return filepath.Join(base, "systemd", "user", systemdUnitName)
}

func (app *App) service(ctx context.Context, args []string) error {
	usage := errors.New("usage: cag service install [--port N] | uninstall | status")
	if len(args) == 0 {
		return usage
	}
	if app.goos != "darwin" && app.goos != "linux" {
		return errors.New("cag service supports macOS launchd and Linux systemd --user")
	}
	switch args[0] {
	case "install":
		options, err := parseConnectArgs(args[1:])
		if err != nil || options.watch || options.adbArgs != nil {
			return usage
		}
		return app.serviceInstall(ctx, options.port)
	case "uninstall":
		if len(args) != 1 {
			return usage
		}
		return app.serviceUninstall(ctx)
	case "status":
		if len(args) != 1 {
			return usage
		}
		return app.serviceStatus(ctx)
	default:
		return usage
	}
}

func (app *App) launchdDomain() string { return fmt.Sprintf("gui/%d", os.Getuid()) }

func (app *App) launchdTarget() string { return app.launchdDomain() + "/" + serviceLabel }

func (app *App) serviceInstall(ctx context.Context, requestedPort int) error {
	if strings.Contains(app.executable, "go-build") || !filepath.IsAbs(app.executable) {
		return fmt.Errorf("install the cag binary first; %q is not a stable path", app.executable)
	}
	if err := app.ensureConfig(); err != nil {
		return err
	}
	port, err := app.localPort(requestedPort)
	if err != nil {
		return err
	}
	adbPath, adbErr := app.lookPath("adb")
	if adbErr != nil {
		adbPath = ""
	}
	spec := serviceSpec{
		Executable: app.executable,
		ConfigDir:  app.configDir,
		Path:       servicePath(adbPath),
		LogPath:    app.path(serviceLogName),
	}
	var file string
	if app.goos == "darwin" {
		file = app.launchAgentPath()
		if err := writeServiceFile(file, renderLaunchAgent(spec)); err != nil {
			return err
		}
		if err := app.launchdReload(ctx, file); err != nil {
			return err
		}
	} else {
		file = app.systemdUnitPath()
		if err := writeServiceFile(file, renderSystemdUnit(spec)); err != nil {
			return err
		}
		for _, step := range [][]string{
			{"--user", "daemon-reload"},
			{"--user", "enable", systemdUnitName},
			{"--user", "restart", systemdUnitName},
		} {
			if _, err := app.runner.Run(ctx, nil, "systemctl", step...); err != nil {
				return fmt.Errorf("systemctl %s: %w", strings.Join(step, " "), err)
			}
		}
	}
	fmt.Fprintf(app.stdout, "Installed %s: cag connect --watch keeps %s up.\nLog: %s\n",
		file, loopbackAddress(port), app.path(watchLogName))
	if adbPath == "" {
		fmt.Fprintln(app.stdout, "adb was not found on PATH; the service keeps the tunnel but cannot run adb connect.")
	}
	if _, err := os.Stat(app.path("device.json")); err != nil {
		fmt.Fprintln(app.stdout, "No car is paired yet; the service waits for: cag pair XXXX-XXXX")
	}
	return nil
}

func writeServiceFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("create %s: %w", filepath.Dir(path), err)
	}
	if err := writeAtomic(path, []byte(content), 0o644); err != nil {
		return fmt.Errorf("write %s: %w", path, err)
	}
	return nil
}

// launchdReload boots out a loaded copy, waits until launchd lets it go, and
// bootstraps the agent from its file.
func (app *App) launchdReload(ctx context.Context, plist string) error {
	_, _ = app.runner.Run(ctx, nil, "launchctl", "bootout", app.launchdTarget())
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); {
		if _, err := app.runner.Run(ctx, nil, "launchctl", "print", app.launchdTarget()); err != nil {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	_, err := app.runner.Run(ctx, nil, "launchctl", "bootstrap", app.launchdDomain(), plist)
	if err != nil {
		time.Sleep(time.Second)
		if _, err = app.runner.Run(ctx, nil, "launchctl", "bootstrap", app.launchdDomain(), plist); err != nil {
			return fmt.Errorf("launchctl bootstrap %s: %w", plist, err)
		}
	}
	return nil
}

func (app *App) serviceUninstall(ctx context.Context) error {
	var file string
	if app.goos == "darwin" {
		file = app.launchAgentPath()
		_, _ = app.runner.Run(ctx, nil, "launchctl", "bootout", app.launchdTarget())
	} else {
		file = app.systemdUnitPath()
		_, _ = app.runner.Run(ctx, nil, "systemctl", "--user", "disable", "--now", systemdUnitName)
	}
	if err := os.Remove(file); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("remove %s: %w", file, err)
	}
	if app.goos == "linux" {
		_, _ = app.runner.Run(ctx, nil, "systemctl", "--user", "daemon-reload")
	}
	// The stopped watch closes the tunnel it started; this also closes one
	// whose socket is gone or that the watch had adopted.
	closed, err := app.closeAnyMaster(ctx)
	if err != nil {
		return err
	}
	fmt.Fprintf(app.stdout, "Uninstalled %s.\n", file)
	if closed {
		fmt.Fprintln(app.stdout, "Closed the tunnel.")
	}
	return nil
}

func (app *App) serviceStatus(ctx context.Context) error {
	file := app.systemdUnitPath()
	if app.goos == "darwin" {
		file = app.launchAgentPath()
	}
	state := "not installed"
	if _, err := os.Stat(file); err == nil {
		state = app.serviceState(ctx)
	}
	port, err := app.localPort(0)
	if err != nil {
		return err
	}
	fmt.Fprintf(app.stdout, "Service: %s\nFile: %s\nSerial: %s\n", state, file, loopbackAddress(port))
	if last := lastLine(app.path(watchLogName)); last != "" {
		fmt.Fprintf(app.stdout, "Last: %s\n", last)
	}
	return nil
}

func (app *App) serviceState(ctx context.Context) string {
	if app.goos == "linux" {
		output, _ := app.runner.Run(ctx, nil, "systemctl", "--user", "is-active", systemdUnitName)
		if state := strings.TrimSpace(string(output)); state != "" {
			return state
		}
		return "unknown"
	}
	output, err := app.runner.Run(ctx, nil, "launchctl", "print", app.launchdTarget())
	if err != nil {
		return "installed, not loaded"
	}
	state, pid := "loaded", ""
	for scanner := bufio.NewScanner(strings.NewReader(string(output))); scanner.Scan(); {
		key, value, found := strings.Cut(strings.TrimSpace(scanner.Text()), " = ")
		if !found {
			continue
		}
		switch key {
		case "state":
			if state == "loaded" {
				state = value
			}
		case "pid":
			if pid == "" {
				pid = value
			}
		}
	}
	if pid != "" {
		return fmt.Sprintf("%s (pid %s)", state, pid)
	}
	return state
}

func lastLine(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	return lines[len(lines)-1]
}
