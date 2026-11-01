package cag

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// The macOS config dir is "~/Library/Application Support/cag".
func newSpacedTestApp(t *testing.T, executable string) (*App, *fakeRunner, *bytes.Buffer) {
	t.Helper()
	config := filepath.Join(t.TempDir(), "Application Support", "cag")
	runner := &fakeRunner{configDir: config, pairConfirmation: true, procs: newFakeProcesses()}
	output := &bytes.Buffer{}
	app := newForTest(runner, config, executable, output)
	app.processes = runner.procs
	return app, runner, output
}

func testBundle(mode, host string) DeviceBundle {
	return DeviceBundle{
		DeviceID:        "0123456789abcdef",
		DeviceLabel:     "Test Car",
		Enabled:         true,
		EndpointMode:    mode,
		EndpointHost:    host,
		InnerHostKey:    testInnerKey,
		RelayDevicePort: 20_000,
		RelayHost:       relayHost,
		RelaySSHPort:    relaySSHPort,
	}
}

func writeTestBundle(t *testing.T, app *App, bundle DeviceBundle) {
	t.Helper()
	if err := app.ensureConfig(); err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(bundle)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(app.path("device.json"), raw, 0o600); err != nil {
		t.Fatal(err)
	}
}

func containsArg(args []string, want string) bool {
	for _, arg := range args {
		if arg == want {
			return true
		}
	}
	return false
}

func argAfter(args []string, flag string) string {
	for index := 0; index+1 < len(args); index++ {
		if args[index] == flag {
			return args[index+1]
		}
	}
	return ""
}

func TestSSHArgumentsQuoteAConfigDirWithSpaces(t *testing.T) {
	app, runner, _ := newSpacedTestApp(t, "/opt/home brew/bin/cag")
	if err := app.Run(context.Background(), []string{"pair", "2345-6789"}); err != nil {
		t.Fatal(err)
	}
	if err := app.Run(context.Background(), []string{"connect"}); err != nil {
		t.Fatal(err)
	}
	var calls [][]string
	for _, args := range runner.sshArgs {
		if argAfter(args, "-O") == "" { // mux checks only ask the master
			calls = append(calls, args)
		}
	}
	calls = append(calls, runner.detachedCalls[0][1:])
	if len(calls) != 3 {
		t.Fatalf("expected pair submit, pair-complete and tunnel; got %d calls", len(calls))
	}
	knownHosts := `UserKnownHostsFile="` + app.path("known_hosts") + `"`
	for index, args := range calls {
		if len(args) < 2 || args[0] != "-F" || args[1] != "none" {
			t.Fatalf("call %d does not start with -F none: %q", index, args)
		}
		for _, arg := range args {
			if strings.HasPrefix(arg, "UserKnownHostsFile=") && !strings.HasPrefix(arg, `UserKnownHostsFile="`) {
				t.Fatalf("call %d passes an unquoted known-hosts path: %q", index, arg)
			}
			if strings.HasPrefix(arg, "ControlPath=") {
				t.Fatalf("call %d passes ControlPath through -o: %q", index, arg)
			}
		}
	}
	if !containsArg(calls[0], knownHosts) || !containsArg(calls[2], knownHosts) {
		t.Fatalf("relay and tunnel calls do not quote %s: %q / %q", knownHosts, calls[0], calls[2])
	}
	if !containsArg(calls[1], "ProxyCommand='/opt/home brew/bin/cag' __proxy 20000 identity") {
		t.Fatalf("pair-complete ProxyCommand is wrong: %q", calls[1])
	}
	if argAfter(calls[2], "-S") != app.controlSocket() {
		t.Fatalf("tunnel control socket is wrong: %q", calls[2])
	}
}

func TestSSHPathOptionEscapesQuotesBackslashesAndPercent(t *testing.T) {
	got := sshPathOption("UserKnownHostsFile", `/a b/"q"\x/100%/known_hosts`)
	want := `UserKnownHostsFile="/a b/\"q\"\\x/100%%/known_hosts"`
	if got != want {
		t.Fatalf("sshPathOption() = %s, want %s", got, want)
	}
}

