#!/bin/bash
# Records the two daemons in the livelock, and notices anything crashing, every 15 seconds.
#
# Written 2026-09-21 while deliberately letting a WindowServer/gamecontrollerd flood run to see
# whether it takes the machine down. If it does, this session and every watcher in it die with
# the login session — so everything worth having afterwards has to already be on disk. Hence a
# flat append-only file and no buffering.
OUT="$HOME/Library/Logs/thor-gcwatch/crashwatch.log"
DR="$HOME/Library/Logs/DiagnosticReports"
mkdir -p "$(dirname "$OUT")"
say() { echo "$(date '+%F %T')  $*" >> "$OUT"; sync; }

cpu() { ps -eo comm=,%cpu=,time= | awk -v want="$1" '$1 ~ want { printf "%s cpu=%s time=%s  ", $1, $2, $3 }'; }
seen=$(ls "$DR" 2>/dev/null | sort | tr '\n' '|')

say "watching WindowServer + gamecontrollerd and the crash reporter (pid $$)"
while true; do
  gc=$(ps -o %cpu=,time= -p "$(pgrep -x gamecontrollerd | head -1)" 2>/dev/null | tr -s ' ')
  ws=$(ps -o %cpu=,time= -p "$(pgrep -x WindowServer | head -1)" 2>/dev/null | tr -s ' ')
  lw=$(pgrep -x loginwindow >/dev/null && echo up || echo GONE)
  say "gamecontrollerd[$gc] WindowServer[$ws] loginwindow=$lw"
  # Anything new in the crash reporter is the whole point of this script.
  now=$(ls "$DR" 2>/dev/null | sort | tr '\n' '|')
  if [ "$now" != "$seen" ]; then
    for f in $(ls -t "$DR" 2>/dev/null | head -5); do
      case "$seen" in *"$f|"*) ;; *) say "NEW CRASH REPORT: $f" ;; esac
    done
    seen=$now
  fi
  sleep 15
done
