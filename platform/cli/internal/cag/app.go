package cag

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

const (
	relayHost       = "adbgw.ru"
	relaySSHPort    = 443
	relayHostKey    = "[adbgw.ru]:443 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOIW2f0IkkC+BgYvVE7Mp1AaTqEZ3nTZzdguBBooU0/u"
	innerUser       = "cag"
	defaultSmartADB = 5037
	defaultRawADB   = 5555
)

type DeviceBundle struct {
	ClientFingerprint string `json:"client_fingerprint"`
	ClientLabel       string `json:"client_label"`
	DeviceID          string `json:"device_id"`
	DeviceLabel       string `json:"device_label"`
	Enabled           bool   `json:"enabled"`
	EndpointMode      string `json:"endpoint_mode"`
	EndpointHost      string `json:"endpoint_host"`
	InnerHostKey      string `json:"inner_host_key"`
	PairingExpiresAt  int64  `json:"pairing_expires_at"`
	RelayDevicePort   int    `json:"relay_device_port"`
	RelayHost         string `json:"relay_host"`
	RelaySSHPort      int    `json:"relay_ssh_port"`
}

type commandRunner interface {
	Run(context.Context, map[string]string, string, ...string) ([]byte, error)
	Interactive(context.Context, map[string]string, string, ...string) error
}

type systemRunner struct{}

func (systemRunner) command(ctx context.Context, env map[string]string, name string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = os.Environ()
	for key, value := range env {
		cmd.Env = append(cmd.Env, key+"="+value)
	}
	return cmd
}

func (runner systemRunner) Run(ctx context.Context, env map[string]string, name string, args ...string) ([]byte, error) {
	cmd := runner.command(ctx, env, name, args...)
	output, err := cmd.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(output))
		if message != "" {
			return output, fmt.Errorf("%s: %s", err, message)
		}
	}
	return output, err
}

