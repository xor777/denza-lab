package cag

import (
	"context"
	"errors"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
)

func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

// busyPort holds a listener for the test's lifetime, standing in for another
// program (such as the legacy LAN tunnel) on the port.
func busyPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	return listener.Addr().(*net.TCPAddr).Port
}

// pretendMasterOn leaves a live, answering master of ours on port, as a
// successful ssh -M -f would.
func pretendMasterOn(t *testing.T, app *App, runner *fakeRunner, port int) int {
	t.Helper()
	pid := runner.startFakeMaster(app.tunnelArgs(testBundle("raw", "127.0.0.1"), port))
	t.Cleanup(func() { os.Remove(app.controlSocket()) })
	app.recordMaster(pid, port)
	return pid
}

func TestLocalPortDefaultsTo15555AndRemembersAChoice(t *testing.T) {
	app, _, _ := newTestApp(t, true)
	app.defaultPort = defaultLocalPort
	if port, err := app.localPort(0); err != nil || port != 15555 {
		t.Fatalf("default port = %d, %v; want 15555", port, err)
	}
	if port, err := app.localPort(15600); err != nil || port != 15600 {
		t.Fatalf("requested port = %d, %v", port, err)
	}
	again := newForTest(&fakeRunner{}, app.configDir, app.executable, nil)
	if port, err := again.localPort(0); err != nil || port != 15600 {
		t.Fatalf("remembered port = %d, %v; want 15600", port, err)
	}
	raw, err := os.ReadFile(app.path(settingsName))
	if err != nil || !strings.Contains(string(raw), `"local_port":15600`) {
		t.Fatalf("settings.json = %q, %v", raw, err)
	}
	for _, bad := range []string{"80", "70000", "abc"} {
		if _, err := parseConnectArgs([]string{"--port", bad}); err == nil {
			t.Errorf("port %q was accepted", bad)
		}
	}
	options, err := parseConnectArgs([]string{"--port=15601", "--", "adb", "shell"})
	if err != nil || options.port != 15601 || strings.Join(options.adbArgs, " ") != "shell" {
		t.Fatalf("parseConnectArgs = %+v, %v", options, err)
	}
	if _, err := parseConnectArgs([]string{"--watch", "--", "adb", "shell"}); err == nil {
		t.Fatal("--watch with an adb command was accepted")
	}
}

func TestLocalPortFreeSeesAnotherListener(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if localPortFree(port) {
		t.Fatal("a held port was reported free")
	}
	listener.Close()
	if !localPortFree(port) {
		t.Fatal("a released port was reported busy")
	}
}

func TestConnectFailsFastWhenAnotherProgramHoldsThePort(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := busyPort(t)
	err := app.Run(context.Background(), []string{"connect", "--port", strconv.Itoa(port)})
	var busy *portBusyError
	if !errors.As(err, &busy) || !strings.Contains(err.Error(), loopbackAddress(port)) || strings.Contains(err.Error(), "\n") {
		t.Fatalf("want a one-line busy-port error naming %s, got %v", loopbackAddress(port), err)
	}
	if len(runner.detachedCalls) != 0 {
		t.Fatalf("ssh was started on a busy port: %q", runner.detachedCalls)
	}
}

func TestConnectReusesOurMasterOnTheChosenPort(t *testing.T) {
	app, runner, output := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := freePort(t)
	pretendMasterOn(t, app, runner, port)
	if err := app.Run(context.Background(), []string{"connect", "--port", strconv.Itoa(port)}); err != nil {
		t.Fatal(err)
	}
	if len(runner.detachedCalls) != 0 {
		t.Fatalf("a live master was replaced: %q", runner.detachedCalls)
	}
	if want := "Connected. Serial: " + loopbackAddress(port) + " (raw)."; !strings.Contains(output.String(), want) {
		t.Fatalf("output %q lacks %q", output, want)
	}
}

func TestConnectMovesOurMasterToTheRequestedPort(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	oldPort, newPort := freePort(t), freePort(t)
	pretendMasterOn(t, app, runner, oldPort)
	if err := app.Run(context.Background(), []string{"connect", "--port", strconv.Itoa(newPort)}); err != nil {
		t.Fatal(err)
	}
	exited := false
	for _, call := range runner.runCalls {
		if call[0] == "ssh" && argAfter(call, "-O") == "exit" {
			exited = true
		}
	}
	if !exited {
		t.Fatalf("the master on the old port was not closed: %q", runner.runCalls)
	}
	if len(runner.detachedCalls) != 1 || !strings.HasPrefix(argAfter(runner.detachedCalls[0], "-L"), loopbackAddress(newPort)+":") {
		t.Fatalf("the master was not reopened on %d: %q", newPort, runner.detachedCalls)
	}
	if port, _ := app.readLocalPort(); port != newPort {
		t.Fatalf("local-port = %d, want %d", port, newPort)
	}
	if port, _ := app.localPort(0); port != newPort {
		t.Fatalf("remembered port = %d, want %d", port, newPort)
	}
}

func TestStatusShowsTheSerialAndAPortHeldByAnotherProgram(t *testing.T) {
	app, _, output := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := busyPort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	if err := app.Run(context.Background(), []string{"status"}); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"State: disconnected",
		"Serial: " + loopbackAddress(port),
		"Port: " + loopbackAddress(port) + " is in use by another program",
	} {
		if !strings.Contains(output.String(), want) {
			t.Errorf("status lacks %q:\n%s", want, output)
		}
	}
}

func TestStatusShowsTheMasterPIDAndTheSerialState(t *testing.T) {
	app, runner, output := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := freePort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	pid := pretendMasterOn(t, app, runner, port)
	runner.adbDevicesOutput = "List of devices attached\n" + loopbackAddress(port) + "\tdevice\n"
	if err := app.Run(context.Background(), []string{"status"}); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"State: connected (pid " + strconv.Itoa(pid) + ")",
		"Serial: " + loopbackAddress(port) + " (device)",
	} {
		if !strings.Contains(output.String(), want) {
			t.Errorf("status lacks %q:\n%s", want, output)
		}
	}
}
