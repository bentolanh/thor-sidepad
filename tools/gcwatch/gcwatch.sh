#!/bin/bash
# Records what happens around gamecontrollerd, because the unified log keeps nothing by the time
# we go looking afterwards. Two things run together: a filtered live stream of Bluetooth/HID/
# GameController messages, and a sampler that notices the daemon going unresponsive and marks it.
#
# The writing is done by gcfilter.pl rather than a shell redirect. Three bugs came from doing it
# in the shell, every one of them silent; they are written up at the top of that file.
HERE="$(cd "$(dirname "$0")" && pwd)"
FILTER="$HERE/gcfilter.pl"
LOGDIR="$HOME/Library/Logs/thor-gcwatch"
mkdir -p "$LOGDIR"
DAY=$(date +%F)
STATE="$LOGDIR/state-$DAY.log"

find "$LOGDIR" -name '*.log' -mtime +7 -delete 2>/dev/null

say() { echo "$(date '+%F %T')  $*" >> "$STATE"; }

# bluetoothd narrates its radio housekeeping several times a second. None of it concerns us, and
# left in it buries the handful of lines that do. Scanning chatter, the XPC handshake every client
# makes, and CoreBluetooth's multi-line argument dumps: all constant, none of it marks an event.
#
# "Incoming Driver Connection" used to be in this list and must never go back. There are thousands
# of them during a fault and that is exactly the finding — gcfilter.pl counts them and writes a
# rate line, which is the whole signal at none of the volume.
NOISE="Desense|WLAN Status|RSSI|kBTHCI|inquiry|Advertis(ing|ement) (report|data)|LEScan|batteryLevel|Sniff|clock offset"
NOISE="$NOISE|Server\.LE\.Scan|FindMy|com\.apple\.xpc:connection"
NOISE="$NOISE|Connection Invalidated|kCBMsgArg|kCBAdvOption|^[[:space:]]*[{}();]*[[:space:]]*$"
export GCW_DIR="$LOGDIR" GCW_NOISE="$NOISE"

# Self-test, same reasoning as driverflood.sh: a watcher that cannot be shown to fire is not a
# watcher. This one silently filtered away the signature it existed to catch.
if [ "$1" = "--selftest" ]; then
  T=$(mktemp -d); D=$(date +%F)
  GCW_DIR="$T" GCW_EVERY=1 perl "$FILTER" <<< "$(
    for i in $(seq 1 600); do echo 'Incoming Driver Connection driver.peer[9999]'; done
    echo 'GCController did connect'; echo 'bluetoothd: RSSI Desense chatter')"
  ok=0
  grep -q 'driver connections in' "$T/events-$D.log" 2>/dev/null || { echo "FAIL: flood not counted"; ok=1; }
  grep -q 'did connect'           "$T/events-$D.log" 2>/dev/null || { echo "FAIL: real line dropped"; ok=1; }
  grep -q 'Desense'               "$T/events-$D.log" 2>/dev/null && { echo "FAIL: noise kept"; ok=1; }
  [ "$ok" -eq 0 ] && { echo "SELFTEST PASS:"; grep 'driver connections in' "$T/events-$D.log"; }
  rm -rf "$T"; exit $ok
fi

# Private-data redaction hides some names, but connect and disconnect timing is what we are after
# and that comes through. /usr/bin/log by full path: bare `log` can resolve to something else.
/usr/bin/log stream --style compact --predicate '
  process == "bluetoothd" OR process == "gamecontrollerd"
  OR eventMessage CONTAINS[c] "HOGP" OR eventMessage CONTAINS[c] "IOHIDDevice"
  OR eventMessage CONTAINS[c] "GCController"
' 2>/dev/null | grep -viE "simruntime|Simulator" | perl "$FILTER" &
STREAM=$!
trap 'kill $STREAM 2>/dev/null' EXIT

say "watch started (pid $$, stream $STREAM)"
stuck=0; lastcpu=""; laststat=""; marked=0
while true; do
  # Only the state log rolls over here; the stream's two files are opened and rolled by the
  # filter, which is the one process that actually holds them.
  if [ "$(date +%F)" != "$DAY" ]; then DAY=$(date +%F); STATE="$LOGDIR/state-$DAY.log"; fi

  read -r pid st cpu <<<"$(ps -eo pid,stat,time,args | awk '/libexec\/gamecontrollerd/ && !/simruntime|awk/ {print $1, $2, $3; exit}')"
  if [ -z "$pid" ]; then
    [ "$laststat" != "gone" ] && say "gamecontrollerd not running"
    laststat="gone"; stuck=0; marked=0; sleep 2; continue
  fi
  [ "$st" != "$laststat" ] && say "state $laststat -> $st (pid $pid, cpu $cpu)"
  laststat="$st"

  # Wedged means uninterruptible AND not accruing any CPU. Healthy daemons dip into U all the
  # time; standing there with a frozen CPU clock is the signal.
  if [[ "$st" == U* && "$cpu" == "$lastcpu" ]]; then stuck=$((stuck+1)); else stuck=0; marked=0; fi
  lastcpu="$cpu"

  if [ "$stuck" -ge 8 ] && [ "$marked" -eq 0 ]; then
    marked=1
    {
      echo
      echo "############ WEDGED  $(date '+%F %T')  pid=$pid cpu=$cpu ############"
      echo "--- bluetooth devices ---"
      system_profiler SPBluetoothDataType 2>/dev/null | sed -n '/Connected:/,/Services:/p' | head -60
      echo "--- other stuck processes ---"
      ps -eo pid,stat,time,comm | awk '$2 ~ /^U/'
      echo "############################################################"
    } >> "$STATE"
    say "WEDGE captured; see the events log around this time"
  fi
  sleep 2
done