func (runner systemRunner) Interactive(ctx context.Context, env map[string]string, name string, args ...string) error {
	cmd := runner.command(ctx, env, name, args...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

type App struct {
	runner     commandRunner
	configDir  string
	executable string
	stdout     io.Writer
}

func New() *App {
	configDir := os.Getenv("CAG_CONFIG_DIR")
	if configDir == "" {
		base, err := os.UserConfigDir()
		if err != nil {
			base = filepath.Join(os.Getenv("HOME"), ".config")
		}
		configDir = filepath.Join(base, "cag")
	}
	executable, _ := os.Executable()
	return &App{
		runner:     systemRunner{},
		configDir:  configDir,
		executable: executable,
		stdout:     os.Stdout,
	}
}

func newForTest(runner commandRunner, configDir, executable string, stdout io.Writer) *App {
	return &App{runner: runner, configDir: configDir, executable: executable, stdout: stdout}
}

func (app *App) Run(ctx context.Context, args []string) error {
	if runtime.GOOS == "windows" {
		return errors.New("this release supports macOS and Linux OpenSSH clients")
	}
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		app.usage()
		return nil
	}
	switch args[0] {
	case "pair":
		if len(args) != 2 {
			return errors.New("usage: cag pair XXXX-XXXX")
		}
		return app.pair(ctx, args[1])
	case "connect":
		return app.connect(ctx, args[1:])
	case "status":
		if len(args) != 1 {
			return errors.New("usage: cag status")
		}
		return app.status(ctx)
	case "disconnect":
		if len(args) != 1 {
			return errors.New("usage: cag disconnect")
		}
		return app.disconnect(ctx)
	case "__proxy":
		if len(args) != 2 {
			return errors.New("invalid proxy invocation")
		}
		port, err := strconv.Atoi(args[1])
		if err != nil || port < 1024 || port > 65535 {
			return errors.New("invalid proxy port")
		}
		return app.proxy(ctx, port)
	default:
		app.usage()
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func (app *App) usage() {
	fmt.Fprintln(app.stdout, `Usage:
  cag pair XXXX-XXXX
  cag connect
  cag connect -- adb [arguments...]
  cag status
  cag disconnect`)
}

func (app *App) path(name string) string { return filepath.Join(app.configDir, name) }

func (app *App) ensureConfig() error {
	if err := os.MkdirAll(app.configDir, 0o700); err != nil {
		return fmt.Errorf("create config directory: %w", err)
	}
	if err := os.Chmod(app.configDir, 0o700); err != nil {
		return fmt.Errorf("protect config directory: %w", err)
	}
	knownHosts := app.path("known_hosts")
	data, err := os.ReadFile(knownHosts)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("read known hosts: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		if line == relayHostKey {
			return nil
		}
	}
	if len(data) > 0 && data[len(data)-1] != '\n' {
		data = append(data, '\n')
	}
	data = append(data, relayHostKey...)
	data = append(data, '\n')
	return writeAtomic(knownHosts, data, 0o600)
}

func (app *App) ensureIdentity(ctx context.Context) error {
	if err := app.ensureConfig(); err != nil {
		return err
	}
	privateKey := app.path("identity")
	publicKey := privateKey + ".pub"
	if fileNonempty(privateKey) && fileNonempty(publicKey) {
		return nil
	}
	hostname, _ := os.Hostname()
	if _, err := app.runner.Run(ctx, nil, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "cag@"+hostname, "-f", privateKey); err != nil {
		return fmt.Errorf("create client key: %w", err)
	}
	if err := os.Chmod(privateKey, 0o600); err != nil {
		return err
	}
	return os.Chmod(publicKey, 0o644)
}

func fileNonempty(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Size() > 0
}

func normalizeCode(value string) (string, error) {
	var compact strings.Builder
	for _, char := range strings.ToUpper(value) {
		if char >= 'A' && char <= 'Z' || char >= '0' && char <= '9' {
			compact.WriteRune(char)
		}
	}
	if compact.Len() != 8 {
		return "", errors.New("the code must contain eight characters")
	}
	for _, char := range compact.String() {
		if !strings.ContainsRune("23456789ABCDEFGHJKLMNPQRSTUVWXYZ", char) {
			return "", errors.New("the code contains an unsupported character")
		}
	}
	return compact.String()[:4] + "-" + compact.String()[4:], nil
}

func (app *App) pair(ctx context.Context, rawCode string) error {
	code, err := normalizeCode(rawCode)
	if err != nil {
		return err
	}
	if err := app.ensureIdentity(ctx); err != nil {
		return err
	}
	publicKey, err := os.ReadFile(app.path("identity.pub"))
	if err != nil {
		return fmt.Errorf("read client key: %w", err)
	}
	hostname, _ := os.Hostname()
	payload, err := json.Marshal(map[string]string{
		"public_key": strings.TrimSpace(string(publicKey)),
		"label":      hostname,
	})
	if err != nil {
		return err
	}
	encodedPayload := base64.RawURLEncoding.EncodeToString(payload)
	output, err := app.runner.Run(ctx, askpassEnvironment(app.executable, code), "ssh", app.relayPairArgs(code, encodedPayload)...)
	if err != nil {
		return fmt.Errorf("the relay rejected the code or is unavailable: %w", err)
	}
	bundle, rawBundle, err := parseRelayBundle(output)
	if err != nil {
		return err
	}
	fingerprint, err := app.identityFingerprint(ctx)
	if err != nil {
		return err
	}
	if err := validateBundle(bundle, fingerprint); err != nil {
		return err
	}

	temporaryKnownHosts := app.path(fmt.Sprintf(".pair-known-hosts-%d", os.Getpid()))
	defer os.Remove(temporaryKnownHosts)
	if err := app.writeKnownHostsForBundle(bundle, temporaryKnownHosts); err != nil {
		return err
	}
	confirmation, err := app.runner.Run(ctx, askpassEnvironment(app.executable, code), "ssh", app.innerArgs(bundle, temporaryKnownHosts, false, "pair-complete")...)
	if err != nil {
		return fmt.Errorf("the car did not confirm pairing; the previous computer still has access: %w", err)
	}
	if !strings.HasPrefix(strings.TrimSpace(string(confirmation)), "OK") {
		return errors.New("the car returned an invalid pairing confirmation")
	}
	if err := app.installBundleHostKey(bundle); err != nil {
		return err
	}
	if err := writeAtomic(app.path("device.json"), rawBundle, 0o600); err != nil {
		return fmt.Errorf("save paired car: %w", err)
	}
	fmt.Fprintf(app.stdout, "Paired with %s.\n", bundle.DeviceLabel)
	return nil
}

func askpassEnvironment(executable, code string) map[string]string {
	return map[string]string{
		"CAG_ASKPASS_MODE":    "1",
		"CAG_ASKPASS_SECRET":  code,
		"SSH_ASKPASS":         executable,
		"SSH_ASKPASS_REQUIRE": "force",
		"DISPLAY":             "cag",
	}
}

func (app *App) relayPairArgs(code, payload string) []string {
	return append(app.relayHostArgs(false),
		"-o", "BatchMode=no",
		"-o", "PreferredAuthentications=password",
		"-o", "PubkeyAuthentication=no",
		"-o", "NumberOfPasswordPrompts=1",
		"cag-pair@"+relayHost,
		"pair "+code+" "+payload,
	)
}

func (app *App) relayHostArgs(withIdentity bool) []string {
	args := []string{
		"-T", "-p", strconv.Itoa(relaySSHPort),
		"-o", "StrictHostKeyChecking=yes",
		"-o", "UserKnownHostsFile=" + app.path("known_hosts"),
		"-o", "GlobalKnownHostsFile=/dev/null",
		"-o", "LogLevel=ERROR",
	}
	if withIdentity {
		args = append(args,
			"-i", app.path("identity"),
			"-o", "BatchMode=yes",
			"-o", "IdentitiesOnly=yes",
		)
	}
	return args
}

func parseRelayBundle(output []byte) (DeviceBundle, []byte, error) {
	var bundle DeviceBundle
	for scanner := bufio.NewScanner(strings.NewReader(string(output))); scanner.Scan(); {
		fields := strings.Fields(scanner.Text())
		if len(fields) != 2 || fields[0] != "OK" {
			continue
		}
		raw, err := base64.RawURLEncoding.DecodeString(fields[1])
		if err != nil {
			return bundle, nil, errors.New("the relay returned an invalid bundle")
		}
		if err := json.Unmarshal(raw, &bundle); err != nil {
			return bundle, nil, errors.New("the relay returned invalid JSON")
		}
		return bundle, append(raw, '\n'), nil
	}
	return bundle, nil, errors.New("the relay returned an invalid response")
}

func (app *App) identityFingerprint(ctx context.Context) (string, error) {
	output, err := app.runner.Run(ctx, nil, "ssh-keygen", "-lf", app.path("identity.pub"), "-E", "sha256")
	if err != nil {
		return "", fmt.Errorf("read client fingerprint: %w", err)
	}
	fields := strings.Fields(string(output))
	if len(fields) < 2 || !strings.HasPrefix(fields[1], "SHA256:") {
		return "", errors.New("ssh-keygen returned an invalid client fingerprint")
	}
	return fields[1], nil
}

func validateBundle(bundle DeviceBundle, fingerprint string) error {
	if bundle.RelayHost != relayHost || bundle.RelaySSHPort != relaySSHPort {
		return errors.New("relay bundle changed the fixed relay identity")
	}
	if len(bundle.DeviceID) != 16 || strings.Trim(bundle.DeviceID, "0123456789abcdef") != "" {
		return errors.New("relay returned an invalid device ID")
	}
	if bundle.RelayDevicePort < 1024 || bundle.RelayDevicePort > 65535 {
		return errors.New("relay returned an invalid device port")
	}
	if bundle.ClientFingerprint != fingerprint {
		return errors.New("relay returned a different client identity")
	}
	if bundle.EndpointMode != "smart" && bundle.EndpointMode != "raw" {
		return errors.New("the car has no usable ADB endpoint yet")
	}
	endpointIP := net.ParseIP(bundle.EndpointHost)
	if endpointIP == nil || endpointIP.To4() == nil || endpointIP.IsMulticast() || endpointIP.IsUnspecified() {
		return errors.New("relay returned an invalid ADB endpoint host")
	}
	if !validPublicKey(bundle.InnerHostKey) {
		return errors.New("relay returned an unsupported device host key")
	}
	return nil
}

func validPublicKey(value string) bool {
	fields := strings.Fields(value)
	if len(fields) != 2 {
		return false
	}
	switch fields[0] {
	case "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521":
	default:
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(fields[1])
	return err == nil && len(decoded) >= 32
}

func (app *App) writeKnownHostsForBundle(bundle DeviceBundle, destination string) error {
	data, err := os.ReadFile(app.path("known_hosts"))
	if err != nil {
		return err
	}
	alias := innerAlias(bundle.DeviceID)
	var output strings.Builder
	for _, line := range strings.Split(string(data), "\n") {
		if line != "" && !strings.HasPrefix(line, alias+" ") {
			output.WriteString(line)
			output.WriteByte('\n')
		}
	}
	output.WriteString(alias)
	output.WriteByte(' ')
	output.WriteString(bundle.InnerHostKey)
	output.WriteByte('\n')
	return writeAtomic(destination, []byte(output.String()), 0o600)
}

func (app *App) installBundleHostKey(bundle DeviceBundle) error {
	temporary := app.path(fmt.Sprintf(".known-hosts-%d", os.Getpid()))
	defer os.Remove(temporary)
	if err := app.writeKnownHostsForBundle(bundle, temporary); err != nil {
		return err
	}
	data, err := os.ReadFile(temporary)
	if err != nil {
		return err
	}
	return writeAtomic(app.path("known_hosts"), data, 0o600)
}

func innerAlias(deviceID string) string { return "cag-device-" + deviceID }

func (app *App) proxyCommand(port int) string {
	return shellQuote(app.executable) + " __proxy " + strconv.Itoa(port)
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'"
}

func (app *App) innerArgs(bundle DeviceBundle, knownHosts string, batch bool, command ...string) []string {
	args := []string{
		"-T",
		"-i", app.path("identity"),
		"-o", "IdentitiesOnly=yes",
		"-o", "StrictHostKeyChecking=yes",
		"-o", "HostKeyAlias=" + innerAlias(bundle.DeviceID),
		"-o", "UserKnownHostsFile=" + knownHosts,
		"-o", "GlobalKnownHostsFile=/dev/null",
		"-o", "ProxyCommand=" + app.proxyCommand(bundle.RelayDevicePort),
		"-o", "LogLevel=ERROR",
	}
	if batch {
		args = append(args, "-o", "BatchMode=yes")
	} else {
		args = append(args,
			"-o", "BatchMode=no",
			"-o", "PreferredAuthentications=publickey,password",
			"-o", "NumberOfPasswordPrompts=1",
		)
	}
	args = append(args, innerUser+"@127.0.0.1")
	return append(args, command...)
}

func (app *App) proxy(ctx context.Context, port int) error {
	args := append(app.relayHostArgs(true),
		"-o", "ServerAliveInterval=30",
		"-o", "ServerAliveCountMax=3",
		"-W", "127.0.0.1:"+strconv.Itoa(port),
		"cag-client@"+relayHost,
	)
	return app.runner.Interactive(ctx, nil, "ssh", args...)
}

func (app *App) loadBundle() (DeviceBundle, error) {
	var bundle DeviceBundle
	if err := app.ensureConfig(); err != nil {
		return bundle, err
	}
	raw, err := os.ReadFile(app.path("device.json"))
	if errors.Is(err, os.ErrNotExist) {
		return bundle, errors.New("no car is paired; run: cag pair XXXX-XXXX")
	}
	if err != nil || json.Unmarshal(raw, &bundle) != nil {
		return bundle, errors.New("the saved pairing is corrupt; pair again")
	}
	return bundle, nil
}

func (app *App) controlCheck(ctx context.Context, bundle DeviceBundle) bool {
	if _, err := os.Stat(app.path("session.sock")); err != nil {
		return false
	}
	args := app.innerArgs(bundle, app.path("known_hosts"), true)
	args = append(args[:len(args)-1], "-S", app.path("session.sock"), "-O", "check", args[len(args)-1])
	_, err := app.runner.Run(ctx, nil, "ssh", args...)
	return err == nil
}

func (app *App) startTunnel(ctx context.Context, bundle DeviceBundle) (int, error) {
	if app.controlCheck(ctx, bundle) {
		return app.readLocalPort()
	}
	os.Remove(app.path("session.sock"))
	os.Remove(app.path("local-port"))
	targetPort := defaultSmartADB
	if bundle.EndpointMode == "raw" {
		targetPort = defaultRawADB
	}
	for attempt := 0; attempt < 20; attempt++ {
		localPort, err := freeLocalPort()
		if err != nil {
			return 0, err
		}
		args := app.innerArgs(bundle, app.path("known_hosts"), true)
		last := args[len(args)-1]
		args = append(args[:len(args)-1],
			"-o", "ExitOnForwardFailure=yes",
			"-o", "ServerAliveInterval=30",
			"-o", "ServerAliveCountMax=3",
			"-o", "ControlMaster=yes",
			"-o", "ControlPath="+app.path("session.sock"),
			"-S", app.path("session.sock"),
			"-M", "-N", "-f",
			"-L", fmt.Sprintf("127.0.0.1:%d:%s:%d", localPort, bundle.EndpointHost, targetPort),
			last,
		)
		if _, err := app.runner.Run(ctx, nil, "ssh", args...); err == nil {
			if err := writeAtomic(app.path("local-port"), []byte(strconv.Itoa(localPort)+"\n"), 0o600); err != nil {
				return 0, err
			}
			return localPort, nil
		}
	}
	return 0, errors.New("could not open a local ADB port")
}

func freeLocalPort() (int, error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

func (app *App) readLocalPort() (int, error) {
	data, err := os.ReadFile(app.path("local-port"))
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(data)))
}

func (app *App) connect(ctx context.Context, args []string) error {
	if len(args) > 0 && (len(args) < 2 || args[0] != "--" || args[1] != "adb") {
		return errors.New("usage: cag connect [-- adb ...]")
	}
	bundle, err := app.loadBundle()
	if err != nil {
		return err
	}
	if err := app.ensureIdentity(ctx); err != nil {
		return err
	}
	localPort, err := app.startTunnel(ctx, bundle)
	if err != nil {
		return err
	}
	if len(args) == 0 {
		fmt.Fprintf(app.stdout, "Connected. Local ADB port: %d (%s).\n", localPort, bundle.EndpointMode)
		return nil
	}
	adbArgs := args[2:]
	if bundle.EndpointMode == "smart" {
		return app.runner.Interactive(ctx, map[string]string{
			"ADB_SERVER_SOCKET": fmt.Sprintf("tcp:127.0.0.1:%d", localPort),
		}, "adb", adbArgs...)
	}
	serial := fmt.Sprintf("127.0.0.1:%d", localPort)
	if _, err := app.runner.Run(ctx, nil, "adb", "connect", serial); err != nil {
		return err
	}
	return app.runner.Interactive(ctx, nil, "adb", append([]string{"-s", serial}, adbArgs...)...)
}

func (app *App) status(ctx context.Context) error {
	bundle, err := app.loadBundle()
	if err != nil {
		return err
	}
	state := "disconnected"
	localPort := "-"
	if app.controlCheck(ctx, bundle) {
		state = "connected"
		if port, err := app.readLocalPort(); err == nil {
			localPort = strconv.Itoa(port)
		}
	}
	fmt.Fprintf(app.stdout, "Car: %s\nState: %s\nADB: %s\nLocal port: %s\n", bundle.DeviceLabel, state, bundle.EndpointMode, localPort)
	return nil
}

func (app *App) disconnect(ctx context.Context) error {
	bundle, err := app.loadBundle()
	if err != nil {
		return err
	}
	if app.controlCheck(ctx, bundle) {
		args := app.innerArgs(bundle, app.path("known_hosts"), true)
		args = append(args[:len(args)-1], "-S", app.path("session.sock"), "-O", "exit", args[len(args)-1])
		_, _ = app.runner.Run(ctx, nil, "ssh", args...)
	}
	os.Remove(app.path("session.sock"))
	os.Remove(app.path("local-port"))
	fmt.Fprintln(app.stdout, "Disconnected.")
	return nil
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	directory := filepath.Dir(path)
	file, err := os.CreateTemp(directory, ".cag-*")
	if err != nil {
		return err
	}
	temporary := file.Name()
	defer os.Remove(temporary)
	if err := file.Chmod(mode); err != nil {
		file.Close()
		return err
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}