// Real OpenSSH must read the arguments back as the same paths.
func TestOpenSSHParsesTheTunnelArgumentsWithSpaces(t *testing.T) {
	if _, err := exec.LookPath("ssh"); err != nil {
		t.Skip("ssh is not installed")
	}
	// ssh -G joins a split value with one space, so two spaces in the path
	// tell a correctly parsed value from one ssh split into several files.
	config := filepath.Join(t.TempDir(), "Application  Support", "cag")
	app := newForTest(&fakeRunner{configDir: config}, config, "/opt/home brew/bin/cag", &bytes.Buffer{})
	if err := app.ensureConfig(); err != nil {
		t.Fatal(err)
	}
	args := append([]string{"-G"}, app.tunnelArgs(testBundle("smart", "127.0.0.1"), 23_456)...)
	output, err := exec.Command("ssh", args...).CombinedOutput()
	if err != nil {
		t.Fatalf("ssh rejected the tunnel arguments: %v\n%s", err, output)
	}
	parsed := map[string]string{}
	for _, line := range strings.Split(string(output), "\n") {
		if key, value, ok := strings.Cut(line, " "); ok {
			parsed[key] = value
		}
	}
	checks := map[string]string{
		"controlpath":        app.controlSocket(),
		"userknownhostsfile": app.path("known_hosts"),
		"hostkeyalias":       "cag-device-0123456789abcdef",
		"proxycommand":       "'/opt/home brew/bin/cag' __proxy 20000 identity",
	}
	for key, want := range checks {
		if parsed[key] != want {
			t.Errorf("ssh -G %s = %q, want %q", key, parsed[key], want)
		}
	}
}

func TestAskpassModeNeverAnswersTheProxy(t *testing.T) {
	env := map[string]string{"CAG_ASKPASS_MODE": "1", "CAG_ASKPASS_SECRET": "2345-6789"}
	getenv := func(key string) string { return env[key] }
	if _, ok := AskpassSecret([]string{"__proxy", "20000", "identity"}, getenv); ok {
		t.Fatal("the proxy invocation was treated as askpass")
	}
	if secret, ok := AskpassSecret([]string{"cag@127.0.0.1's password: "}, getenv); !ok || secret != "2345-6789" {
		t.Fatalf("askpass prompt was not answered: %q %v", secret, ok)
	}
	if _, ok := AskpassSecret([]string{"status"}, func(string) string { return "" }); ok {
		t.Fatal("a normal invocation was treated as askpass")
	}
}

func TestProxyUsesTheRequestedIdentityAndDropsAskpass(t *testing.T) {
	t.Setenv("CAG_ASKPASS_MODE", "1")
	t.Setenv("CAG_ASKPASS_SECRET", "2345-6789")
	app, runner, _ := newTestApp(t, true)
	if err := app.Run(context.Background(), []string{"__proxy", "20000", "identity.next"}); err != nil {
		t.Fatal(err)
	}
	if os.Getenv("CAG_ASKPASS_MODE") != "" || os.Getenv("CAG_ASKPASS_SECRET") != "" {
		t.Fatal("the proxy kept the askpass environment for its ssh")
	}
	args := runner.pipeCalls[0]
	if args[0] != "ssh" || argAfter(args, "-i") != app.path("identity.next") ||
		argAfter(args, "-W") != "127.0.0.1:20000" || args[len(args)-1] != "cag-client@adbgw.ru" {
		t.Fatalf("unexpected proxy ssh: %q", args)
	}
	if err := app.Run(context.Background(), []string{"__proxy", "20000"}); err != nil {
		t.Fatal(err)
	}
	if argAfter(runner.pipeCalls[1], "-i") != app.path("identity") {
		t.Fatalf("two-argument proxy did not default to identity: %q", runner.pipeCalls[1])
	}
	if err := app.Run(context.Background(), []string{"__proxy", "20000", "../identity"}); err == nil {
		t.Fatal("an arbitrary proxy identity was accepted")
	}
	inner := app.innerArgsWithIdentity(testBundle("smart", "127.0.0.1"), app.path("known_hosts"), "identity.next", false, "pair-complete")
	if !containsArg(inner, "ProxyCommand='/usr/local/bin/cag' __proxy 20000 identity.next") {
		t.Fatalf("inner ssh does not hand its identity to the proxy: %q", inner)
	}
}

func TestDetachedDoesNotWaitForBackgroundChildren(t *testing.T) {
	runner := systemRunner{waitDelay: 200 * time.Millisecond}
	logPath := filepath.Join(t.TempDir(), "tunnel.log")
	started := time.Now()
	if err := runner.Detached(context.Background(), nil, logPath, "sh", "-c", "sleep 10 & echo $!"); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("Detached waited %s for a background child", elapsed)
	}
	killLoggedPID(t, logPath)
}

func TestRunReturnsWhenABackgroundChildKeepsThePipe(t *testing.T) {
	runner := systemRunner{waitDelay: 200 * time.Millisecond}
	started := time.Now()
	output, err := runner.Run(context.Background(), nil, "sh", "-c", "sleep 10 & echo $!")
	if err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("Run waited %s for a background child", elapsed)
	}
	if pid, err := strconv.Atoi(strings.TrimSpace(string(output))); err == nil {
		if process, err := os.FindProcess(pid); err == nil {
			process.Kill()
		}
	}
}

