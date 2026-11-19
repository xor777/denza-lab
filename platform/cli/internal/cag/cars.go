package cag

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"text/tabwriter"
	"unicode"
	"unicode/utf8"
)

// The original car stays in the config root, including its live SSH socket.
// Additional cars have private directories. Names are labels, never paths.
type localCar struct {
	app      *App
	bundle   DeviceBundle
	settings Settings
}

func (car localCar) name() string {
	if car.settings.Name != "" {
		return car.settings.Name
	}
	if validCarName(car.bundle.DeviceLabel) {
		return car.bundle.DeviceLabel
	}
	return car.bundle.DeviceID
}

func validCarName(s string) bool {
	return utf8.ValidString(s) && strings.TrimSpace(s) == s && s != "" &&
		!strings.HasPrefix(s, "-") && strings.IndexFunc(s, unicode.IsControl) < 0
}

func (app *App) carDirectories() ([]*App, error) {
	dirs := []*App{app}
	parent := app.path("cars")
	entries, err := os.ReadDir(parent)
	if errors.Is(err, os.ErrNotExist) {
		return dirs, nil
	}
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if e.IsDir() && strings.HasPrefix(e.Name(), "car-") {
			child := *app
			child.configDir = filepath.Join(parent, e.Name())
			child.child = true
			dirs = append(dirs, &child)
		}
	}
	return dirs, nil
}

func readCars(dirs []*App) ([]localCar, error) {
	var cars []localCar
	for _, dir := range dirs {
		if _, err := os.Stat(dir.path("device.json")); errors.Is(err, os.ErrNotExist) {
			continue
		} else if err != nil {
			return nil, err
		}
		bundle, err := dir.loadBundle()
		if err != nil {
			return nil, err
		}
		settings, err := dir.loadSettings()
		if err != nil {
			return nil, err
		}
		cars = append(cars, localCar{dir, bundle, settings})
	}
	return cars, nil
}

func (app *App) cars() ([]localCar, error) {
	dirs, err := app.carDirectories()
	if err != nil {
		return nil, err
	}
	return readCars(dirs)
}

func selectCar(cars []localCar, name string) (localCar, error) {
	if len(cars) == 0 {
		return localCar{}, errors.New("no car is paired; run: cag pair XXXX-XXXX")
	}
	if name == "" {
		if len(cars) == 1 {
			return cars[0], nil
		}
		return localCar{}, errors.New("choose a car by name or ID from cag list")
	}
	for _, car := range cars {
		if car.bundle.DeviceID == name || strings.EqualFold(car.name(), name) {
			return car, nil
		}
	}
	return localCar{}, fmt.Errorf("unknown car %q; run: cag list", name)
}

// Serialize changes to names and port reservations. Per-car actions also lock
// against that car's watcher, without holding a lock during an interactive shell.
func lockFile(path string) (*os.File, error) {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	for {
		err = syscall.Flock(int(file.Fd()), syscall.LOCK_EX)
		if err != syscall.EINTR {
			break
		}
	}
	if err != nil {
		file.Close()
		return nil, err
	}
	return file, nil
}

func (app *App) catalogLock() (*os.File, error) {
	if err := os.MkdirAll(app.configDir, 0o700); err != nil {
		return nil, err
	}
	return lockFile(app.path("cars.lock"))
}

func (app *App) listCars(ctx context.Context) error {
	lock, err := app.catalogLock()
	if err != nil {
		return err
	}
	defer lock.Close()
	cars, err := app.cars()
	if err != nil {
		return err
	}
	return app.printCars(ctx, cars)
}

