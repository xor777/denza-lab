package cag

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const testInnerKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBERERERERERERERERERERERERERERERERERERERERE="

type fakeRunner struct {
	configDir        string
	pairConfirmation bool
	sshCalls         []string
}

func (runner *fakeRunner) Run(ctx context.Context, env map[string]string, name string, args ...string) ([]byte, error) {
	if name == "ssh-keygen" {
		return exec.CommandContext(ctx, name, args...).CombinedOutput()
	}
	if name != "ssh" {
		return nil, nil
	}
	joined := strings.Join(args, " ")
	runner.sshCalls = append(runner.sshCalls, joined)
	if strings.Contains(joined, "cag-pair@adbgw.ru") {
		output, err := exec.CommandContext(ctx, "ssh-keygen", "-lf", filepath.Join(runner.configDir, "identity.pub"), "-E", "sha256").Output()
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
			PairingExpiresAt:  1_700_000_600,
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
	return nil, nil
}

func (runner *fakeRunner) Interactive(context.Context, map[string]string, string, ...string) error {
	return nil
}

func newTestApp(t *testing.T, confirmation bool) (*App, *fakeRunner, *bytes.Buffer) {
	t.Helper()
	config := t.TempDir()
	runner := &fakeRunner{configDir: config, pairConfirmation: confirmation}
	output := &bytes.Buffer{}
	return newForTest(runner, config, "/usr/local/bin/cag", output), runner, output
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
	if !strings.Contains(string(knownHosts), relayHostKey) || !strings.Contains(string(knownHosts), innerAlias("0123456789abcdef")+" "+testInnerKey) {
		t.Fatalf("host keys were not pinned: %s", knownHosts)
	}
	if len(runner.sshCalls) != 2 || !strings.Contains(runner.sshCalls[0], "cag-pair@adbgw.ru") || !strings.Contains(runner.sshCalls[1], "pair-complete") {
		t.Fatalf("unexpected SSH calls: %#v", runner.sshCalls)
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
