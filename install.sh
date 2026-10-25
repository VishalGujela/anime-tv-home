#!/usr/bin/env bash
# Installs Anime Home on an Android TV over the network and makes it the default home screen.
#
#   ./install.sh <tv-ip>[:port]
#   curl -fsSL https://raw.githubusercontent.com/VishalGujela/anime-tv-home/main/install.sh | bash -s -- <tv-ip>
#
#   ./install.sh <tv-ip> --uninstall     # remove Anime Home again
#
# The TV needs Developer options with network (or USB) debugging turned on.
set -euo pipefail

REPO="VishalGujela/anime-tv-home"
PKG="com.vishal.animehome"
APK_URL="https://github.com/$REPO/releases/latest/download/anime-home.apk"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

TV="${1:-}"
MODE="${2:-install}"
[[ -n "$TV" ]] || fail "usage: install.sh <tv-ip>[:port] [--uninstall]"
[[ "$TV" == *:* ]] || TV="$TV:5555"

if ! command -v adb >/dev/null 2>&1; then
  fail "adb is not installed. macOS: 'brew install --cask android-platform-tools'  Debian/Ubuntu: 'sudo apt install adb'
       Windows: download Android SDK Platform-Tools from https://developer.android.com/tools/releases/platform-tools"
fi

# adb must never read stdin: with `curl ... | bash` the rest of this script *is* stdin.
tv() { adb -s "$TV" "$@" </dev/null; }

say "Connecting to $TV"
connected=""
for i in $(seq 1 30); do
  adb connect "$TV" >/dev/null 2>&1 </dev/null || true
  state="$(adb -s "$TV" get-state 2>&1 </dev/null || true)"
  if [[ "$state" == "device" ]]; then connected=1; break; fi
  if [[ "$state" == *unauthorized* || $i -eq 1 ]]; then
    say "If the TV shows 'Allow debugging?', choose 'Always allow from this computer' and press OK."
  fi
  sleep 2
done
[[ -n "$connected" ]] || fail "could not connect to $TV. Check the IP address, that the TV and this computer are on the same network,
       and that network debugging is on (Settings > Device Preferences > Developer options)."

if [[ "$MODE" == "--uninstall" ]]; then
  say "Removing Anime Home"
  tv uninstall "$PKG" || true
  say "Done. Press Home on the remote and pick your previous launcher if the TV asks."
  exit 0
fi

sdk="$(tv shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" -ge 28 ]] || fail "Anime Home needs Android 9 or newer (this device reports SDK '$sdk')."
if ! tv shell pm has-feature android.software.leanback | grep -q true; then
  say "Warning: this device doesn't report itself as an Android TV. Continuing anyway."
fi

previous="$(tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')"
say "Current home screen: $previous"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
say "Downloading the latest Anime Home release"
curl -fsSL -o "$tmp/anime-home.apk" "$APK_URL" || fail "download failed: $APK_URL"

say "Installing on the TV"
tv install -r "$tmp/anime-home.apk" | tail -1

say "Making Anime Home the default home screen"
tv shell cmd package set-home-activity "$PKG/.Home" >/dev/null
tv shell input keyevent KEYCODE_HOME
sleep 2

home="$(tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')"
[[ "$home" == "$PKG/"* ]] || fail "installed, but the TV did not accept Anime Home as the default home (it reports '$home')."

cat <<EOF

Anime Home is installed and is now your home screen.

  Undo (go back to your previous home screen):
    adb -s $TV shell cmd package set-home-activity $previous
  Remove completely:
    curl -fsSL https://raw.githubusercontent.com/$REPO/main/install.sh | bash -s -- $TV --uninstall

Tip: once you're done, turn network debugging off again on the TV
(Settings > Device Preferences > Developer options).
EOF
