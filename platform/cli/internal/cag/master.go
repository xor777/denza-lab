package cag

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const masterPIDName = "master.pid"

type processInfo struct {
	PID     int
	Command string
}

// processTable is how cag sees and signals processes; tests replace it.
type processTable interface {
	// Command returns the command line of pid, or "" when it is not running.
	Command(pid int) string
	List() []processInfo
	Signal(pid int, sig syscall.Signal) error
}

type systemProcesses struct{}

func (systemProcesses) Command(pid int) string {
	if pid <= 0 {
		return ""
	}
	// -ww: the master command line is long and must not be cut.
	output, err := exec.Command("ps", "-ww", "-o", "command=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(output))
}

func (systemProcesses) List() []processInfo {
	output, err := exec.Command("ps", "-A", "-ww", "-o", "pid=", "-o", "command=").Output()
	if err != nil {
		return nil
	}
	var processes []processInfo
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		pid, err := strconv.Atoi(fields[0])
		if err != nil {
			continue
		}
		command := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), fields[0]))
		processes = append(processes, processInfo{PID: pid, Command: command})
	}
	return processes
}

func (systemProcesses) Signal(pid int, sig syscall.Signal) error { return syscall.Kill(pid, sig) }

// masterState is what cag knows about this config's ControlMaster. The PID
// file and the process table outlive the control socket, so a master whose
// socket vanished is still found, owned and stopped.
type masterState struct {
	Answers bool // ssh -O check succeeded
	Running bool // PID is a live ssh master of this config
	PID     int
	Port    int // the local port it forwards, 0 if unknown
}

func (state masterState) holds(port int) bool {
	return (state.Answers || state.Running) && state.Port == port
}

var masterPIDPattern = regexp.MustCompile(`pid=(\d+)`)
var masterPortPattern = regexp.MustCompile(`-L 127\.0\.0\.1:(\d+):`)

// controlCheck asks the master over its socket; ssh prints
// "Master running (pid=N)".
func (app *App) controlCheck(ctx context.Context, bundle DeviceBundle) (bool, int) {
	if _, err := os.Stat(app.controlSocket()); err != nil {
		return false, 0
	}
	args := app.innerArgs(bundle, app.path("known_hosts"), true)
	args = append(args[:len(args)-1], "-S", sshPercentEscape(app.controlSocket()), "-O", "check", args[len(args)-1])
	output, err := app.runner.Run(ctx, nil, "ssh", args...)
	if err != nil {
		return false, 0
	}
	pid := 0
	if match := masterPIDPattern.FindSubmatch(output); match != nil {
		pid, _ = strconv.Atoi(string(match[1]))
	}
	return true, pid
}

// isOurMaster tells this config's master from its -O clients and from
// anything else that reuses the PID: only the master has -M and our socket.
func (app *App) isOurMaster(command string) bool {
	if !strings.Contains(command, "ssh") || !strings.Contains(command, " -M ") {
		return false
	}
	socket := app.controlSocket()
	return strings.Contains(command, socket) || strings.Contains(command, sshPercentEscape(socket))
}

