#!/bin/bash
# Counts how often something opens a driver connection to gamecontrollerd, and says who.
#
# On 2026-09-20 that daemon burned a core for twenty minutes and hung every game that asked it
# for controllers. The cause was visible the whole time and thrown away: about 2,500 driver
# connections a second, opened and cancelled, from a single peer. The general-purpose capture
# could not hold it — twelve thousand log lines a second fills any file — and its noise filter
# discarded exactly these lines as boilerplate.
#
# So this counts rather than stores, and records the peer's pid, which is the one thing that
# names the culprit. It costs nothing while the daemon is behaving.
#
# Three things went wrong with earlier versions of this, all silent, and between them they cost
# a whole evening's evidence:
#
#   1. It called `log`, which resolves to something else in this user's shell. The stream died
#      on its first line.
#   2. When the stream died the script died with it, leaving a log whose last line said it had
#      started watching — which reads exactly like "nothing happened".
#   3. The counter was awk using systime() and strftime(). Those are GNU extensions; this machine
#      has neither them nor gawk, so the counter aborted the moment gamecontrollerd logged
#      anything at all. Every "stream ended" in the old log was really this.
#
# Hence perl, which is on every Mac and has the clock built in, and the self-test below.
OUT="$HOME/Library/Logs/thor-gcwatch/driverflood.log"
mkdir -p "$(dirname "$OUT")"
say() { echo "$(date '+%F %T')  $*" >> "$OUT"; }

counter() {
  perl -ne '
    BEGIN { $| = 1; $start = time; $n = 0; $peer = ""; }
    $n++ if /Incoming Driver Connection/;
    $peer = $& if /driver\.peer\[\d+\]/;
    my $now = time;
    if ($now - $start >= 10) {
      my $rate = $n / ($now - $start);
      # A pad connecting legitimately produces a handful. Hundreds a second is the fault.
      if ($rate > 20) {
        my @t = localtime;
        printf "%04d-%02d-%02d %02d:%02d:%02d  FLOOD: %.0f driver connections/sec from %s\n",
               $t[5]+1900, $t[4]+1, $t[3], $t[2], $t[1], $t[0],
               $rate, ($peer eq "" ? "unknown peer" : $peer);
      }
      $n = 0; $start = $now; $peer = "";
    }
  ' >> "$OUT"
}

# Self-test: feed it a burst and make sure it says so. A watcher that cannot be shown to fire is
# not a watcher, and this one claimed two hours of clean results while doing nothing.
if [ "$1" = "--selftest" ]; then
  before=$(wc -l < "$OUT" 2>/dev/null || echo 0)
  { for i in $(seq 1 600); do echo "Incoming Driver Connection driver.peer[9999]"; done
    sleep 11
    echo "Incoming Driver Connection driver.peer[9999]"; } | counter
  after=$(wc -l < "$OUT" 2>/dev/null || echo 0)
  if [ "$after" -gt "$before" ]; then echo "SELFTEST PASS:"; tail -1 "$OUT"; exit 0
  else echo "SELFTEST FAIL: no flood reported for 600 connections in 11s"; exit 1; fi
fi

trap 'say "watcher stopped"; exit 0' TERM INT
say "watching gamecontrollerd's driver endpoint (pid $$)"
while true; do
  /usr/bin/log stream --style compact --predicate 'process == "gamecontrollerd"' 2>/dev/null | counter
  say "stream ended; restarting in 5s"
  sleep 5
done
