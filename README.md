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

The emulator runs on the host (not in Docker) for GPU/KVM support. Install
the emulator and system image once:

```sh
sdkmanager --install "emulator" "system-images;android-35;google_apis;x86_64"
```

Then bootstrap everything else with:

```sh
./scripts/setup-emulator.sh
```

The script is idempotent and handles the parts that are easy to get wrong:

- Creates the `anchor` AVD (pixel_9, android-35) if it doesn't exist
- Forces **host GPU rendering** (`hw.gpu.enabled=yes`, `hw.gpu.mode=host`) —
  without this the emulator software-renders the entire screen and crawls
  even on fast machines — and enables the hardware keyboard
- Boots the emulator and waits for it
- Sets a screen-lock PIN (`1111`) and **enrolls a virtual fingerprint** by
  driving the Settings wizard via uiautomator. Without an enrolled
  fingerprint, Anchor's biometric-gated key generation silently refuses to
  run.
- Disables the first-focus stylus promo popup and keeps the screen awake
  so the emulator never sleeps or re-locks between interactions

Whenever the app shows a fingerprint prompt, simulate a sensor touch with:

```sh
adb emu finger touch 1
```

To test against the host machine from inside the emulator, use hostname
`10.0.2.2` (the emulator's alias for the host loopback).

## Usage

1. **Set up SSH key** — tap the key icon in the top bar, generate a key, then deploy it to a saved host
2. **Add a host** — tap the + button, enter label/hostname/username
3. **Connect** — tap a saved host to connect and see its tmux sessions
4. **View a session** — tap a session to see its pane content; type commands in the input bar at the bottom

## Known issues

### Tmux window resizing

Anchor resizes the tmux window to match your phone's screen width so content fits without overflow. This means if you have the same tmux session open on a desktop terminal, it will appear narrow while the phone is actively viewing it.

When you leave the session view or disconnect, Anchor automatically restores automatic sizing (it unsets the per-window `window-size` option that `tmux resize-window` implicitly sets to `manual`), so attached desktop terminals snap back to their own size.

If Anchor is killed while viewing a session (so the restore never runs), windows stay stuck at the phone size. Fix it from any shell on the host:

```
tmux list-windows -t <session> -F '#{window_id}' | xargs -I{} tmux set-option -w -t {} -u window-size
```

Note that `tmux resize-window -A` is not sufficient — it restores the size once but leaves `window-size` set to `manual`, so auto-resize stays broken.

## Architecture

- **Kotlin + Jetpack Compose** — Material 3 UI
- **JSch (mwiede fork)** — SSH connections
- **BouncyCastle** — ECDSA key generation
- **Room** — local host database
- **Stateless polling** — captures pane content periodically via `tmux capture-pane -p`; no persistent terminal session or mosh required

## License

MIT — see [LICENSE](LICENSE).
