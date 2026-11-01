package cag

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

const testInnerKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBERERERERERERERERERERERERERERERERERERERERE="

type fakeRunner struct {
	configDir        string
	pairConfirmation bool
	pairSubmits      int
	sshCalls         []string
	sshArgs          [][]string
	adbConnectOutput string
	// detachedFailures[i] is written to the log of the i-th Detached call,
	// which then fails; an empty or missing entry succeeds.
	detachedFailures []string
	detachedCalls    [][]string
	pipeCalls        [][]string
	interactiveCalls [][]string
	// runCalls records every Run call other than ssh-keygen, name first.
	runCalls         [][]string
	adbDevicesOutput string
	launchctlLoaded  bool
	onDetached       func(args []string)
	// procs models the ssh masters that Detached leaves behind.
	procs     *fakeProcesses
	masterPID int
	// unresponsivePID is a master that runs but no longer answers -O check
	// or -O exit, as after its socket broke.
	unresponsivePID int
}

// fakeProcesses is a process table for the tests.
type fakeProcesses struct {
	mu       sync.Mutex
	commands map[int]string
	next     int
	signals  []string
}

func newFakeProcesses() *fakeProcesses {
	return &fakeProcesses{commands: map[int]string{}, next: 4000}
}

func (procs *fakeProcesses) start(command string) int {
	procs.mu.Lock()
	defer procs.mu.Unlock()
	procs.next++
	procs.commands[procs.next] = command
	return procs.next
}

func (procs *fakeProcesses) exit(pid int) {
	procs.mu.Lock()
	defer procs.mu.Unlock()
	delete(procs.commands, pid)
}

func (procs *fakeProcesses) alive(pid int) bool { return procs.Command(pid) != "" }

func (procs *fakeProcesses) Command(pid int) string {
	procs.mu.Lock()
	defer procs.mu.Unlock()
	return procs.commands[pid]
}

func (procs *fakeProcesses) List() []processInfo {
	procs.mu.Lock()
	defer procs.mu.Unlock()
	var list []processInfo
	for pid, command := range procs.commands {
		list = append(list, processInfo{PID: pid, Command: command})
	}
	return list
}

func (procs *fakeProcesses) Signal(pid int, sig syscall.Signal) error {
	procs.mu.Lock()
	defer procs.mu.Unlock()
	if _, ok := procs.commands[pid]; !ok {
		return syscall.ESRCH
	}
	procs.signals = append(procs.signals, fmt.Sprintf("%d %s", pid, sig))
	if sig == syscall.SIGTERM || sig == syscall.SIGKILL {
		delete(procs.commands, pid)
	}
	return nil
}

func (procs *fakeProcesses) signalled() []string {
	procs.mu.Lock()
	defer procs.mu.Unlock()
	return append([]string{}, procs.signals...)
}

// fakeMux answers ssh -O check and -O exit the way a master would.
func (runner *fakeRunner) fakeMux(args []string) ([]byte, error) {
	socket := argAfter(args, "-S")
	pid := runner.masterPID
	if _, err := os.Stat(socket); err != nil || pid == 0 || !runner.procs.alive(pid) || pid == runner.unresponsivePID {
		return nil, errors.New("Control socket connect(" + socket + "): No such file or directory")
	}
	switch argAfter(args, "-O") {
	case "check":
		return []byte(fmt.Sprintf("Master running (pid=%d)\r\n", pid)), nil
	case "exit":
		runner.procs.exit(pid)
		os.Remove(socket)
		return []byte("Exit request sent.\r\n"), nil
	}
	return nil, errors.New("unexpected mux command")
}

// startFakeMaster is what ssh -M -f leaves: a process and its socket.
func (runner *fakeRunner) startFakeMaster(args []string) int {
	socket := argAfter(args, "-S")
	os.WriteFile(socket, nil, 0o600)
	runner.masterPID = runner.procs.start("ssh " + strings.Join(args, " "))
	return runner.masterPID
}

