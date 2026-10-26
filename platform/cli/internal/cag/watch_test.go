package cag

import (
	"context"
	"fmt"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestWatchDecisions(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	healthyRaw := watchObservation{
		Now: now, Paired: true, Port: 15555, MasterAnswers: true, MasterRunning: true, MasterPort: 15555,
		Raw: true, SerialState: "device",
	}
	dead := func(o *watchObservation) { o.MasterAnswers, o.MasterRunning, o.MasterPort = false, false, 0 }
	with := func(changes ...func(*watchObservation)) watchObservation {
		obs := healthyRaw
		for _, change := range changes {
			change(&obs)
		}
		return obs
	}
	cases := []struct {
		name string
		obs  watchObservation
		want watchAction
	}{
		{"healthy raw", healthyRaw, watchHealthy},
		{"healthy smart", with(func(o *watchObservation) { o.Raw = false; o.SerialState = "" }), watchHealthy},
		{"master dead, port free", with(dead), watchReconnect},
		{"master dead, port busy", with(dead, func(o *watchObservation) { o.PortBusy = true }), watchWaitForPort},
		{"master dead, backing off", with(dead, func(o *watchObservation) { o.RetryAt = now.Add(5 * time.Second) }), watchBackoff},
		{"busy port wins over backoff", with(dead, func(o *watchObservation) {
			o.PortBusy, o.RetryAt = true, now.Add(5*time.Second)
		}), watchWaitForPort},
		{"master on another port, port free", with(func(o *watchObservation) { o.MasterPort = 15600 }), watchReconnect},
		{"master on another port, port busy", with(func(o *watchObservation) { o.MasterPort = 15600; o.PortBusy = true }), watchWaitForPort},
		{"runs without answering, 1st poll", with(func(o *watchObservation) { o.MasterAnswers = false; o.Unanswered = 1 }), watchGrace},
		{"runs without answering, 2nd poll", with(func(o *watchObservation) { o.MasterAnswers = false; o.Unanswered = 2 }), watchGrace},
		{"runs without answering, 3rd poll", with(func(o *watchObservation) { o.MasterAnswers = false; o.Unanswered = 3 }), watchRestart},
		{"serial offline", with(func(o *watchObservation) { o.SerialState = "offline" }), watchAdbConnect},
		{"serial absent", with(func(o *watchObservation) { o.SerialState = "" }), watchAdbConnect},
		{"no adb on PATH", with(func(o *watchObservation) { o.AdbMissing = true; o.SerialState = "" }), watchHealthy},
		{"not paired", with(func(o *watchObservation) { o.Paired = false }), watchWaitForPairing},
	}
	for _, test := range cases {
		if got := decideWatch(test.obs); got != test.want {
			t.Errorf("%s: decideWatch = %d, want %d", test.name, got, test.want)
		}
	}
}

func TestReconnectBackoffGrowsFromOneSecondToAMinute(t *testing.T) {
	backoff := &reconnectBackoff{random: func() float64 { return 1 }}
	var delays []time.Duration
	for range 9 {
		delays = append(delays, backoff.next())
	}
	want := []time.Duration{1, 2, 4, 8, 16, 32, 60, 60, 60}
	for index, delay := range delays {
		if delay != want[index]*time.Second {
			t.Fatalf("delays = %v", delays)
		}
	}
	backoff.reset()
	backoff.random = func() float64 { return 0 }
	if delay := backoff.next(); delay != time.Second {
		t.Fatalf("jittered first delay = %s, want the 1 s floor", delay)
	}
	for range 8 {
		backoff.next()
	}
	if delay := backoff.next(); delay != 30*time.Second {
		t.Fatalf("jittered cap = %s, want half of a minute", delay)
	}
}

func TestStateLogWritesOneLinePerChange(t *testing.T) {
	log := &stateLog{path: t.TempDir() + "/watch.log"}
	for _, message := range []string{"waiting", "waiting", "connected", "connected", "waiting"} {
		log.state(message)
	}
	data, _ := os.ReadFile(log.path)
	if lines := strings.Split(strings.TrimSpace(string(data)), "\n"); len(lines) != 3 {
		t.Fatalf("want 3 lines, got %q", lines)
	}
}

func TestHealthyMessageClaimsMissingAdbOnlyWhenItWasMissing(t *testing.T) {
	raw := testBundle("raw", "127.0.0.1")
	unmeasured := watchObservation{Port: 15556, Raw: true}
	if message := healthyMessage(unmeasured, raw); message != "connected: 127.0.0.1:15556 (raw)" {
		t.Fatalf("message before adb was looked at: %q", message)
	}
	missing := watchObservation{Port: 15556, Raw: true, AdbMissing: true}
	if message := healthyMessage(missing, raw); !strings.Contains(message, "adb is not on PATH") {
		t.Fatalf("message with adb missing: %q", message)
	}
}

func newTestWatcher(app *App) *watcher {
	return &watcher{app: app, log: &stateLog{path: app.path(watchLogName)}, backoff: &reconnectBackoff{}}
}

func readLog(t *testing.T, app *App) string {
	t.Helper()
	data, _ := os.ReadFile(app.path(watchLogName))
	return string(data)
}

func TestSecondWatchOnOneConfigExits(t *testing.T) {
	app, runner, output := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	first, locked, err := app.lockWatch()
	if err != nil || !locked {
		t.Fatalf("first lock: %v %v", locked, err)
	}
	defer first.Close()
	done := make(chan error, 1)
	go func() { done <- app.watch(context.Background()) }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("the second watch failed instead of leaving: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the second watch kept running")
	}
	message := strings.TrimSpace(output.String())
	if !strings.Contains(message, fmt.Sprintf("pid %d", os.Getpid())) || strings.Contains(message, "\n") {
		t.Fatalf("want one line naming the running watch, got %q", message)
	}
	if len(runner.detachedCalls) != 0 || len(runner.runCalls) != 0 {
		t.Fatalf("the second watch acted: %q %q", runner.detachedCalls, runner.runCalls)
	}
	first.Close()
	again, locked, err := app.lockWatch()
	if err != nil || !locked {
		t.Fatalf("the lock was not free after the first watch: %v %v", locked, err)
	}
	again.Close()
}