func killLoggedPID(t *testing.T, path string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil {
		t.Fatalf("background pid was not logged: %q", data)
	}
	if process, err := os.FindProcess(pid); err == nil {
		process.Kill()
	}
}

func TestTunnelFailsFastWhenSshCannotBindThePort(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("smart", "127.0.0.1"))
	runner.detachedFailures = []string{
		"bind [127.0.0.1]:41000: Address already in use\r\n" +
			"channel_setup_fwd_listener_tcpip: cannot listen to port: 41000\r\n" +
			"Could not request local forwarding.\r\n",
	}
	err := app.Run(context.Background(), []string{"connect"})
	var busy *portBusyError
	if !errors.As(err, &busy) || busy.port != app.defaultPort {
		t.Fatalf("a bind failure was not reported as the busy port: %v", err)
	}
	if len(runner.detachedCalls) != 1 {
		t.Fatalf("a bind failure fell back to another attempt: %d calls", len(runner.detachedCalls))
	}

	app, runner, _ = newTestApp(t, true)
	writeTestBundle(t, app, testBundle("smart", "127.0.0.1"))
	runner.detachedFailures = []string{"Host key verification failed.\r\n"}
	err = app.Run(context.Background(), []string{"connect"})
	if err == nil || !strings.Contains(err.Error(), "Host key verification failed") {
		t.Fatalf("a non-port failure was not reported: %v", err)
	}
	if len(runner.detachedCalls) != 1 {
		t.Fatalf("a non-port failure was retried %d times", len(runner.detachedCalls))
	}
}

func TestRawConnectRequiresAdbToReportTheConnection(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	runner.adbConnectOutput = "failed to connect to '127.0.0.1:41000': Connection refused\n"
	err := app.Run(context.Background(), []string{"connect", "--", "adb", "devices"})
	if err == nil || !strings.Contains(err.Error(), "could not reach the car's ADB") {
		t.Fatalf("a failed adb connect was not reported: %v", err)
	}
	if len(runner.interactiveCalls) != 0 {
		t.Fatalf("adb ran after a failed connect: %q", runner.interactiveCalls)
	}

	for _, reply := range []string{"connected to 127.0.0.1:41000\n", "already connected to 127.0.0.1:41000\n"} {
		app, runner, _ = newTestApp(t, true)
		writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
		runner.adbConnectOutput = reply
		if err := app.Run(context.Background(), []string{"connect", "--", "adb", "devices"}); err != nil {
			t.Fatalf("%q: %v", reply, err)
		}
		call := runner.interactiveCalls[0]
		if call[0] != "adb" || call[1] != "-s" || call[2] != loopbackAddress(app.defaultPort) || call[3] != "devices" {
			t.Fatalf("unexpected adb call: %q", call)
		}
	}
}

func TestTunnelToleratesAStaleOrMissingEndpointHost(t *testing.T) {
	app, _, _ := newTestApp(t, true)
	cases := []struct {
		bundle DeviceBundle
		want   string
	}{
		{testBundle("smart", ""), "127.0.0.1:41000:127.0.0.1:5037"},
		{testBundle("raw", "not-an-ip"), "127.0.0.1:41000:127.0.0.1:5555"},
		{testBundle("raw", "192.168.1.5"), "127.0.0.1:41000:192.168.1.5:5555"},
	}
	for _, test := range cases {
		if got := argAfter(app.tunnelArgs(test.bundle, 41_000), "-L"); got != test.want {
			t.Errorf("forward for %q = %q, want %q", test.bundle.EndpointHost, got, test.want)
		}
	}
}

func TestControlSocketStaysShortEnoughForUnixSockets(t *testing.T) {
	short := &App{configDir: "/Users/me/Library/Application Support/cag"}
	if got := short.controlSocket(); got != filepath.Join(short.configDir, "session.sock") {
		t.Fatalf("short config dir should keep the socket inside it, got %q", got)
	}
	long := &App{configDir: "/private/tmp/" + strings.Repeat("deep-directory/", 8) + "cag state"}
	got := long.controlSocket()
	if len(got) > maxControlSocketPath {
		t.Fatalf("socket path %q is %d bytes, over %d", got, len(got), maxControlSocketPath)
	}
	other := &App{configDir: long.configDir + "-2"}
	if other.controlSocket() == got {
		t.Fatalf("two config dirs share one control socket %q", got)
	}
}
