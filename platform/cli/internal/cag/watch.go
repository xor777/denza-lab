package cag

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	watchPollInterval = 10 * time.Second
	watchMinBackoff   = time.Second
	watchMaxBackoff   = 10 * time.Second
	watchLogName      = "watch.log"
	watchLockName     = "watch.lock"
	// watchGracePolls is how many more polls a master that runs but does not
	// answer -O check is trusted before the watch replaces it.
	watchGracePolls = 2
)

type watchAction int

const (
	watchHealthy watchAction = iota
	watchReconnect
	watchBackoff
	watchWaitForPort
	watchAdbConnect
	watchWaitForPairing
	watchGrace   // our master runs but did not answer; leave it alone for now
	watchRestart // our master stopped answering for too long; replace it
)

// watchObservation is everything one poll learns; decideWatch turns it into
// the next step without side effects.
type watchObservation struct {
	Now           time.Time
	Paired        bool
	Port          int  // the port the tools expect
	MasterAnswers bool // this config's ControlMaster answers -O check
	MasterRunning bool // the recorded master process is alive
	MasterPort    int  // the port that master forwards, 0 if unknown
	Unanswered    int  // consecutive polls where it ran without answering
	PortBusy      bool // something other than our master holds Port
	Raw           bool // the car exposes raw adbd, so the serial matters
	AdbMissing    bool // running adb failed with "not found"
	SerialState   string
	RetryAt       time.Time
}

func decideWatch(obs watchObservation) watchAction {
	ours := obs.MasterPort == obs.Port
	switch {
	case !obs.Paired:
		return watchWaitForPairing
	case ours && obs.MasterAnswers:
		if obs.Raw && !obs.AdbMissing && obs.SerialState != "device" {
			return watchAdbConnect
		}
		return watchHealthy
	case ours && obs.MasterRunning:
		if obs.Unanswered <= watchGracePolls {
			return watchGrace
		}
		return watchRestart
	case obs.PortBusy:
		return watchWaitForPort
	case obs.Now.Before(obs.RetryAt):
		return watchBackoff
	default:
		return watchReconnect
	}
}

// reconnectBackoff grows from one second to ten seconds with jitter, so a fleet
// of computers does not hit the relay in step after an outage.
type reconnectBackoff struct {
	failures int
	random   func() float64
}

func (backoff *reconnectBackoff) next() time.Duration {
	backoff.failures++
	delay := watchMinBackoff << min(backoff.failures-1, 6)
	if delay > watchMaxBackoff {
		delay = watchMaxBackoff
	}
	random := backoff.random
	if random == nil {
		random = rand.Float64
	}
	jittered := time.Duration(float64(delay) * (0.5 + 0.5*random()))
	return max(jittered, watchMinBackoff)
}

func (backoff *reconnectBackoff) reset() { backoff.failures = 0 }

// stateLog writes one line per state change, not one per poll.
type stateLog struct {
	path string
	last string
}

func (log *stateLog) state(message string) {
	if message == log.last {
		return
	}
	log.last = message
	file, err := os.OpenFile(log.path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return
	}
	defer file.Close()
	fmt.Fprintf(file, "%s %s\n", time.Now().Format(time.RFC3339), message)
}

func firstLine(err error) string {
	text := strings.TrimSpace(err.Error())
	if line, _, found := strings.Cut(text, "\n"); found {
		return line
	}
	return text
}

// lockWatch takes watch.lock for the life of the process. Two watches on one
// config fight over the master, so the second one leaves.
func (app *App) lockWatch() (*os.File, bool, error) {
	file, err := os.OpenFile(app.path(watchLockName), os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, false, err
	}
	if err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		file.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return nil, false, nil
		}
		return nil, false, fmt.Errorf("lock %s: %w", app.path(watchLockName), err)
	}
	_ = file.Truncate(0)
	_, _ = file.WriteAt([]byte(strconv.Itoa(os.Getpid())+"\n"), 0)
	return file, true, nil
}

// watch keeps the tunnel on the remembered port until SIGTERM. It never stops
// or disconnects anything it did not start, and closes its own master on exit.
func (app *App) watch(ctx context.Context) error {
	if err := app.ensureConfig(); err != nil {
		return err
	}
	lock, locked, err := app.lockWatch()
	if err != nil {
		return err
	}
	if !locked {
		holder, _ := os.ReadFile(app.path(watchLockName))
		fmt.Fprintf(app.stdout, "Another cag connect --watch (pid %s) already keeps this config's tunnel; exiting.\n",
			strings.TrimSpace(string(holder)))
		return nil
	}
	defer lock.Close()
	ctx, stop := signal.NotifyContext(ctx, syscall.SIGTERM, os.Interrupt)
	defer stop()
	watcher := &watcher{app: app, log: &stateLog{path: app.path(watchLogName)}, backoff: &reconnectBackoff{}}
	watcher.log.state(fmt.Sprintf("watch started (pid %d)", os.Getpid()))
	for !app.sleep(ctx, watcher.step(ctx)) {
	}
	watcher.closeOwnMaster()
	watcher.log.state("watch stopped")
	return nil
}

type watcher struct {
	app        *App
	log        *stateLog
	backoff    *reconnectBackoff
	retryAt    time.Time
	unanswered int
	ownedPID   int // the master this watch started, 0 if none
	lastBundle DeviceBundle
}

