#!/bin/bash
# Records what gamecontrollerd costs, once every thirty seconds, as a rate rather than a total.
#
# A single reading taken after a session says only "it was bad". This says when it turned, which
# is the part that names the cause: the figure sitting at the idle baseline through ten minutes
# of play and then climbing when a particular thing happens is worth more than any total.
OUT="$HOME/Library/Logs/thor-gcwatch/gccpu.log"
mkdir -p "$(dirname "$OUT")"
secs() { awk -F: '{ n=NF; s=$n; if (n>1) s += $(n-1)*60; if (n>2) s += $(n-2)*3600; print s }' <<<"$1"; }
prev=""; prevpid=""
echo "$(date '+%F %T')  watching gamecontrollerd cpu (pid $$)" >> "$OUT"
while true; do
  read -r pid t <<<"$(ps -eo pid,time,args | awk '/libexec\/gamecontrollerd/ && !/awk|simruntime/ {print $1, $2; exit}')"
  if [ -z "$pid" ]; then echo "$(date '+%F %T')  daemon not running" >> "$OUT"; prev=""; sleep 30; continue; fi
  now=$(secs "$t")
  # A restarted daemon has a fresh clock, so the delta across the change is meaningless — it
  # reported -4098 s/min the first time this happened. Say the restart happened and start again.
  if [ "$pid" != "$prevpid" ]; then
    [ -n "$prevpid" ] && echo "$(date '+%F %T')  == DAEMON RESTARTED (was $prevpid, now $pid)" >> "$OUT"
    prevpid=$pid; prev=$now; sleep 30; continue
  fi
  if [ -n "$prev" ]; then
    rate=$(awk -v a="$prev" -v b="$now" 'BEGIN { printf "%.2f", (b-a)*2 }')   # per minute
    pads=$(ioreg -r -c IOHIDDevice 2>/dev/null | grep -c '"PrimaryUsage" = 5')
    # The haptics run loop ticks about 1 s/min for as long as an Xbox-identity pad is attached,
    # whether or not the rest of the daemon is answering. So a steady rate proves nothing about
    # health: on 2026-09-21 the daemon sat at 1.59 s/min while hanging every game launch, and
    # killing it freed them instantly. The state letter is the better hint — U is a blocking
    # wait — so it is recorded alongside rather than inferred later.
    st=$(ps -o stat= -p "$pid" 2>/dev/null | tr -d ' ')
    echo "$(date '+%F %T')  ${rate}s/min  (total $t, state $st, hid pads $pads)" >> "$OUT"
  fi
  prev=$now
  sleep 30
done