func TestWatchWaitsQuietlyWhileAnotherProgramHoldsThePort(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := busyPort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	watcher := newTestWatcher(app)
	for range 3 {
		watcher.step(context.Background())
	}
	if len(runner.detachedCalls) != 0 {
		t.Fatalf("the watch started ssh on a busy port: %q", runner.detachedCalls)
	}
	if strings.Count(readLog(t, app), "in use by another program") != 1 {
		t.Fatalf("want one waiting line, got:\n%s", readLog(t, app))
	}
	if watcher.ownedPID != 0 {
		t.Fatal("the watch claimed a master it did not start")
	}
}

func TestWatchReconnectsThenAttachesTheSerial(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := freePort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	watcher := newTestWatcher(app)
	watcher.step(context.Background())
	if len(runner.detachedCalls) != 1 || watcher.ownedPID != runner.masterPID || app.readMasterPID() != runner.masterPID {
		t.Fatalf("the watch did not open and record its master: owned=%d recorded=%d pid=%d",
			watcher.ownedPID, app.readMasterPID(), runner.masterPID)
	}
	if strings.Contains(readLog(t, app), "not on PATH") {
		t.Fatalf("the log claimed adb is missing:\n%s", readLog(t, app))
	}

	runner.adbDevicesOutput = "List of devices attached\n" + loopbackAddress(port) + "\toffline\n"
	runner.adbConnectOutput = "connected to " + loopbackAddress(port) + "\n"
	watcher.step(context.Background())
	if callIndex(runner.runCalls, "adb", "connect", loopbackAddress(port)) < 0 {
		t.Fatalf("an offline serial did not get adb connect: %q", runner.runCalls)
	}
	if callIndex(runner.runCalls, "adb", "disconnect") >= 0 {
		t.Fatal("the watch disconnected a serial")
	}

	runner.adbDevicesOutput = "List of devices attached\n" + loopbackAddress(port) + "\tdevice\n"
	before := len(runner.runCalls)
	watcher.step(context.Background())
	for _, call := range runner.runCalls[before:] {
		if call[0] == "adb" && call[1] != "devices" {
			t.Fatalf("a healthy poll did more than look: %q", call)
		}
	}
	if len(runner.detachedCalls) != 1 {
		t.Fatalf("a healthy poll reopened the master: %d", len(runner.detachedCalls))
	}
}