func (app *App) readMasterPID() int {
	data, err := os.ReadFile(app.path(masterPIDName))
	if err != nil {
		return 0
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
	return pid
}

func (app *App) recordMaster(pid, port int) {
	if pid > 0 && app.readMasterPID() != pid {
		_ = writeAtomic(app.path(masterPIDName), []byte(strconv.Itoa(pid)+"\n"), 0o600)
	}
	if port > 0 {
		if current, err := app.readLocalPort(); err != nil || current != port {
			_ = writeAtomic(app.path("local-port"), []byte(strconv.Itoa(port)+"\n"), 0o600)
		}
	}
}

func (app *App) findOurMaster() (int, string) {
	for _, process := range app.processes.List() {
		if app.isOurMaster(process.Command) {
			return process.PID, process.Command
		}
	}
	return 0, ""
}

func (app *App) masterState(ctx context.Context, bundle DeviceBundle) masterState {
	var state masterState
	state.Port, _ = app.readLocalPort()
	answers, checkPID := app.controlCheck(ctx, bundle)
	state.Answers = answers
	pid := app.readMasterPID()
	if checkPID > 0 {
		pid = checkPID
	}
	command := ""
	if pid > 0 {
		if command = app.processes.Command(pid); !app.isOurMaster(command) {
			pid, command = 0, ""
		}
	}
	if pid == 0 {
		pid, command = app.findOurMaster()
	}
	if pid > 0 {
		state.Running = true
		state.PID = pid
		if match := masterPortPattern.FindStringSubmatch(command); match != nil {
			state.Port, _ = strconv.Atoi(match[1])
		}
		app.recordMaster(pid, state.Port)
	}
	return state
}

func (app *App) removeMasterFiles() {
	os.Remove(app.controlSocket())
	os.Remove(app.path("local-port"))
	os.Remove(app.path(masterPIDName))
}

func (app *App) waitForExit(pid int, timeout time.Duration) bool {
	for deadline := time.Now().Add(timeout); ; {
		if !app.isOurMaster(app.processes.Command(pid)) {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// stopMaster ends this config's master: politely over the socket when it
// answers, by its PID when it does not. Its files go only once it is gone.
func (app *App) stopMaster(ctx context.Context, bundle DeviceBundle, state masterState) error {
	exited := state.PID == 0
	if state.Answers {
		args := app.innerArgs(bundle, app.path("known_hosts"), true)
		args = append(args[:len(args)-1], "-S", sshPercentEscape(app.controlSocket()), "-O", "exit", args[len(args)-1])
		_, _ = app.runner.Run(ctx, nil, "ssh", args...)
		exited = exited || app.waitForExit(state.PID, 2*time.Second)
	}
	if !exited && !app.waitForExit(state.PID, 0) {
		_ = app.processes.Signal(state.PID, syscall.SIGTERM)
		if !app.waitForExit(state.PID, 5*time.Second) {
			_ = app.processes.Signal(state.PID, syscall.SIGKILL)
			if !app.waitForExit(state.PID, 2*time.Second) {
				return fmt.Errorf("the tunnel process %d does not exit", state.PID)
			}
		}
	}
	app.removeMasterFiles()
	return nil
}

// discoverMasterPID finds the master that ssh -f just left behind.
func (app *App) discoverMasterPID(ctx context.Context, bundle DeviceBundle) int {
	for attempt := 0; attempt < 5; attempt++ {
		if answers, pid := app.controlCheck(ctx, bundle); answers && pid > 0 && app.isOurMaster(app.processes.Command(pid)) {
			return pid
		}
		if pid, _ := app.findOurMaster(); pid > 0 {
			return pid
		}
		time.Sleep(200 * time.Millisecond)
	}
	return 0
}

// startTunnel makes sure this config's ControlMaster forwards 127.0.0.1:port
// and returns its PID. A master that answers on that port is reused; one on
// another port, or one that runs but does not answer, is stopped first. A
// port held by anything else is an error: the port is part of the contract
// with the tools that use the serial, so there is no fallback.
func (app *App) startTunnel(ctx context.Context, bundle DeviceBundle, port int) (int, error) {
	state := app.masterState(ctx, bundle)
	if state.Answers && state.Port == port {
		return state.PID, nil
	}
	if state.Answers || state.Running {
		if err := app.stopMaster(ctx, bundle, state); err != nil {
			return 0, err
		}
	} else {
		app.removeMasterFiles()
	}
	if !localPortFree(port) {
		return 0, &portBusyError{port: port}
	}
	logPath := app.path(tunnelLogName)
	proxyLogOffset := fileSize(app.path(proxyLogName))
	err := app.runner.Detached(ctx, nil, logPath, "ssh", app.tunnelArgs(bundle, port)...)
	if err != nil {
		output, _ := os.ReadFile(logPath)
		if isLocalBindFailure(string(output)) {
			return 0, &portBusyError{port: port}
		}
		return 0, fmt.Errorf("could not open the tunnel to the car: %w%s%s", err,
			logDetail("ssh", string(output)), logDetail("relay", app.logSince(proxyLogName, proxyLogOffset)))
	}
	if err := writeAtomic(app.path("local-port"), []byte(strconv.Itoa(port)+"\n"), 0o600); err != nil {
		return 0, err
	}
	pid := app.discoverMasterPID(ctx, bundle)
	app.recordMaster(pid, port)
	return pid, nil
}

// closeAnyMaster stops this config's master for disconnect and uninstall,
// even when its control socket is gone.
func (app *App) closeAnyMaster(ctx context.Context) (bool, error) {
	bundle, _ := app.loadBundle()
	state := app.masterState(ctx, bundle)
	if !state.Answers && !state.Running {
		app.removeMasterFiles()
		return false, nil
	}
	return true, app.stopMaster(ctx, bundle, state)
}