func (runner *fakeRunner) Run(ctx context.Context, env map[string]string, name string, args ...string) ([]byte, error) {
	if name == "ssh-keygen" {
		return exec.CommandContext(ctx, name, args...).CombinedOutput()
	}
	runner.runCalls = append(runner.runCalls, append([]string{name}, args...))
	switch name {
	case "adb":
		if len(args) > 0 && args[0] == "devices" {
			return []byte(runner.adbDevicesOutput), nil
		}
		return []byte(runner.adbConnectOutput), nil
	case "launchctl":
		if len(args) > 0 && args[0] == "print" {
			if runner.launchctlLoaded {
				return []byte("gui/501/ru.adbgw.cag = {\n\tstate = running\n\tpid = 42\n}\n"), nil
			}
			return nil, errors.New("exit status 113")
		}
		return nil, nil
	case "systemctl":
		if containsArg(args, "is-active") {
			return []byte("active\n"), nil
		}
		return nil, nil
	case "ssh":
	default:
		return nil, nil
	}
	joined := strings.Join(args, " ")
	runner.sshCalls = append(runner.sshCalls, joined)
	runner.sshArgs = append(runner.sshArgs, args)
	if strings.Contains(joined, "cag-pair@adbgw.ru") {
		runner.pairSubmits++
		fields := strings.Fields(args[len(args)-1])
		if len(fields) != 3 {
			return nil, errors.New("invalid fake pair command")
		}
		payloadRaw, err := base64.RawURLEncoding.DecodeString(fields[2])
		if err != nil {
			return nil, err
		}
		var payload map[string]string
		if err := json.Unmarshal(payloadRaw, &payload); err != nil {
			return nil, err
		}
		identityPublic := filepath.Join(runner.configDir, "identity.pub")
		if staged, readErr := os.ReadFile(filepath.Join(runner.configDir, "identity.next.pub")); readErr == nil &&
			strings.TrimSpace(string(staged)) == payload["public_key"] {
			identityPublic = filepath.Join(runner.configDir, "identity.next.pub")
		}
		output, err := exec.CommandContext(ctx, "ssh-keygen", "-lf", identityPublic, "-E", "sha256").Output()
		if err != nil {
			return nil, err
		}
		fingerprint := strings.Fields(string(output))[1]
		bundle := DeviceBundle{
			ClientFingerprint: fingerprint,
			ClientLabel:       "Test Mac",
			DeviceID:          "0123456789abcdef",
			DeviceLabel:       "Test Car",
			Enabled:           true,
			EndpointMode:      "smart",
			EndpointHost:      "127.0.0.1",
			InnerHostKey:      testInnerKey,
			PairingExpiresAt:  time.Now().Add(10 * time.Minute).Unix(),
			RelayDevicePort:   20_000,
			RelayHost:         relayHost,
			RelaySSHPort:      relaySSHPort,
		}
		raw, _ := json.Marshal(bundle)
		return []byte("OK " + base64.RawURLEncoding.EncodeToString(raw) + "\n"), nil
	}
	if strings.Contains(joined, "pair-complete") {
		if runner.pairConfirmation {
			return []byte("OK paired\n"), nil
		}
		return nil, errors.New("confirmation failed")
	}
	if argAfter(args, "-O") != "" {
		return runner.fakeMux(args)
	}
	return nil, nil
}

func (runner *fakeRunner) Interactive(_ context.Context, _ map[string]string, name string, args ...string) error {
	runner.interactiveCalls = append(runner.interactiveCalls, append([]string{name}, args...))
	return nil
}

func (runner *fakeRunner) Detached(_ context.Context, _ map[string]string, logPath string, name string, args ...string) error {
	index := len(runner.detachedCalls)
	runner.detachedCalls = append(runner.detachedCalls, append([]string{name}, args...))
	if runner.onDetached != nil {
		runner.onDetached(args)
	}
	failure := ""
	if index < len(runner.detachedFailures) {
		failure = runner.detachedFailures[index]
	}
	if err := os.WriteFile(logPath, []byte(failure), 0o600); err != nil {
		return err
	}
	if failure != "" {
		return errors.New("exit status 255")
	}
	if containsArg(args, "-M") {
		runner.startFakeMaster(args)
	}
	return nil
}

func (runner *fakeRunner) Pipe(_ context.Context, _ map[string]string, _ string, name string, args ...string) error {
	runner.pipeCalls = append(runner.pipeCalls, append([]string{name}, args...))
	return nil
}

func newTestApp(t *testing.T, confirmation bool) (*App, *fakeRunner, *bytes.Buffer) {
	t.Helper()
	config := t.TempDir()
	runner := &fakeRunner{configDir: config, pairConfirmation: confirmation, procs: newFakeProcesses()}
	output := &bytes.Buffer{}
	app := newForTest(runner, config, "/usr/local/bin/cag", output)
	app.processes = runner.procs
	return app, runner, output
}