func TestWatchKeepsAnUnansweringMasterTwoPollsThenReplacesIt(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("smart", "127.0.0.1"))
	port := freePort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	watcher := newTestWatcher(app)
	watcher.step(context.Background())
	first := runner.masterPID
	runner.unresponsivePID = first // its socket stops answering, the process lives on

	for poll := 1; poll <= 2; poll++ {
		watcher.step(context.Background())
		for _, file := range []string{app.controlSocket(), app.path("local-port"), app.path(masterPIDName)} {
			if _, err := os.Stat(file); err != nil {
				t.Fatalf("poll %d removed %s while the master ran", poll, file)
			}
		}
		if len(runner.procs.signalled()) != 0 || len(runner.detachedCalls) != 1 {
			t.Fatalf("poll %d acted on a master inside its grace polls", poll)
		}
	}

	watcher.step(context.Background())
	if signals := runner.procs.signalled(); len(signals) != 1 || signals[0] != fmt.Sprintf("%d %s", first, syscall.SIGTERM) {
		t.Fatalf("want SIGTERM to %d, got %q", first, signals)
	}
	second := runner.masterPID
	if len(runner.detachedCalls) != 2 || second == first || app.readMasterPID() != second || watcher.ownedPID != second {
		t.Fatalf("the master was not replaced: calls=%d first=%d second=%d recorded=%d",
			len(runner.detachedCalls), first, second, app.readMasterPID())
	}
}

func TestAPortHeldByOurOrphanedMasterIsNotAnotherProgram(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := busyPort(t) // the orphaned master's listener
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	pid := pretendMasterOn(t, app, runner, port)
	// The control socket, the port file and the PID file are all gone, as in
	// the live failure; only the process and its listener remain.
	os.Remove(app.controlSocket())
	os.Remove(app.path("local-port"))
	os.Remove(app.path(masterPIDName))

	obs, _, master, err := app.observe(context.Background(), time.Time{})
	if err != nil {
		t.Fatal(err)
	}
	if obs.PortBusy || !obs.MasterRunning || obs.MasterPort != port || master.PID != pid {
		t.Fatalf("our master was not recognised: %+v %+v", obs, master)
	}
	if decideWatch(obs) == watchWaitForPort {
		t.Fatal("the watch would wait on its own master")
	}
	if app.readMasterPID() != pid {
		t.Fatal("the rediscovered master was not recorded")
	}
}

func TestStopOnSignalClosesAMasterRestartedAfterADrop(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	port := freePort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	watcher := newTestWatcher(app)
	watcher.step(context.Background())
	first := runner.masterPID
	runner.procs.exit(first) // the tunnel drops
	watcher.step(context.Background())
	second := runner.masterPID
	if second == first || watcher.ownedPID != second {
		t.Fatalf("the watch did not restart and own the master: first=%d second=%d owned=%d", first, second, watcher.ownedPID)
	}
	os.Remove(app.controlSocket()) // even without its socket
	watcher.closeOwnMaster()
	if runner.procs.alive(second) {
		t.Fatal("the restarted master survived the watch")
	}
	if _, err := os.Stat(app.path(masterPIDName)); !os.IsNotExist(err) {
		t.Fatal("master.pid stayed after the master was stopped")
	}
}

func TestWatchLeavesAMasterItDidNotStart(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("smart", "127.0.0.1"))
	port := freePort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	pid := pretendMasterOn(t, app, runner, port)
	watcher := newTestWatcher(app)
	watcher.step(context.Background())
	watcher.closeOwnMaster()
	if !runner.procs.alive(pid) || len(runner.detachedCalls) != 0 {
		t.Fatal("the watch closed or replaced a master someone else opened")
	}
}

func TestWatchStopsOnCancelAndClosesItsOwnMaster(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("smart", "127.0.0.1"))
	port := freePort(t)
	if _, err := app.localPort(port); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	runner.onDetached = func([]string) { cancel() } // stopped right after the master comes up
	done := make(chan error, 1)
	go func() { done <- app.watch(ctx) }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("watch did not stop after cancel")
	}
	if runner.masterPID == 0 || runner.procs.alive(runner.masterPID) {
		t.Fatalf("watch left its own master running: %d", runner.masterPID)
	}
	if !strings.Contains(readLog(t, app), "watch stopped") {
		t.Fatalf("watch.log:\n%s", readLog(t, app))
	}
}

func TestDisconnectClosesAMasterWhoseSocketIsGone(t *testing.T) {
	app, runner, output := newTestApp(t, true)
	writeTestBundle(t, app, testBundle("raw", "127.0.0.1"))
	pid := pretendMasterOn(t, app, runner, freePort(t))
	os.Remove(app.controlSocket())
	if err := app.Run(context.Background(), []string{"disconnect"}); err != nil {
		t.Fatal(err)
	}
	if runner.procs.alive(pid) {
		t.Fatal("disconnect left the master running")
	}
	if signals := runner.procs.signalled(); len(signals) != 1 || !strings.HasPrefix(signals[0], fmt.Sprint(pid)) {
		t.Fatalf("want one signal to %d, got %q", pid, signals)
	}
	if !strings.Contains(output.String(), "Disconnected.") {
		t.Fatalf("output %q", output)
	}
}
