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

Then pair and connect:

```bash
cag pair XXXX-XXXX
cag connect
cag connect -- adb devices
cag connect -- adb shell
cag status
cag disconnect
```

State is stored in the operating system's user config directory: `~/.config/cag`
on Linux (respecting `$XDG_CONFIG_HOME`) and `~/Library/Application Support/cag`
on macOS. `$CAG_CONFIG_DIR` selects an explicit location. The directory contains
the client key, pinned relay and vehicle host keys, the active device bundle, and
the SSH control socket. Pairing writes a new active bundle and host key only after
the vehicle confirms the replacement. A failed or expired replacement therefore
does not damage an existing pairing.

`cag connect` leaves a keepalive-protected SSH tunnel in the background. The
`disconnect` command closes this Mac's tunnel; it does not disable the relay on
the vehicle. Use the vehicle UI when remote access must stay disabled after a
reboot.
