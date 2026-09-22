#!/bin/bash
# One reading of everything that decides whether the pad feels right, in a form that can be
# compared against another reading months later.
#
# Written on 2026-09-22, the day the pad first felt like real hardware, so that "it feels worse
# than it used to" can be answered with numbers instead of memory. Run it while playing, not
# while idle: several of these are only meaningful when reports are actually moving.
#
#   tools/linkbench/linkbench.sh > some-file.txt
#
# Every number here has a known-good value recorded beside it in baseline-2026-09-22.txt.
set -u
D="${SIDEPAD_DEVICE:-$(adb devices | sed 1d | awk '/\tdevice/{print $1; exit}')}"
MAC_LOG="$HOME/Library/Logs/thor-sidepad/macpair.log"

say() { printf '\n== %s\n' "$1"; }

printf '# link benchmark  %s\n' "$(date '+%F %T %Z')"

say "what is running"
printf '   commit        %s\n' "$(git -C "$(dirname "$0")/../.." log --oneline -1 2>/dev/null)"
printf '   installed     %s\n' "$(adb -s "$D" shell dumpsys package dev.lbento.thorsidepad 2>/dev/null | awk -F= '/versionName/{print $2; exit}' | tr -d '\r')"
printf '   handheld      %s\n' "$(adb -s "$D" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

say "the link, as the host describes it"
# The interval is the number everything else follows from. macOS opens every connection at 30 ms
# and decides within a second or two whether to apply the pad's preference.
grep -E 'macLeDeviceConnectionParameters' "$MAC_LOG" 2>/dev/null | grep -i odin | tail -3 |
  sed -E 's/.*Server.HID\] macLeDeviceConnectionParameters?(Updated|OverrideByPeer): /   /' | cut -c1-108
printf '   disconnects in the log      %s\n' "$(grep -c 'BLE Disconnected' "$MAC_LOG" 2>/dev/null || echo '?')"

say "the pad getting ahead of the radio (all of these want to be zero)"
LOG="$(adb -s "$D" logcat -d 2>/dev/null)"
printf '   refusals / short gaps       %s\n' "$(printf '%s' "$LOG" | grep -c 'running ahead of the radio')"
printf '   reports dropped             %s\n' "$(printf '%s' "$LOG" | grep -c 'report dropped')"
printf '   stack congestion events     %s\n' "$(printf '%s' "$LOG" | grep -c 'onServerCongestion.*congested=true')"
printf '   link lost by the pad        %s\n' "$(printf '%s' "$LOG" | grep -c 'went away (status')"

say "what the host holds"
printf '   gamepads seen by macOS:\n'
ioreg -r -c IOHIDDevice 2>/dev/null | python3 "$(dirname "$0")/pads.py" 2>/dev/null | sed 's/^/   /'
printf '   gamecontrollerd             %s\n' "$(ps -eo state,etime,comm | awk '/gamecontrollerd/{printf "state %s, up %s", $1, $2}')"

say "the radio it shares the band with"
system_profiler SPAirPortDataType 2>/dev/null |
  awk '/Current Network Information/{f=1} f&&/Channel:|Signal \/ Noise:/{print "   "$0; n++} n>=2{exit}'
printf '   awdl0                       %s\n' "$(ifconfig awdl0 2>/dev/null | head -1 | grep -o RUNNING || echo down)"
