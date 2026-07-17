#!/usr/bin/env bash
# Bootstrap an Android emulator ready for Anchor development.
#
# Creates the AVD (if missing), fixes its config for usable performance
# (host GPU, hardware keyboard), boots it, sets a screen-lock PIN, and
# enrolls a virtual fingerprint so Anchor's biometric-gated key storage
# works. Safe to re-run: every step is skipped if already done.
#
# Host prerequisites (script checks the first two):
#   - Android SDK cmdline tools on PATH: avdmanager, emulator, adb
#   - System image installed:  sdkmanager --install "system-images;android-35;google_apis;x86_64"
#   - KVM available (/dev/kvm) and a GPU render node (/dev/dri) for
#     hardware acceleration; a running X11/Wayland session for the window
#
# After setup, answer any fingerprint prompt in the app with:
#   adb emu finger touch 1
set -euo pipefail

AVD_NAME="${AVD_NAME:-anchor}"
SYS_IMAGE="system-images;android-35;google_apis;x86_64"
DEVICE="pixel_9"
PIN="1111"
FINGER_ID=1

log() { printf '\n==> %s\n' "$*"; }

for tool in avdmanager emulator adb; do
    command -v "$tool" >/dev/null || { echo "ERROR: $tool not found on PATH" >&2; exit 1; }
done

# --- AVD creation ---------------------------------------------------------
avd_dir="$HOME/.android/avd/${AVD_NAME}.avd"
if avdmanager list avd -c 2>/dev/null | grep -qx "$AVD_NAME"; then
    log "AVD '$AVD_NAME' already exists"
else
    log "Creating AVD '$AVD_NAME' ($SYS_IMAGE, $DEVICE)"
    echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYS_IMAGE" -d "$DEVICE" || {
        echo "ERROR: AVD creation failed. Is the system image installed?" >&2
        echo "  sdkmanager --install \"$SYS_IMAGE\"" >&2
        exit 1
    }
fi

# --- AVD config: host GPU + hardware keyboard -----------------------------
# hw.gpu.enabled=no means software rendering of the whole screen — the
# emulator crawls on any machine. hw.keyboard=yes lets you type with the
# host keyboard instead of the on-screen one.
config="$avd_dir/config.ini"
set_ini() {
    local key="$1" value="$2"
    if grep -q "^${key}=" "$config"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$config"
    else
        echo "${key}=${value}" >> "$config"
    fi
}
log "Ensuring AVD config: host GPU, hardware keyboard"
set_ini hw.gpu.enabled yes
set_ini hw.gpu.mode host
set_ini hw.keyboard yes

# --- Boot ------------------------------------------------------------------
if adb get-state >/dev/null 2>&1; then
    log "A device/emulator is already connected"
else
    log "Booting emulator (window should appear on your display)"
    nohup emulator -avd "$AVD_NAME" -gpu host -no-snapshot-save >/tmp/anchor-emulator.log 2>&1 &
    disown
fi

log "Waiting for boot to complete"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
log "Booted: Android $(adb shell getprop ro.build.version.release | tr -d '\r')"

# --- Quality-of-life defaults ----------------------------------------------
# The emulator registers virtual stylus input devices, so a fresh AVD shows
# a one-shot "try your stylus" education popup on the first text-field
# focus. Disabling stylus handwriting preempts it.
log "Disabling stylus handwriting (suppresses first-focus stylus promo)"
adb shell settings put secure stylus_handwriting_enabled 0

# The emulator always reports as charging, so "stay awake while plugged in"
# stops the screen from sleeping and locking between interactions.
log "Keeping screen awake"
adb shell settings put global stay_on_while_plugged_in 7

# --- Fingerprint enrollment ------------------------------------------------
# Anchor gates SSH keys behind BiometricPrompt; with no fingerprint
# enrolled, key generation is unavailable. Enrollment needs a screen-lock
# PIN first, then simulated sensor touches, driven through the Settings
# wizard via uiautomator.
if adb shell dumpsys fingerprint 2>/dev/null | grep -q '"prints":\[{'; then
    log "Fingerprint already enrolled — done"
    exit 0
fi

log "Setting screen-lock PIN ($PIN)"
adb shell locksettings set-pin "$PIN" >/dev/null 2>&1 || \
    log "PIN already set (leaving it alone; assuming it is $PIN)"

# Dump the current UI and tap the center of the first element whose text
# matches the given regex. Returns nonzero if not found.
tap_text() {
    local pattern="$1" bounds
    bounds=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null \
        | grep -oE "<node[^>]*text=\"${pattern}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
        | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    [ -n "$bounds" ] || return 1
    local x y
    x=$(echo "$bounds" | grep -oE '[0-9]+' | awk 'NR==1{a=$0} NR==3{print int((a+$0)/2)}')
    y=$(echo "$bounds" | grep -oE '[0-9]+' | awk 'NR==2{a=$0} NR==4{print int((a+$0)/2)}')
    adb shell input tap "$x" "$y"
}

screen_has() {
    adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -qE "text=\"$1\""
}

log "Launching fingerprint enrollment wizard"
adb shell am start -a android.settings.BIOMETRIC_ENROLL >/dev/null
sleep 3

# Confirm PIN if the wizard asks for it
if screen_has "Enter your (PIN|device PIN)( to continue)?"; then
    adb shell input text "$PIN"
    adb shell input keyevent 66
    sleep 2
fi

# Walk the wizard: tap through intro buttons, send sensor touches at the
# scanning step, until "Fingerprint added" appears. Button labels are from
# the android-35 google_apis image; other images may vary.
log "Walking enrollment wizard (this takes ~20s)"
for _ in $(seq 1 30); do
    if screen_has "Fingerprint added"; then
        tap_text "DONE" || true
        log "Fingerprint enrolled. Answer prompts with: adb emu finger touch $FINGER_ID"
        exit 0
    fi
    tap_text "(MORE|I AGREE|NEXT|AGREE)" || adb emu finger touch "$FINGER_ID" >/dev/null 2>&1
    sleep 2
done

echo "ERROR: enrollment did not finish; drive the wizard manually in the emulator window (simulate touches with: adb emu finger touch $FINGER_ID)" >&2
exit 1