func (app *App) printCars(ctx context.Context, cars []localCar) error {
	if len(cars) == 0 {
		fmt.Fprintln(app.stdout, "No paired cars.")
		return nil
	}
	sort.Slice(cars, func(i, j int) bool { return cars[i].name() < cars[j].name() })
	w := tabwriter.NewWriter(app.stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(w, "NAME\tID\tLOCAL ADDRESS\tSTATE")
	for _, car := range cars {
		port, err := car.app.localPort(0)
		if err != nil {
			return err
		}
		master := car.app.masterState(ctx, car.bundle)
		state := "disconnected"
		if master.Answers && master.holds(port) {
			state = "connected"
		} else if master.Running {
			state = "reconnecting"
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", car.name(), car.bundle.DeviceID, loopbackAddress(port), state)
	}
	return w.Flush()
}

func parsePair(args []string) (code, name string, rotate bool, err error) {
	for i := 0; i < len(args); i++ {
		switch {
		case args[i] == "--name" && i+1 < len(args) && name == "":
			i++
			name = args[i]
			if !validCarName(name) {
				return "", "", false, errors.New("choose a nonempty car name without leading or trailing space, control characters or a leading dash")
			}
		case args[i] == "--new-key" && !rotate:
			rotate = true
		case code == "" && !strings.HasPrefix(args[i], "-"):
			code = args[i]
		default:
			return "", "", false, errors.New("usage: cag pair CODE [--name NAME] [--new-key]")
		}
	}
	code, err = normalizeCode(code)
	return
}

func (app *App) pairCar(ctx context.Context, args []string) error {
	code, name, rotate, err := parsePair(args)
	if err != nil {
		return err
	}
	lock, err := app.catalogLock()
	if err != nil {
		return err
	}
	defer lock.Close()
	dirs, err := app.carDirectories()
	if err != nil {
		return err
	}
	cars, err := readCars(dirs)
	if err != nil {
		return err
	}
	var target, empty *App
	// A retry must use the same private key, even if it omits --new-key.
	for _, dir := range dirs {
		pending, _, _, err := dir.loadPendingPair(codeHash(code))
		if errors.Is(err, errOtherPairing) {
			continue
		}
		if err != nil {
			return err
		}
		if pending != nil {
			target = dir
			break
		}
		if _, err := os.Stat(dir.path("device.json")); errors.Is(err, os.ErrNotExist) && empty == nil {
			empty = dir
		}
	}
	resuming := target != nil
	if target == nil && (name != "" || rotate) {
		car, lookupErr := selectCar(cars, name)
		if lookupErr == nil {
			if !rotate {
				return fmt.Errorf("car name %q is already in use; choose another --name", name)
			}
			target = car.app
		} else if rotate && len(cars) > 0 {
			return lookupErr
		}
	}
	if target == nil {
		target = empty
	}
	if target == nil {
		parent := app.path("cars")
		if err := os.MkdirAll(parent, 0o700); err != nil {
			return err
		}
		path, err := os.MkdirTemp(parent, "car-")
		if err != nil {
			return err
		}
		child := *app
		child.configDir = path
		child.child = true
		target = &child
	}
	action, err := lockFile(target.path("action.lock"))
	if err != nil {
		return err
	}
	defer action.Close()
	settings, err := target.loadSettings()
	if err != nil {
		return err
	}
	err = target.pair(ctx, code, rotate, func(bundle DeviceBundle) error {
		label := name
		if label == "" && resuming {
			label = settings.Name
		}
		if label == "" {
			label = (localCar{bundle: bundle}).name()
		}
		for _, car := range cars {
			if car.app.configDir == target.configDir {
				if car.bundle.DeviceID != bundle.DeviceID {
					return fmt.Errorf("%q is paired to a different vehicle; choose another --name", car.name())
				}
				// --name is a selector during rotation and may contain the ID.
				settings.Name = car.name()
				if settings.Name == car.settings.Name {
					return nil
				}
				return target.saveSettings(settings)
			}
		}
		for _, car := range cars {
			if car.bundle.DeviceID == bundle.DeviceID {
				return fmt.Errorf("this car is already paired as %q; to replace its key use a fresh code with --new-key --name %q", car.name(), car.name())
			}
			if strings.EqualFold(car.name(), label) || strings.EqualFold(car.bundle.DeviceID, label) || strings.EqualFold(car.name(), bundle.DeviceID) {
				return fmt.Errorf("car name %q conflicts with an existing car; retry with a distinct --name", label)
			}
		}
		settings.Name = label
		if len(cars) > 0 && !fileNonempty(target.path("device.json")) {
			port, err := app.availableCarPort(cars)
			if err != nil {
				return err
			}
			settings.LocalPort = port
			settings.Paused = true
		}
		return target.saveSettings(settings)
	})
	if err == nil {
		fmt.Fprintf(app.stdout, "Paired with %s.\n", settings.Name)
	}
	return err
}

func (app *App) availableCarPort(cars []localCar) (int, error) {
	first, err := app.localPort(0)
	if err != nil {
		return 0, err
	}
	reserved := map[int]bool{}
	for _, car := range cars {
		port, err := car.app.localPort(0)
		if err != nil {
			return 0, err
		}
		reserved[port] = true
	}
	for n := 0; n <= 65535-1024; n++ {
		port := 1024 + (first-1024+n)%(65536-1024)
		if !reserved[port] && localPortFree(port) {
			return port, nil
		}
	}
	return 0, errors.New("no local port is available")
}

func (app *App) unpair(ctx context.Context) error {
	if _, err := app.closeAnyMaster(ctx); err != nil {
		return err
	}
	// device.json goes last: an interrupted removal remains addressable for retry.
	for _, file := range []string{"identity", "identity.pub", "identity.next", "identity.next.pub", "pending-pair.json", "pending-device.json", "known_hosts", "device.json"} {
		if err := os.Remove(app.path(file)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	if err := os.Remove(app.path(settingsName)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := syncDirectory(app.configDir); err != nil {
		return err
	}
	fmt.Fprintln(app.stdout, "Unpaired on this computer.")
	return nil
}

// service install --port is the single-car form, also valid before the first pairing.
func (app *App) setServicePort(port int) error {
	lock, err := app.catalogLock()
	if err != nil {
		return err
	}
	defer lock.Close()
	cars, err := app.cars()
	if err != nil {
		return err
	}
	if len(cars) > 1 {
		return errors.New("use cag connect NAME --port N to change one car's port")
	}
	target := app
	if len(cars) == 1 {
		target = cars[0].app
	}
	action, err := lockFile(target.path("action.lock"))
	if err != nil {
		return err
	}
	defer action.Close()
	_, err = target.localPort(port)
	return err
}

func (app *App) carCommand(ctx context.Context, command, name string, options connectOptions) error {
	catalog, err := app.catalogLock()
	if err != nil {
		return err
	}
	defer func() {
		if catalog != nil {
			catalog.Close()
		}
	}()
	cars, err := app.cars()
	if err != nil {
		return err
	}
	car, err := selectCar(cars, name)
	if err != nil {
		return err
	}
	target := car.app
	action, err := lockFile(target.path("action.lock"))
	if err != nil {
		return err
	}
	defer func() {
		if action != nil {
			action.Close()
		}
	}()
	if command == "status" {
		return target.status(ctx, car.bundle, car.name())
	}
	settings := car.settings
	if options.port != 0 {
		for _, other := range cars {
			if other.app.configDir == target.configDir {
				continue
			}
			p, err := other.app.localPort(0)
			if err != nil {
				return err
			}
			if options.port == p {
				return fmt.Errorf("port %d is reserved for %q", p, other.name())
			}
		}
		settings.LocalPort = options.port
	}
	settings.Paused = command != "connect"
	if settings != car.settings {
		if err := target.saveSettings(settings); err != nil {
			return err
		}
	}
	if command == "unpair" || command == "forget" {
		return target.unpair(ctx)
	}
	catalog.Close()
	catalog = nil
	if command == "disconnect" {
		return target.disconnect(ctx)
	}
	port, err := target.localPort(0)
	if err != nil {
		return err
	}
	if err := target.ensureIdentity(ctx); err != nil {
		return err
	}
	if _, err := target.startTunnel(ctx, car.bundle, port); err != nil {
		return err
	}
	action.Close()
	action = nil
	return target.connectedCommand(ctx, car.bundle, port, options)
}
