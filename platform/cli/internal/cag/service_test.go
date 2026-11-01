package cag

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func testSpec() serviceSpec {
	return serviceSpec{
		Executable: "/opt/homebrew/bin/cag",
		ConfigDir:  "/Users/me/Library/Application Support/cag",
		Path:       servicePath("/Users/me/Library/Android/sdk/platform-tools/adb"),
		LogPath:    "/Users/me/Library/Application Support/cag/service.log",
	}
}

func TestServicePathPutsTheInstallTimeAdbFirst(t *testing.T) {
	want := "/Users/me/Library/Android/sdk/platform-tools:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
	if got := servicePath("/Users/me/Library/Android/sdk/platform-tools/adb"); got != want {
		t.Fatalf("servicePath = %s", got)
	}
	if got := servicePath("/opt/homebrew/bin/adb"); got != "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin" {
		t.Fatalf("servicePath did not drop the duplicate: %s", got)
	}
}

func TestLaunchAgentRendering(t *testing.T) {
	spec := testSpec()
	spec.Executable = "/Users/me/bin & tools/cag"
	plist := renderLaunchAgent(spec)
	decoder := xml.NewDecoder(strings.NewReader(plist))
	decoder.Strict = true
	var strings_ []string
	for {
		token, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("the plist is not well-formed XML: %v\n%s", err, plist)
		}
		if data, ok := token.(xml.CharData); ok && strings.TrimSpace(string(data)) != "" {
			strings_ = append(strings_, string(data))
		}
	}
	joined := strings.Join(strings_, "|")
	for _, want := range []string{
		"Label|ru.adbgw.cag",
		"ProgramArguments|/Users/me/bin & tools/cag|connect|--watch",
		"CAG_CONFIG_DIR|/Users/me/Library/Application Support/cag",
		"PATH|/Users/me/Library/Android/sdk/platform-tools:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin",
		"StandardOutPath|/Users/me/Library/Application Support/cag/service.log",
		"StandardErrorPath|/Users/me/Library/Application Support/cag/service.log",
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("plist lacks %q: %s", want, joined)
		}
	}
	for _, want := range []string{"<key>RunAtLoad</key>\n\t<true/>", "<key>KeepAlive</key>\n\t<true/>"} {
		if !strings.Contains(plist, want) {
			t.Errorf("plist lacks %q", want)
		}
	}
	if plutil, err := exec.LookPath("plutil"); err == nil {
		file := filepath.Join(t.TempDir(), "agent.plist")
		os.WriteFile(file, []byte(plist), 0o644)
		if output, err := exec.Command(plutil, "-lint", file).CombinedOutput(); err != nil {
			t.Fatalf("plutil -lint: %v %s", err, output)
		}
	}
}

func TestSystemdUnitRendering(t *testing.T) {
	spec := testSpec()
	spec.Executable = `/home/me/bin/c"a$g%`
	unit := renderSystemdUnit(spec)
	for _, want := range []string{
		`ExecStart="/home/me/bin/c\"a$$g%%" connect --watch`,
		`Environment="CAG_CONFIG_DIR=/Users/me/Library/Application Support/cag"`,
		`Environment="PATH=/Users/me/Library/Android/sdk/platform-tools:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"`,
		"Restart=always",
		"KillMode=mixed",
		"StandardOutput=append:/Users/me/Library/Application Support/cag/service.log",
		"WantedBy=default.target",
	} {
		if !strings.Contains(unit, want) {
			t.Errorf("unit lacks %q:\n%s", want, unit)
		}
	}
}

func newServiceTestApp(t *testing.T, goos string) (*App, *fakeRunner, *bytes.Buffer) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", "")
	app, runner, output := newTestApp(t, true)
	app.goos = goos
	app.lookPath = func(name string) (string, error) { return "/opt/android/platform-tools/" + name, nil }
	return app, runner, output
}

func callIndex(calls [][]string, want ...string) int {
	for index, call := range calls {
		if len(call) >= len(want) && strings.Join(call[:len(want)], " ") == strings.Join(want, " ") {
			return index
		}
	}
	return -1
}

