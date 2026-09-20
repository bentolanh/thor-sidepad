# What we know about gamecontrollerd overloading, and what to do next time

## Surrounding context

**Trigger.** On 2026-09-20 macOS's `gamecontrollerd` wedged repeatedly: games hung at
launch waiting for it, `sudo killall gamecontrollerd` was needed before almost every
launch, and `loginwindow` crashed twice, logging the user out. It had never happened
before this pad existed, so the pad was the suspect.

**Failure scenario (user POV).** Play a Steam game with the SidePad for a while. Quit.
Launch another game — it hangs on the splash screen and never reaches the menu. Kill
the controller daemon and it launches fine. Do that a few times and the whole desktop
disappears: logged out, back at the login window, unsaved work gone. The pad itself
seems fine the whole time. The user described it as "similar to a memory leak" because
it got worse the longer the machine had been up.

**Problem.** Nothing on the Mac attributes the daemon's work to a device, so "our pad
causes it" was a story rather than a measurement. Worse, the first attempt to capture
evidence destroyed it: `gcwatch.sh`'s noise filter discarded `Incoming Driver
Connection` lines as boilerplate, and those five line types were the entire signal —
about 2,500 driver connections a second from one peer. By the time that was understood
the peer process was dead and only its pid survived.

**World-state assumptions.** Written after the 2026-09-20 evening session, with the pad
carrying the advertisement fix (`509eac4`) and the rumble setting (`a44d430`, text
corrected in `9f98744`). The pad claims Microsoft's `045E:02E0` over Low Energy and
sends the genuine Xbox model 1708 report shape.

**Lineage.** Builds on the pairing-direction findings in the project memory
`project_thor_sidepad_ble_pairing_direction` and on
`reference_macos_emulator_hang_gamecontrollerd`, which records that the kill itself can
crash `loginwindow` — the trigger being `unpublishControllersWithIdentifiers:`, i.e. any
controller removal, not the kill specifically.

**Stale-when.** Stop trusting this if the hang recurs while the advertisement fix is
live — that would disprove the leading theory below. Also stale if the pad stops
claiming an Xbox identity, or if Apple changes how `gamecontrollerd` publishes devices.

---

## The state of it

The overload was **not reproduced** on the evening of 2026-09-20, across twenty-five
minutes of Eastward in two segments — Steam Input off, then on — with the daemon's cost
recorded every thirty seconds throughout. It sat on its idle baseline the whole time.

The leading explanation is no longer long play or Steam Input. It is **reconnect churn**,
and the user proposed it after noticing that this was the longest the game had run all
day without trouble. Before the advertisement fix, the pad stopped announcing itself as
a gamepad whenever it left its findable window, so macOS could not reconnect to it as a
controller. What it did instead was connect and drop — measured at 243ms, then 480ms,
repeatedly. Every one of those cycles makes `gamecontrollerd` publish and unpublish a
controller, and controller *removal* is what the earlier `loginwindow` crashes were
traced to.

Today's log supports the shape of that. Counting connect and disconnect events per
minute across 2026-09-20: a sustained stretch of 8–16 per minute from 11:43 to 11:55,
then spikes of 24 at 16:02 and 19 at 16:17. During both of the evening's game sessions,
23:26 to 23:44, there were none at all.

This is a good fit to the surviving evidence, not a proof. The 2,500-per-second flood
itself is unrecoverable because the filter that was supposed to capture it threw it
away.

**So the test is time.** If the hang does not return over several days of ordinary use
with the advertisement fix live, churn was the cause. If it does return, the recorders
below will catch it properly this time.

## The one thing that is measured and still unexplained

`gamecontrollerd` burns about **1.0 second of processor per minute for as long as this
pad is connected**, with the pad untouched, no game running and no traffic on the radio.
It costs nothing when the pad is absent.

| attached | daemon CPU per minute |
| --- | --- |
| nothing | 0.00 s |
| 8BitDo Ultimate 2 Wireless alone | 0.00 s |
| this pad alone | 1.02 s |
| this pad and the 8BitDo together | 1.00 s |

All of it is one thread, in `-[_GCHapticServerManager enterRunloop]` — a haptics loop
that wakes on a timer, walks a dictionary looking for haptic client data, finds none,
and sleeps again. Confirmed by `sample` twice. The other three threads are parked.

**It is not the report descriptor.** The obvious theory was that our Physical Interface
Device collection — the force-feedback report copied from the real controller — is what
tells macOS there is a haptic engine. Removing it entirely made no difference: 1.07 s
per minute with the host confirmed by `ioreg` to be holding the trimmed 226-byte map
with no PID page in it, and the same frames still on top of the sample. That collection
is now optional anyway (Settings → "Tell a machine the pad can rumble") but the switch
buys nothing on a Mac.

The untested theory is **identity**: we claim `045E:02E0`, and macOS may apply its
built-in Xbox profile — haptics included — regardless of what our descriptor says. The
8BitDo declares `2dc8:6012` and gets a generic profile. Testing it means presenting a
different identity and re-pairing, which costs the Xbox mapping everything depends on.

Note the scale before spending anything on this: 1 s/min is about 1.7% of one core. It
is a steady tax. It is two orders of magnitude short of the 2,500-connections-a-second
flood that hung games, and it should not be confused with it.

## Next time it overloads

The watchers live in `tools/gcwatch/` in this repo and are copied to
`~/Library/Application Support/thor-sidepad/gcwatch/` to run. Start all three and leave
them running:

```bash
nohup ~/Library/Application\ Support/thor-sidepad/gcwatch/driverflood.sh >/dev/null 2>&1 &
nohup ~/Library/Application\ Support/thor-sidepad/gcwatch/gccpu.sh     >/dev/null 2>&1 &
nohup ~/Library/Application\ Support/thor-sidepad/gcwatch/gcmarks.sh   >/dev/null 2>&1 &
```

They all write to `~/Library/Logs/thor-gcwatch/`:

| script | what it records | why it exists |
| --- | --- | --- |
| `driverflood.sh` | driver connections per second, over 20/s, **with the peer's pid** | the peer is the culprit and last time it was dead before we thought to look |
| `gccpu.sh` | the daemon's cost as a rate every 30s, plus the HID pad count | a total says it went bad; a rate says when, and when is what names the cause |
| `gcmarks.sh` | games starting and stopping, Steam's virtual pad appearing | so the curve can be read months later without remembering the evening |

Then, while it is misbehaving and **before killing anything**:

```bash
sudo sample $(pgrep -x gamecontrollerd) 10 -f /tmp/gcd.sample
```

That needs a password and is read-only. It is what identified the haptics loop, and a
wedged daemon's stack is the one piece of evidence that cannot be recovered afterwards.

### Traps, all of which cost us a session

- **`log` is shadowed in this user's shell.** Scripts and one-off commands must call
  `/usr/bin/log`. A bare `log stream` dies on its first line and the silence reads as
  "nothing happened".
- **A watcher that stops watching is worse than none.** `driverflood.sh` ran for two
  hours having died in its first second, and its log's last line said it had started
  watching. It now restarts its stream and says so when it does.
- **Do not filter the raw capture.** `gcwatch.sh`'s `NOISE` pattern still contains
  `Incoming (Driver )?Connection`. That is the signal. Reading the filtered
  `events-*.log` for a flood will find nothing, by construction.
- **`system_profiler` and the Bluetooth menu disagree about "connected".** The menu
  showed a blue icon for a device `system_profiler` reported as not connected. Trust the
  command.
- **This machine's `awk` is not GNU awk, and there is no `gawk`.** `systime()` and
  `strftime()` abort the script the moment a line arrives. `driverflood.sh` was written
  with both and therefore never worked at all: its counter died on the first line
  `gamecontrollerd` logged, which closed the pipe, which the script reported as "stream
  ended". Every clean result it produced on 2026-09-20 is void. It is perl now, and it
  has a self-test:

  ```bash
  ~/Library/Application\ Support/thor-sidepad/gcwatch/driverflood.sh --selftest
  ```

  Run that before trusting a quiet log. A watcher that cannot be shown to fire is not a
  watcher — that is three separate silent failures in one evening, each of which looked
  exactly like good news.
