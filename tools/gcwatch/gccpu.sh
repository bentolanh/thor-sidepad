#!/bin/bash
# Records what gamecontrollerd costs, once every thirty seconds, as a rate rather than a total.
#
# A single reading taken after a session says only "it was bad". This says when it turned, which
# is the part that names the cause: the figure sitting at the idle baseline through ten minutes
# of play and then climbing when a particular thing happens is worth more than any total.
OUT="$HOME/Library/Logs/thor-gcwatch/gccpu.log"
mkdir -p "$(dirname "$OUT")"
secs() { awk -F: '{ n=NF; s=$n; if (n>1) s += $(n-1)*60; if (n>2) s += $(n-2)*3600; print s }' <<<"$1"; }
prev=""
echo "$(date '+%F %T')  watching gamecontrollerd cpu (pid $$)" >> "$OUT"
while true; do
  read -r pid t <<<"$(ps -eo pid,time,args | awk '/libexec\/gamecontrollerd/ && !/awk|simruntime/ {print $1, $2; exit}')"
  if [ -z "$pid" ]; then echo "$(date '+%F %T')  daemon not running" >> "$OUT"; prev=""; sleep 30; continue; fi
  now=$(secs "$t")
  if [ -n "$prev" ]; then
    rate=$(awk -v a="$prev" -v b="$now" 'BEGIN { printf "%.2f", (b-a)*2 }')   # per minute
    pads=$(ioreg -r -c IOHIDDevice 2>/dev/null | grep -c '"PrimaryUsage" = 5')
    echo "$(date '+%F %T')  ${rate}s/min  (total $t, hid pads $pads)" >> "$OUT"
  fi
  prev=$now
  sleep 30
done