func TestServiceInstallOnMacOSBootsOutThenBootstraps(t *testing.T) {
	app, runner, _ := newServiceTestApp(t, "darwin")
	port := freePort(t)
	if err := app.Run(context.Background(), []string{"service", "install", "--port", fmt.Sprint(port)}); err != nil {
		t.Fatal(err)
	}
	plist, err := os.ReadFile(app.launchAgentPath())
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(app.homeDir, "Library", "LaunchAgents", "ru.adbgw.cag.plist"); app.launchAgentPath() != want {
		t.Fatalf("plist path = %s", app.launchAgentPath())
	}
	if !strings.Contains(string(plist), "<string>/usr/local/bin/cag</string>") ||
		!strings.Contains(string(plist), "<string>/opt/android/platform-tools:/opt/homebrew/bin:") {
		t.Fatalf("unexpected plist:\n%s", plist)
	}
	target := fmt.Sprintf("gui/%d/ru.adbgw.cag", os.Getuid())
	bootout := callIndex(runner.runCalls, "launchctl", "bootout", target)
	bootstrap := callIndex(runner.runCalls, "launchctl", "bootstrap", fmt.Sprintf("gui/%d", os.Getuid()), app.launchAgentPath())
	if bootout < 0 || bootstrap < 0 || bootout > bootstrap {
		t.Fatalf("want bootout before bootstrap: %q", runner.runCalls)
	}
	if remembered, _ := app.localPort(0); remembered != port {
		t.Fatalf("install did not remember port %d: %d", port, remembered)
	}

	runner.runCalls = nil
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	pid := pretendMasterOn(t, app, runner, port)
	os.Remove(app.controlSocket()) // uninstall must close it even without its socket
	if err := app.Run(context.Background(), []string{"service", "uninstall"}); err != nil {
		t.Fatal(err)
	}
	if runner.procs.alive(pid) {
		t.Fatal("uninstall left the tunnel running")
	}
	if callIndex(runner.runCalls, "launchctl", "bootout", target) < 0 {
		t.Fatalf("uninstall did not boot the agent out: %q", runner.runCalls)
	}
	if _, err := os.Stat(app.launchAgentPath()); !os.IsNotExist(err) {
		t.Fatal("uninstall left the plist")
	}
}

func TestServiceInstallOnLinuxEnablesAUserUnit(t *testing.T) {
	app, runner, _ := newServiceTestApp(t, "linux")
	if err := app.Run(context.Background(), []string{"service", "install"}); err != nil {
		t.Fatal(err)
	}
	unitPath := filepath.Join(app.homeDir, ".config", "systemd", "user", "cag.service")
	unit, err := os.ReadFile(unitPath)
	if err != nil || !strings.Contains(string(unit), `ExecStart="/usr/local/bin/cag" connect --watch`) {
		t.Fatalf("unit %s: %v\n%s", unitPath, err, unit)
	}
	reload := callIndex(runner.runCalls, "systemctl", "--user", "daemon-reload")
	enable := callIndex(runner.runCalls, "systemctl", "--user", "enable", "cag.service")
	restart := callIndex(runner.runCalls, "systemctl", "--user", "restart", "cag.service")
	if reload < 0 || enable < reload || restart < enable {
		t.Fatalf("unexpected systemctl sequence: %q", runner.runCalls)
	}
	if err := app.Run(context.Background(), []string{"service", "uninstall"}); err != nil {
		t.Fatal(err)
	}
	if callIndex(runner.runCalls, "systemctl", "--user", "disable", "--now", "cag.service") < 0 {
		t.Fatalf("uninstall did not stop the unit: %q", runner.runCalls)
	}
	if _, err := os.Stat(unitPath); !os.IsNotExist(err) {
		t.Fatal("uninstall left the unit")
	}
}

func TestServiceStatusAndUnstableBinary(t *testing.T) {
	app, runner, output := newServiceTestApp(t, "darwin")
	if err := app.Run(context.Background(), []string{"service", "status"}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output.String(), "Service: not installed") {
		t.Fatalf("status = %q", output)
	}
	if err := app.Run(context.Background(), []string{"service", "install"}); err != nil {
		t.Fatal(err)
	}
	runner.launchctlLoaded = true
	output.Reset()
	if err := app.Run(context.Background(), []string{"service", "status"}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output.String(), "Service: running (pid 42)") ||
		!strings.Contains(output.String(), "Serial: "+loopbackAddress(app.defaultPort)) {
		t.Fatalf("status = %q", output)
	}

	app.executable = "/var/folders/x/go-build123/b001/exe/cag"
	if err := app.Run(context.Background(), []string{"service", "install"}); err == nil {
		t.Fatal("a go run binary was installed as the service")
	}
}