func TestNormalizeCode(t *testing.T) {
	code, err := normalizeCode("2345 6789")
	if err != nil || code != "2345-6789" {
		t.Fatalf("normalizeCode() = %q, %v", code, err)
	}
	if _, err := normalizeCode("O0II-1111"); err == nil {
		t.Fatal("ambiguous code characters were accepted")
	}
}

func TestPairPinsRelayAndDeviceAfterConfirmation(t *testing.T) {
	app, runner, output := newTestApp(t, true)
	if err := app.Run(context.Background(), []string{"pair", "2345-6789"}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output.String(), "Paired with Test Car") {
		t.Fatalf("unexpected output: %s", output.String())
	}
	knownHosts, err := os.ReadFile(app.path("known_hosts"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(knownHosts), relayHostKeys[0]) || !strings.Contains(string(knownHosts), innerAlias("0123456789abcdef")+" "+testInnerKey) {
		t.Fatalf("host keys were not pinned: %s", knownHosts)
	}
	if len(runner.sshCalls) != 2 || !strings.Contains(runner.sshCalls[0], "cag-pair@adbgw.ru") || !strings.Contains(runner.sshCalls[1], "pair-complete") {
		t.Fatalf("unexpected SSH calls: %#v", runner.sshCalls)
	}
}

func TestPairRetryUsesPendingBundleWithoutSecondSubmit(t *testing.T) {
	app, runner, _ := newTestApp(t, false)
	if err := app.Run(context.Background(), []string{"pair", "2345-6789"}); err == nil {
		t.Fatal("pairing unexpectedly succeeded")
	}
	pending, err := os.ReadFile(app.path("pending-pair.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(pending, []byte("2345-6789")) {
		t.Fatal("pending state stored the pairing code")
	}
	runner.pairConfirmation = true
	if err := app.Run(context.Background(), []string{"pair", "2345-6789"}); err != nil {
		t.Fatal(err)
	}
	if runner.pairSubmits != 1 {
		t.Fatalf("pair-submit ran %d times", runner.pairSubmits)
	}
	if _, err := os.Stat(app.path("pending-pair.json")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("pending pairing was not removed")
	}
}

func TestNewKeyIsPromotedOnlyAfterConfirmation(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	if err := app.Run(context.Background(), []string{"pair", "2345-6789"}); err != nil {
		t.Fatal(err)
	}
	oldIdentity, err := os.ReadFile(app.path("identity"))
	if err != nil {
		t.Fatal(err)
	}
	runner.pairConfirmation = false
	if err := app.Run(context.Background(), []string{"pair", "--new-key", "3456-789A"}); err == nil {
		t.Fatal("replacement unexpectedly succeeded")
	}
	stillActive, _ := os.ReadFile(app.path("identity"))
	if !bytes.Equal(oldIdentity, stillActive) || !fileNonempty(app.path("identity.next")) {
		t.Fatal("staged replacement modified the active identity")
	}
	runner.pairConfirmation = true
	if err := app.Run(context.Background(), []string{"pair", "3456-789A"}); err != nil {
		t.Fatal(err)
	}
	newIdentity, _ := os.ReadFile(app.path("identity"))
	if bytes.Equal(oldIdentity, newIdentity) {
		t.Fatal("confirmed staged identity was not promoted")
	}
	if fileNonempty(app.path("identity.next")) {
		t.Fatal("staged identity was not cleaned up")
	}
}

func TestFailedReplacementKeepsPreviousBundleAndHostKey(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	if err := app.Run(context.Background(), []string{"pair", "2345-6789"}); err != nil {
		t.Fatal(err)
	}
	previousBundle, _ := os.ReadFile(app.path("device.json"))
	previousHosts, _ := os.ReadFile(app.path("known_hosts"))
	runner.pairConfirmation = false

	if err := app.Run(context.Background(), []string{"pair", "3456-789A"}); err == nil {
		t.Fatal("failed replacement unexpectedly succeeded")
	}
	currentBundle, _ := os.ReadFile(app.path("device.json"))
	currentHosts, _ := os.ReadFile(app.path("known_hosts"))
	if !bytes.Equal(previousBundle, currentBundle) || !bytes.Equal(previousHosts, currentHosts) {
		t.Fatal("failed replacement modified the active pairing")
	}
}
