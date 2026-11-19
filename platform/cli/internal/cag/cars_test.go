package cag

import (
	"bytes"
	"context"
	"os"
	"strconv"
	"strings"
	"testing"
)

// A flat, previously paired installation, followed by a second pairing through
// the normal CLI. The fake relay fingerprints the public key it actually gets.
func TestTwoCarsOnOneMac(t *testing.T) {
	app, runner, out := newTestApp(t, true)
	ctx := context.Background()
	run := func(args ...string) {
		t.Helper()
		if err := app.Run(ctx, args); err != nil {
			t.Fatalf("%v: %v", args, err)
		}
	}
	first := testBundle("raw", "127.0.0.1")
	first.DeviceLabel = "My Denza Z9GT"
	writeTestBundle(t, app, first)
	if err := app.ensureIdentity(ctx); err != nil {
		t.Fatal(err)
	}
	legacyKey, err := os.ReadFile(app.path("identity"))
	if err != nil {
		t.Fatal(err)
	}
	firstPort := app.defaultPort
	if _, err := app.localPort(firstPort); err != nil {
		t.Fatal(err)
	}
	firstPID := pretendMasterOn(t, app, runner, firstPort)
	second := first
	second.DeviceID = "fedcba9876543210"
	second.DeviceLabel = "Denza"
	second.RelayDevicePort++
	runner.carsByCode = map[string]DeviceBundle{"3456-789A": second}
	runner.adbConnectOutput = "connected to test car"
	alias := "My Denza N9"
	run("pair", "3456-789A", "--name", alias)
	cars, err := app.cars()
	if err != nil || len(cars) != 2 {
		t.Fatalf("cars: %v %v", cars, err)
	}
	n9, err := selectCar(cars, "my denza n9")
	if err != nil {
		t.Fatal(err)
	}
	newKey, err := os.ReadFile(n9.app.path("identity"))
	if err != nil || bytes.Equal(newKey, legacyKey) {
		t.Fatal("second pairing needs its own key", err)
	}
	reRead, err := os.ReadFile(app.path("identity"))
	if err != nil || !bytes.Equal(reRead, legacyKey) {
		t.Fatal("legacy identity changed", err)
	}
	if !n9.settings.Paused || n9.settings.LocalPort == firstPort {
		t.Fatalf("second settings: %+v", n9.settings)
	}
	run("list")
	if !strings.Contains(out.String(), alias) || !strings.Contains(out.String(), first.DeviceLabel) {
		t.Fatal(out.String())
	}
	out.Reset()
	run("status", n9.bundle.DeviceID)
	if !strings.Contains(out.String(), "Car: "+alias+"\n") {
		t.Fatal(out.String())
	}
	out.Reset()
	run("service", "status")
	if !strings.Contains(out.String(), alias) || !strings.Contains(out.String(), first.DeviceLabel) {
		t.Fatal(out.String())
	}
	if err := app.Run(ctx, []string{"service", "install", "--port", strconv.Itoa(firstPort)}); err == nil {
		t.Fatal("a service-wide port was accepted for two cars")
	}
	if err := app.Run(ctx, []string{"unpair"}); err == nil {
		t.Fatal("unpair silently picked a car")
	}
	if err := app.Run(ctx, []string{"disconnect"}); err == nil {
		t.Fatal("ambiguous disconnect")
	}
	if err := app.Run(ctx, []string{"connect", alias, "--port", strconv.Itoa(firstPort)}); err == nil {
		t.Fatal("took first car's port")
	}
	run("connect", alias, "--", "adb", "shell", "id")
	serial := loopbackAddress(n9.settings.LocalPort)
	adb := runner.interactiveCalls[len(runner.interactiveCalls)-1]
	if strings.Join(adb, " ") != "adb -s "+serial+" shell id" {
		t.Fatalf("wrong ADB target: %q", adb)
	}
	secondPID := runner.masterPID
	if secondPID == firstPID || !runner.procs.alive(firstPID) || !runner.procs.alive(secondPID) {
		t.Fatal("both masters should be live")
	}
	if !strings.Contains(strings.Join(runner.detachedCalls[len(runner.detachedCalls)-1], " "), "CAG_CONFIG_DIR="+n9.app.configDir) {
		t.Fatal("relay proxy lacks second car's identity directory")
	}
	run("disconnect", second.DeviceID)
	w := newTestWatcher(n9.app)
	count := len(runner.detachedCalls)
	w.step(ctx)
	if runner.procs.alive(secondPID) || !runner.procs.alive(firstPID) || len(runner.detachedCalls) != count {
		t.Fatal("disconnect affected the other car or auto-reconnected")
	}
	run("connect", alias)
	if runner.masterPID == secondPID {
		t.Fatal("did not re-enable disconnected car")
	}
	run("unpair", alias)
	if fileNonempty(n9.app.path("device.json")) || fileNonempty(n9.app.path("identity")) || runner.procs.alive(runner.masterPID) {
		t.Fatal("unpair left local access or a tunnel")
	}
	run("status")
	if !runner.procs.alive(firstPID) {
		t.Fatal("first master interrupted")
	}
}

func TestDefaultNameCollisionCanResumeWithAnAlias(t *testing.T) {
	app, runner, _ := newTestApp(t, true)
	first := testBundle("raw", "127.0.0.1")
	writeTestBundle(t, app, first)
	second := first
	second.DeviceID = "fedcba9876543210"
	second.RelayDevicePort++
	runner.carsByCode = map[string]DeviceBundle{"3456-789A": second}
	ctx := context.Background()
	if err := app.Run(ctx, []string{"pair", "3456-789A"}); err == nil {
		t.Fatal("duplicate name accepted")
	}
	if len(runner.sshCalls) != 1 {
		t.Fatal("name conflict committed a grant")
	}
	if err := app.Run(ctx, []string{"pair", "3456-789A", "--name", "Вторая машина"}); err != nil {
		t.Fatal(err)
	}
	if runner.pairSubmits != 1 {
		t.Fatal("retry resubmitted key")
	}
	if err := app.Run(ctx, []string{"forget", first.DeviceLabel}); err != nil {
		t.Fatal(err)
	}
	cars, err := app.cars()
	if err != nil || len(cars) != 1 || cars[0].name() != "Вторая машина" {
		t.Fatalf("wrong remaining cars: %v %v", cars, err)
	}
	if err := app.Run(ctx, []string{"status"}); err != nil {
		t.Fatal(err)
	}
}

func TestPairUsesVehicleNameAndRotationByIDKeepsIt(t *testing.T) {
	app, runner, out := newTestApp(t, true)
	bundle := testBundle("smart", "127.0.0.1")
	bundle.DeviceLabel = "Моя Denza N9"
	runner.carsByCode = map[string]DeviceBundle{"3456-789A": bundle, "4567-89AB": bundle}
	ctx := context.Background()
	if err := app.Run(ctx, []string{"pair", "3456-789A"}); err != nil {
		t.Fatal(err)
	}
	// A fresh invocation sees the persisted name. Replacing the key using the
	// ID preserves that name even when the label reported by the car changes.
	fresh := newForTest(runner, app.configDir, app.executable, out)
	fresh.processes = runner.procs
	bundle.DeviceLabel = "N9"
	runner.carsByCode["4567-89AB"] = bundle
	if err := fresh.Run(ctx, []string{"pair", "4567-89AB", "--new-key", "--name", bundle.DeviceID}); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if err := fresh.Run(ctx, []string{"status", "моя denza n9"}); err != nil || !strings.Contains(out.String(), "Car: Моя Denza N9") {
		t.Fatalf("name after rotation: %v %s", err, out)
	}
}
