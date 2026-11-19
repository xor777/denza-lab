# `cag` for macOS and Linux

The CLI is a small Go binary for macOS and Linux. At runtime it uses the system
OpenSSH client plus Android Platform Tools for ADB. It never changes
`~/.ssh/config`.

Build and install it somewhere on `PATH`, for example:

```bash
cd platform/cli
go build -o cag ./cmd/cag
install -m 0755 cag /usr/local/bin/cag
```

Enter the pairing code shown by the vehicle. Give it a name on this computer
with `--name`, or let the CLI use the name supplied by the vehicle.

```bash
cag pair XXXX-XXXX --name "My Denza N9"
cag list
cag connect "My Denza N9"
cag connect "My Denza N9" -- adb shell
cag status "My Denza N9"
cag disconnect "My Denza N9"
```

Use the same quoted name for each command. Names support spaces and Unicode,
and match case-insensitively. The device ID from `cag list` also works. When
only one car is paired, `connect`, `status` and `disconnect` can omit its name.
With several paired cars these commands require a selection. If two vehicles
report the same name, repeat the pairing command with a distinct `--name`.

`cag unpair "My Denza N9"` closes that tunnel and removes that car's pairing
and private key from this computer. It requires an explicit name or ID.
Reconnecting after unpairing requires a fresh pairing code from the vehicle.
The vehicle retains its relay registration. To revoke access from the vehicle,
use its remote-access or trusted-computer replacement controls.

State is stored in the operating system's user config directory: `~/.config/cag`
on Linux (respecting `$XDG_CONFIG_HOME`) and `~/Library/Application Support/cag`
on macOS. `$CAG_CONFIG_DIR` selects an explicit location. The directory contains
the client key, pinned relay and vehicle host keys, the active device bundle, and
the SSH control socket. Pairing writes a temporary bundle and a SHA-256 hash of
the code, so repeating `cag pair CODE` resumes an interrupted inner handshake
without another relay submit. `--new-key` stages a new desktop key and promotes
it only after vehicle confirmation. To rotate one car's key, use
`cag pair CODE --new-key --name "My Denza N9"`. If replacement fails or expires, the
previous local identity and active bundle remain usable. After success,
`known_hosts` contains only the relay pin list and that vehicle's host key.
The first pairing keeps its original files and socket. Each additional pairing
gets its own private directory and key.

Every `ssh` call runs with `-F none`, so `~/.ssh/config` cannot change it, and
paths are quoted for OpenSSH because the macOS directory name contains a space.
The background tunnel writes its messages to `tunnel.log` and the relay leg of
each inner connection to `proxy.log`, both in the same directory; their tails
appear in `cag` error messages.

`cag connect` leaves a keepalive-protected SSH tunnel in the background on a
fixed local port. The first defaults to 15555 or keeps its existing port.
Each additional car receives an available port at pairing and waits for its
first `connect`. `cag connect "My Denza N9" --port N` sets its remembered
port explicitly. Ports reserved for other paired cars are unavailable even
while disconnected. In raw-adbd mode the ADB serial is
`127.0.0.1:N`; in smart-socket mode `127.0.0.1:N` is the car's ADB server
(`ADB_SERVER_SOCKET=tcp:127.0.0.1:N`). A live tunnel of this config on the port
is reused and one on another port is moved. A port held by any other program is
an error, never a reason to pick another port; `cag status` shows the serial and
says when the port is taken. The `disconnect` command closes this computer's
tunnel; it does not disable the relay on the vehicle. Use the vehicle UI when
remote access must stay disabled after a reboot.

`cag connect --watch` is a foreground supervisor. Every 10 seconds it checks each
enabled car independently and, in raw mode, that `adb devices` lists its serial as `device`. A dead
tunnel is reopened with jittered backoff from 1 to 10 seconds, but only while
the port is free; while another program holds it, the watch waits. A missing or
offline serial gets `adb connect`. The watch never stops or disconnects
anything it did not start, closes its own tunnel on SIGTERM, and writes one
line per state change to `watch.log`. Only one watch runs per config directory:
it holds `watch.lock`, and a second one exits at once.

The tunnel's PID is kept in `master.pid` next to `local-port`. Its files are
removed only after that process has exited, so a tunnel whose control socket
broke is still found, stopped by `cag disconnect` or `cag service uninstall`,
and never mistaken for another program on the port. The watch gives a tunnel
that runs but does not answer two more checks, then stops it and opens a new
one.

`cag service install` runs the watch at login: a launchd agent
`~/Library/LaunchAgents/ru.adbgw.cag.plist` on macOS, a `systemd --user` unit
`cag.service` on Linux. It records this binary's absolute path, the config
directory, and a `PATH` that starts with the directory of the `adb` found at
install time; output goes to `service.log`. With zero or one car,
`cag service install --port N` also remembers the port.
`cag disconnect NAME` pauses that car across service and computer restarts.
`cag connect NAME` enables it again. `cag service status` shows the service
state. `cag service uninstall` stops the supervisor and closes its configured
tunnels. On Linux a user unit stops at logout unless
lingering is enabled (`loginctl enable-linger`).