// step runs one poll and returns how long to wait before the next one.
func (watcher *watcher) step(ctx context.Context) time.Duration {
	app := watcher.app
	obs, bundle, master, err := app.observe(ctx, watcher.retryAt)
	if err != nil {
		watcher.log.state("error: " + firstLine(err))
		return watchPollInterval
	}
	if obs.Paired {
		watcher.lastBundle = bundle
	}
	if obs.MasterRunning && !obs.MasterAnswers && obs.MasterPort == obs.Port {
		watcher.unanswered++
	} else {
		watcher.unanswered = 0
	}
	obs.Unanswered = watcher.unanswered
	serial := loopbackAddress(obs.Port)
	switch decideWatch(obs) {
	case watchWaitForPairing:
		watcher.log.state("waiting: no car is paired; run: cag pair XXXX-XXXX")
	case watchWaitForPort:
		watcher.log.state("waiting: " + serial + " is in use by another program")
	case watchBackoff:
		return min(watchPollInterval, time.Until(watcher.retryAt))
	case watchGrace:
		watcher.log.state(fmt.Sprintf("tunnel (pid %d) did not answer; waiting", master.PID))
	case watchRestart:
		watcher.log.state(fmt.Sprintf("tunnel (pid %d) stopped answering; restarting it", master.PID))
		watcher.unanswered = 0
		return watcher.reconnect(ctx, obs, bundle)
	case watchReconnect:
		return watcher.reconnect(ctx, obs, bundle)
	case watchAdbConnect:
		if err := app.adbConnect(ctx, serial); err != nil {
			watcher.log.state("adb: " + firstLine(err))
		} else {
			watcher.log.state(healthyMessage(obs, bundle))
		}
	case watchHealthy:
		watcher.log.state(healthyMessage(obs, bundle))
	}
	return watchPollInterval
}

func (watcher *watcher) reconnect(ctx context.Context, obs watchObservation, bundle DeviceBundle) time.Duration {
	app := watcher.app
	serial := loopbackAddress(obs.Port)
	err := app.ensureIdentity(ctx)
	pid := 0
	if err == nil {
		pid, err = app.startTunnel(ctx, bundle, obs.Port)
	}
	var busy *portBusyError
	switch {
	case err == nil:
		watcher.backoff.reset()
		watcher.retryAt = time.Time{}
		watcher.ownedPID = pid
		watcher.log.state(fmt.Sprintf("tunnel up on %s (pid %d)", serial, pid))
		return time.Second // look at the adb serial right away
	case errors.As(err, &busy):
		watcher.log.state("waiting: " + serial + " is in use by another program")
	default:
		watcher.log.state("reconnect failed: " + firstLine(err))
	}
	delay := watcher.backoff.next()
	watcher.retryAt = time.Now().Add(delay)
	return min(watchPollInterval, delay)
}

// closeOwnMaster stops the master this watch started, including one it
// restarted after a drop, even if its socket is gone. A master someone opened
// by hand stays.
func (watcher *watcher) closeOwnMaster() {
	app := watcher.app
	if watcher.ownedPID == 0 || !app.isOurMaster(app.processes.Command(watcher.ownedPID)) {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	state := app.masterState(ctx, watcher.lastBundle)
	if state.PID != watcher.ownedPID {
		state = masterState{Running: true, PID: watcher.ownedPID}
	}
	if err := app.stopMaster(ctx, watcher.lastBundle, state); err != nil {
		watcher.log.state("could not stop the tunnel: " + firstLine(err))
	}
}

func healthyMessage(obs watchObservation, bundle DeviceBundle) string {
	if bundle.EndpointMode == "smart" {
		return fmt.Sprintf("connected: ADB server tcp:%s (smart)", loopbackAddress(obs.Port))
	}
	if obs.AdbMissing {
		return fmt.Sprintf("connected: %s (raw); adb is not on PATH", loopbackAddress(obs.Port))
	}
	return fmt.Sprintf("connected: %s (raw)", loopbackAddress(obs.Port))
}

// sleep waits and reports whether the watch should stop.
func (app *App) sleep(ctx context.Context, wait time.Duration) bool {
	if wait <= 0 {
		wait = 100 * time.Millisecond
	}
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return true
	case <-timer.C:
		return false
	}
}

// observe gathers one poll's facts. Re-reading the pairing and the port each
// time lets the watch follow a new pairing or a `cag connect --port N`.
func (app *App) observe(ctx context.Context, retryAt time.Time) (watchObservation, DeviceBundle, masterState, error) {
	obs := watchObservation{Now: time.Now(), RetryAt: retryAt}
	if _, err := os.Stat(app.path("device.json")); errors.Is(err, os.ErrNotExist) {
		return obs, DeviceBundle{}, masterState{}, nil
	}
	bundle, err := app.loadBundle()
	if err != nil {
		return obs, bundle, masterState{}, err
	}
	obs.Paired = true
	obs.Raw = bundle.EndpointMode == "raw"
	if obs.Port, err = app.localPort(0); err != nil {
		return obs, bundle, masterState{}, err
	}
	master := app.masterState(ctx, bundle)
	obs.MasterAnswers, obs.MasterRunning, obs.MasterPort = master.Answers, master.Running, master.Port
	// A port held by our own master, answering or not, is not "another program".
	if !master.holds(obs.Port) {
		obs.PortBusy = !localPortFree(obs.Port)
		return obs, bundle, master, nil
	}
	if obs.Raw && master.Answers {
		obs.SerialState, obs.AdbMissing = app.adbSerialState(ctx, loopbackAddress(obs.Port))
	}
	return obs, bundle, master, nil
}
