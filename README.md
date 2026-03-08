# Anchor

A mobile-first Android app for monitoring and interacting with remote tmux sessions over SSH. Not a terminal emulator — a chat-like interface that views tmux pane content via `tmux capture-pane` and sends input via `tmux send-keys`.

## Features

- **Managed host list** — save hosts with label, hostname, port, and username; persisted locally via Room database
- **SSH key management** — generate ECDSA keys and deploy them to saved hosts via password auth
- **Host key verification** — fingerprint checking with persistent known_hosts storage; warns on new or changed keys
- **Tmux session browser** — lists active tmux sessions on the connected host
- **Session viewer** — chat-style display of tmux pane content with a command input bar
- **Adaptive pane sizing** — resizes the tmux window to fit your phone screen based on font size and orientation
- **Configurable font size** — adjustable in-session with +/- controls; persists across sessions

## Requirements

- Android 8.0+ (API 26)
- A remote server with tmux installed and SSH access

## Building

The build environment runs in Docker:

```sh
docker compose up -d
docker compose exec build ./gradlew assembleDebug
```

Install to a connected device or emulator:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Emulator setup (Linux with KVM)

The emulator runs on the host (not in Docker) for GPU/KVM support:

```sh
# Install emulator and system image
sdkmanager --install "emulator" "system-images;android-35;google_apis;x86_64"

# Create AVD
avdmanager create avd -n anchor_test -k "system-images;android-35;google_apis;x86_64" -d "pixel_9"

# Run (enable keyboard input)
emulator -avd anchor_test -gpu auto
```

To enable hardware keyboard in the emulator, add `hw.keyboard = yes` to `~/.android/avd/anchor_test.avd/config.ini`.

## Usage

1. **Set up SSH key** — tap the key icon in the top bar, generate a key, then deploy it to a saved host
2. **Add a host** — tap the + button, enter label/hostname/username
3. **Connect** — tap a saved host to connect and see its tmux sessions
4. **View a session** — tap a session to see its pane content; type commands in the input bar at the bottom

## Known issues

### Tmux window resizing

Anchor resizes the tmux window to match your phone's screen width so content fits without overflow. This means if you have the same tmux session open on a desktop terminal, it will appear narrow while the phone is actively viewing it.

To restore your desktop terminal size, detach and reattach:

```
Ctrl-b d
tmux attach -t <session>
```

Or from any shell with tmux attached at your desired size:

```
tmux resize-window -A -t <session>
```

## Architecture

- **Kotlin + Jetpack Compose** — Material 3 UI
- **JSch (mwiede fork)** — SSH connections
- **BouncyCastle** — ECDSA key generation
- **Room** — local host database
- **Stateless polling** — captures pane content periodically via `tmux capture-pane -p`; no persistent terminal session or mosh required

## License

TBD
