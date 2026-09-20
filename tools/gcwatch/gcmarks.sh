#!/bin/bash
# Writes the events worth lining a CPU curve up against, so the curve reads on its own later.
#
# A rate that climbs tells you something went wrong. A rate that climbs four seconds after a game
# quit tells you what. Marking those by hand means remembering to, in the middle of playing, and
# the one that matters is always the one nobody marked.
#
# Three things are watched: any Steam game process starting or stopping, Steam's Virtual GamePad
# appearing or going away (that is Steam Input engaging, and it is the suspect), and the count of
# HID gamepads macOS holds.
OUT="$HOME/Library/Logs/thor-gcwatch/gccpu.log"
mkdir -p "$(dirname "$OUT")"
say() { echo "$(date '+%F %T')  == $*" >> "$OUT"; }

# Only lines that ARE an executable path, not lines that merely mention one: a shell command
# carrying this very pattern in its arguments otherwise reports itself as the running game.
# Only lines that ARE an executable path, not lines that merely mention one: a shell command
# carrying this very pattern in its arguments otherwise reports itself as the running game. The
# path may well contain spaces — this user's library is "/Volumes/Mac SSD/Steam Games Mac" — so
# anchoring on a run of non-spaces silently matches nothing, which reads exactly like no game.
game() { ps -eo args | grep -E "^/.*steamapps/common/" | grep -viE "steamwebhelper|Steam Helper" \
         | sed -E 's|^/.*steamapps/common/([^/]+)/.*|\1|' | sort -u | paste -sd, - ; }

# Steam's pad is not called "Virtual GamePad" anywhere macOS can see. It arrives as GamePad-1
# claiming Microsoft's 360 identity, and the only honest marker of it is the registry flag.
vpad() { ioreg -r -c IOHIDDevice 2>/dev/null | grep -c '"HIDVirtualDevice" = Yes' ; }

trap 'say "marker stopped"; exit 0' TERM INT
say "marker started (pid $$)"
lg=$(game); lv=$(vpad)
say "at start: game=[${lg:-none}] steam virtual gamepad=$lv"
while true; do
  g=$(game); v=$(vpad)
  [ "$g" != "$lg" ] && { say "GAME: [${lg:-none}] -> [${g:-none}]"; lg=$g; }
  [ "$v" != "$lv" ] && { say "STEAM VIRTUAL GAMEPAD: $lv -> $v"; lv=$v; }
  sleep 3
done
