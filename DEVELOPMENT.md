# Thor SidePad – development notes

User-facing documentation is in `README.md`. This file is for people building or changing
the app.

## How it works

- A Shizuku user service runs as the shell user. On the Thor that user can open the
  controller's `/dev/input/event*` node and `/dev/uinput`.
- **Same-controller mode** writes key and axis events straight into the chosen controller's
  input node, so games keep seeing one pad. It is limited to buttons that controller
  advertises. Controllers are remembered by name and re-resolved every time the pad shows,
  because node numbers change when the Thor switches controller style or a Bluetooth pad
  reconnects; the service also listens for input-device changes and reopens its target on
  the spot, and a failed write triggers the same.
- **Virtual pad mode** creates a separate uinput gamepad named "Thor SidePad". The Thor
  kernel stamps every uinput pad with AYN's vendor/product ids, so AYN's key layout applies.
- **Mouse and keyboard: what the probe found.** Not built, but the ground is checked. A uinput
  device that declares relative axes is taken by Android as a pointer (`Classes: CURSOR | EXTERNAL`)
  and its cursor lands on **display 0**, the top screen, which is where it is wanted. That was the
  question that decided whether the feature is worth building, since no input device on the Thor
  has an `AssociatedDisplayPort` and a virtual device has no port to associate one with, so the
  cursor could not have been steered there by configuration. Verified by screenshot: the arrow
  appeared over the video on the top screen and woke its controls. AYN ships its own uinput mouse
  ("ODIN Station Virtual Mouse"), so the kernel and the security policy were never in doubt.
  `IInjector.probePointer` reruns all of this; fire it with the `PROBE` action (development only).

  Three things still stand between that and a feature. Motion has to travel as one call carrying
  both axes and emitting one sync report, because `key` and `abs` each write their own and a
  pointer moved through two calls steps instead of glides. The injector holds one target
  descriptor and opening a second closes the first, so a pointer alongside a pad needs a real
  multi-device model; the probe sidesteps this by holding its own descriptor and leaving the pad
  alone. And same-controller mode can never carry a pointer or a keyboard, because it writes into
  the physical controller's node and that node declares neither; both are virtual-only. One
  caution for the keyboard half: declaring a full alphabetic keyboard tells Android a hardware
  keyboard is attached, which normally suppresses the on-screen one. This device has
  `show_ime_with_hard_keyboard` on, so it would not show here, but it would elsewhere.

- **M1 / M2** are BTN_C / BTN_Z, which the Thor's controller advertises and AYN's key layouts
  map to Android `BUTTON_C` / `BUTTON_Z`; stock `BUTTON_1..` codes are unmapped on the Thor.
- **Windows.** Shield mode is one full-screen non-focusable overlay on the second display;
  islands mode is one small window per button. All overlay windows stay non-focusable so the
  top screen keeps input focus, which is where gamepad events are delivered. The one
  exception is the preset name box; a transparent hand-off activity returns focus afterwards.
- **Focus and the guide.** The shield is remembered between sessions; the service starts it
  in whatever state it was left. The interactive guide forces shield on, frosted backdrop and
  full opacity while it runs and restores the user's look afterwards, persisting the snapshot
  so an interrupted guide is undone on the next start (shield included).
- **Panel.** Pulled down like the notification shade (finger-tracked, settles or springs
  back), re-rendered in place on setting changes, restyled in place when the shield or
  backdrop changes. With the shield on but the pad hidden, the panel carries the shield's
  backdrop itself.

- **Device controls.** Slider elements (brightness and volume per display, plus a brightness
  slider that sets both displays) and Home/Back
  buttons per screen are pad elements with negative pseudo-codes. Brightness goes through
  the hidden per-display `DisplayManager.setBrightness`, callable from the shell-uid user
  service because the shell holds `CONTROL_DISPLAY_BRIGHTNESS`; the main volume through
  `AudioManager` (media stream) in that service; Back per screen through
  `input -d <display> keyevent 4`; Home on the main screen through the launcher intent on
  display 0, Home on the pad's screen through the Thor's own Home key (routed to the
  last-touched screen).
- **Second-screen volume.** The Thor has one media stream, but AYN's firmware scales the
  audio of apps running on the second screen separately. Its own Control Center slider only
  writes the system setting `secondary_screen_volume_level` (0..15); a running AYN process
  maps that to the `persist.sys.audio.value` gain, and the patched AudioFlinger applies it to
  the UIDs listed in `sys.audio.uids` (the apps on display 4). SidePad's second volume slider
  writes the same setting, so the AYN slider and ours stay in step.

- **Editor model.** The editor edits exactly one profile, named in the chip. `Save` writes the
  working layout into it, stays open and flashes a confirmation; a built-in cannot be written
  over, so that one case asks for a name and the copy becomes the profile being edited. `Exit`
  and the back gesture both run the same check: unchanged leaves at once, changed offers save,
  leave without saving, or cancel. Dirtiness is `PresetStore.find(active).layout.toJson()`
  against `working.toJson()`, which covers buttons, sticky flags and glyph style. Anything that
  replaces what is on screen (switching profile, starting a new one) goes through the same
  guard, so edits are never dropped silently. Leaving always adopts the profile being edited,
  either as just saved or as stored.

- **Reaching the media sessions without a notification listener.** Reading a position, seeking,
  and an app's own custom actions all need `MediaSessionManager.getActiveSessions`, which normally
  forces an app to run an enabled NotificationListenerService. It does not have to here. The
  shell user already holds `MEDIA_CONTENT_CONTROL` (`dumpsys package com.android.shell`), so the
  Shizuku-hosted process is allowed; the only obstacle is that this process never runs the media
  framework's start-up, leaving `MediaServiceManager` null. Constructing that class reflectively
  and passing it to `MediaFrameworkInitializer.setMediaServiceManager` and its Platform twin is
  enough: `getSystemService(MEDIA_SESSION_SERVICE)` then works and `getActiveSessions(null)`
  returns live controllers. Verified on the Thor: Spotify state=2 pos=234964 dur=236000, plus
  Audible and the Apple TV session. `registerServiceWrappers` throws ("can only be called during
  class initialization") and is not needed. So the planned video unit needs no extra permission.

- **The band's icon and title.** The title comes from the session's metadata, so it is the video
  or track name rather than the app's. The icon cannot come from our own package manager: an app
  targeting Android 11 or later sees only packages it declares, and asking for all of them needs a
  broad visibility permission. The injector runs as the shell, which sees everything, so it draws
  the icon into a 96px PNG and hands back the bytes; `AppIcons` caches the result and fetches off
  the drawing thread, redrawing when it arrives.

- **The video unit.** It reads the playing session through the injector: `mediaInfo` returns
  package, state, position and length, `mediaSkip` jumps by ten seconds from where the session is
  now, and `mediaSeek` takes an absolute position. A playing session reports where it was at a
  moment, so `livePosition` carries that forward by the elapsed time and playback speed, which is
  why the timeline advances smoothly between polls. The service polls every 700 ms, but only while
  a media or video unit is actually on the pad. Dragging the timeline seeks once on release rather
  than on every move, since seeking repeatedly makes a player stutter.

- **The media unit's volume.** The Thor scales the audio of apps on the second screen apart from
  the main stream, so "the volume of what is playing" depends on which screen the player sits on.
  The unit's track sends `Slider.VOLUME_MEDIA`, which the service resolves: it asks
  `dumpsys media_session` which package holds the media keys, finds that package under a display
  in `dumpsys window displays`, and then moves either the second screen's level or the media
  stream. It resolves when the pad shows, when a transport key is pressed, and once per drag, so
  the lookup never runs per touch move.

- **The Back key, the back gesture, and who takes focus.** Exactly one window of SidePad's takes
  window focus: the preset name box, because it is a text field. Everything else, the pad, the
  panel, the editor, the profile list, the rate dialog and the choosers, is non-focusable and
  closes by its own Exit, Close or Cancel, or by a tap outside. Back therefore reaches only the
  name box. The editor claims the side edges from the system back gesture the way the shield does,
  so a back swipe inside it does nothing rather than navigating the app behind it, which the user
  cannot see; verified on the device as `SkRegion((0,0,73,1080)(1167,0,1240,1080))`.

  The panel does not take focus, on purpose. It is the one thing meant to be reached mid-game, for
  volume and brightness, and a visit to a focusable window on this screen costs the game up top its
  foreground: measured on the Thor before this was changed, a single panel visit over GameNative
  logged `wm_on_top_resumed_lost_called` for it and then started `FocusHandoffActivity` on the top
  screen, pausing it again. Note the cost is not in the taking of focus itself. While one of our
  focusable windows is up, `mTopFocusedDisplayId` stays 0 and the top screen keeps its foreground;
  the churn comes when that window goes away, and the hand-off is what puts things back. Either
  way a non-focusable panel has neither half, and it measures clean on open and on close. It costs
  only the Back key, and tapping outside already closes it and already says so on the panel. Do not
  make it focusable again to get Back back.

  Focus is handed to display 0 through `FocusHandoffActivity`, and `dropWindow` fires that only
  when the window being closed was itself holding focus and nothing else of ours still is. Both
  halves of that test matter. Handing focus back costs an activity launch on the top screen, so
  doing it after a window that never took focus disturbs the game for nothing, which is what made
  leaving the editor jolt the top screen until the editor stopped taking focus. The pad itself stays non-focusable, so with the shield off or the pad
  hidden the system back gesture reaches the app behind as usual. With the shield up the screen
  behind is not meant to be touched, so `ShieldPadView` claims the left and right edges from the
  system back gesture with `systemGestureExclusionRects`; a back swipe there lands on the shield
  and does nothing. That needs no focus, so injection is unaffected. Android caps edge exclusion
  (`system_gesture_exclusion_limit_dp=200` on the Thor), but the full height of both side edges
  was accepted on the device: `SkRegion((0,0,73,1080)(1167,0,1240,1080))`.

- **Stepping aside for the keyboard.** Overlay windows draw above the on-screen keyboard and a
  non-focusable window is never told the keyboard is up, so the service polls twice a second while
  the pad is showing and takes the pad down when a keyboard appears on the pad's screen. It asks
  the window manager for the keyboard's own window (`dumpsys window InputMethod`) and steps aside
  when one is `isReadyForDisplay()` on the pad's `mDisplayId`. Which screen the text field is on
  does not come into it, and must not: the Thor can be set to send the keyboard to the second
  screen whenever it is called (`settings get system ime_show_on_second`), so a field on the top
  screen raises a keyboard on the pad's screen, and a rule that waited for the field to be on the
  pad's screen left the pad sitting over it. Verified on the device with that setting on, typing
  into Chrome on the top screen: the keyboard appears on the second screen and the pad comes down
  for it.

  Do not ask the input-method service instead. `dumpsys input_method` makes that service turn
  round and ask the current keyboard app to dump itself, waking a sleeping app once a second for
  as long as the pad is up; measured on the Thor that put the Google keyboard at 1086 ticks of
  processor time per 20 seconds, about half a core, against 0 for the window query, and a call
  costs 401 ms against 35 ms. Its `mInputShown` is not trustworthy either: it was seen true with
  no keyboard window in existence, and false while a keyboard was up and drawn.

- **Naming a preset uses a keypad of our own.** The system keyboard cannot be had in a floating
  window. Ours takes focus and the window manager even names it the IME target, yet the
  input-method service is pointing at a different window, our side never asks for a keyboard at
  all (`mShowRequested` stays false, and no input traffic leaves the process), and asking by hand
  is refused with `Ignoring showSoftInput() as view ... is not served`, both straight after
  `requestFocus` and again when the window gains focus. An app that lives on this screen has none
  of this trouble: Settings' search field on display 4 raises the keyboard normally. The
  difference is between a window that belongs to a screen and one that only floats over it.

  So `askName` draws its own letters and handles typing itself, and `NameField` draws the caret,
  blinks it and moves it, because Android stops a real field's cursor blinking the moment its window
  loses focus and this window never has any. The caret is held lit for a moment after every key, a
  long name scrolls by the caret rather than running off the end, and names are capped at
  `NameField.MAX`; the header says so on the key that first hits it, since a key that quietly does
  nothing reads as a missed press. That is not a workaround so much
  as the better fit: the window needs no focus, which means naming a preset costs the top screen
  nothing, and it does not depend on where this device decides to put the keyboard. It also
  leaves SidePad with no focusable window at all; the hand-off machinery stays for safety but
  nothing currently triggers it.

- **Staying up.** The pad is meant to be there until the user says otherwise, so it is not left to
  the memory manager's judgement. The app's own process can be taken; the Shizuku user service
  cannot, since it runs as shell at oom adj -1000. So it is bound with `.daemon(true)` and outlives
  the app deliberately, and it holds a watchdog: the service beats every 5 s, and after 20 s of
  silence the shell side revives the pad with `am broadcast ... START`, carrying
  `FLAG_INCLUDE_STOPPED_PACKAGES` because the killers that matter force-stop the app and a plain
  broadcast would not reach it. `prefs.padShown` remembers whether the pad was on screen, so a
  revived service comes back as it was rather than as bare edge strips.

  Only the Stop button disarms it, and only a deliberate stop releases the shell process; dying any
  other way must leave it running, because it is the thing that puts the pad back. Measured on the
  Thor: after `am force-stop`, the pad was back 21 s later. After Stop, nothing came back and both
  processes were gone.

  Note for development: because the user service is now a daemon, it keeps running old code across
  reinstalls unless `versionCode` changes. Shizuku keys it on `.version(BuildConfig.VERSION_CODE)`.

- **The handheld as a controller for another machine: what the probe found.** Not built, but proven
  end to end against a Mac. The Thor's Bluetooth stack carries `HidDeviceService`, so it can take
  the HID *device* role, and `BluetoothHidDevice.registerApp` with a plain gamepad descriptor,
  sixteen buttons and four axes, reports `registered=true`. `registerApp` itself returns false even
  on success; the callback is the signal to trust.

  Connecting works from either end, but never by itself. With the gamepad registered and idle the
  Mac did not reconnect on its own inside 45 s; calling `connect()` from this side brought the link
  up in under 3 s, and clicking the device in the Mac's own Bluetooth list also worked, with input
  flowing normally afterwards either way. The prerequisite is the same for both: the app has to be
  running and registered, because the gamepad exists only while it is. So the app wants its own
  Connect control rather than relying on the host.

  What the Mac made of it. Bluetooth lists the Thor with `Services: < HID GATT ACL >` but
  `Minor Type: Mobile Phone`, since the advertised device class still says phone. That does not
  matter: macOS's own input stack lists it on usage page 1, usage 5, which is Game Pad, bound to
  `AppleUserHIDEventDriver`. Buttons one to six each arrived as usage page 9 with the right usage
  and value, and the stick arrived on page 1 as usages 0x30 and 0x31 carrying 100 and -100. So the
  descriptor survives intact.

  Why this is the interesting direction: none of it is privileged. One ordinary `BLUETOOTH_CONNECT`
  permission, no Shizuku, no developer mode, no root, no accessibility. The pad, the editor,
  profiles and glyphs would all be reused; only the last step changes, from writing evdev events
  into a local uinput device to assembling HID reports. It also needs no second screen, since the
  handheld is not the machine running the game. `BtHidProbe` reruns all of this: `PROBE_BT` to
  register, `PROBE_BT_SEND` to press. Development only.

  **What a Mac does and does not accept, measured.** macOS's input layer takes it: `hidutil` lists
  it as a Game Pad bound to `AppleUserHIDEventDriver`, and a browser gamepad tester reads the
  buttons and sticks. Apple's own GameController framework does not: a tool calling
  `GCController.controllers()` with the Thor connected reports none, which is why it is absent from
  System Settings under Game Controllers.

  That is the line. Anything reading raw HID works, which covers Steam, emulators, browsers and
  SDL-based games. Anything built on Apple's framework does not see it at all. Getting onto the
  other side of that line means impersonating a controller macOS supports natively, matching both
  the identifiers and the exact report layout of an Xbox or PlayStation pad, which is what the
  "Appears as" row was left open for. Note it would also fix our report layout to theirs, and any
  host already paired would need pairing again, since the descriptor is cached.

  The open question is host compatibility rather than plumbing. Apple's controller framework
  favours known controller families, so Steam and emulators are likely to take a generic pad while
  some native Mac games may not. Whether the advertised device class can be changed from an app was
  not established.

- **A swipe on the pad's screen could fire a back on the other one.** Caught on 2026-09-19 with the
  panel open: `InputDispatcher` logged the system's edge-swipe gesture monitor *stealing the touch*
  from the SidePad panel, `BackAnimationController` reported `BackNavigationInfo is null` because
  nothing on that screen takes focus and so nothing could receive a back, and the press then landed
  on the app holding focus, which is on the top screen. The user saw Apple TV go back while swiping
  the bottom one.

  The shield already claimed the side edges, but the panel sat above it and did not, and a window
  above suppresses the claim of the one below. So any window of ours that fills the screen has to
  hold the edges itself; `add()` now does that for every full-screen window, while the small
  per-button windows are deliberately left alone so the screen between buttons still belongs to the
  app underneath.

  Injected swipes will not reproduce this. `input swipe` never reaches the gesture monitor, so it
  takes a real finger; the recipe that worked was to close the top-screen app completely, reopen it
  so it holds focus, then swipe an edge with the panel open.

- **The whole pad can vanish, and it is not our bug.** An app may set
  `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` on its window, which asks the system to hide every
  non-system overlay while it is showing. Android's own Settings does this on its home screen, as
  anti-tapjacking. It applies across the whole device, not per display: Settings open on the top
  screen hides the pad on the second one. The windows still exist and the permission is still
  granted; `dumpsys window windows` shows them with `mHasSurface=true` and
  `isReadyForDisplay()=false`, and the culprit is whichever window's `pfl=` line carries the flag.
  Move that app off the top and the pad returns at once.

  Worth knowing before chasing it as a rendering fault, which cost an evening on 2026-09-18. Banking
  apps and other security-minded ones use the same flag, so a user will meet this. Nothing can be
  done about the hiding itself; what the app could do is notice and say so, since a pad that
  disappears without explanation mid-game is alarming.

- **Sending presses to another machine.** `PadSink` is where a press leaves the pad. `LocalSink`
  wraps the injector as always; `BluetoothSink` presents a Bluetooth gamepad and assembles HID
  reports instead. Everything above that seam, layouts, profiles, glyphs, turbo, hold, sticks, is
  the same either way, because the sink also declares its `Caps` in the engine's own terms and the
  existing planning does the rest.

  The shape advertised is sixteen buttons, two sticks, two triggers and a hat, in a nine-byte
  report. **The usage names are not free choice.** Hosts assume an unknown pad puts its left stick
  on X and Y, its right stick on Rx and Ry, and its triggers on Z and Rz. A controller calls the
  same things ABS_Z/ABS_RZ and ABS_GAS/ABS_BRAKE on the Linux side, and mapping by name rather than
  by convention puts the triggers where the right stick is expected. A trigger at rest then reads
  as a stick held hard over, which a game shows as input flying about while nothing is touched. `CAPS` deliberately mirrors the local virtual pad: the D-pad is left out of the buttons so
  it plans onto the hat, and the triggers get `BRAKE` and `GAS` so they do not fight the right
  stick, which lives on `Z`/`RZ`.

  Two things that cost time. Without a chosen host, picking the first paired device is wrong on a
  handheld: this one's list begins with headphones and holds six controllers, so the fallback takes
  a paired *computer* by device class. And the destination is chosen in `show()`, where opening is
  asynchronous, so the pad appears before the host answers; the local reopen path is skipped
  entirely for a Bluetooth destination since there is nothing there to reopen.

  **The Thor's sticks are the right way up and must be left alone.** This said the opposite for a
  while and the forwarder turned the two vertical axes over, which is where an inverted stick on
  the other machine came from. The reading that justified it was of the wrong byte: a report
  carries a leading report ID, so every axis sits one place further along than a naive count
  suggests, and the axis being read as Y was really X.

  Settled on 2026-09-19 by holding both sticks at the top and reading what arrived at the far end.
  With the flip in place they read +127; a host wants up to be negative. The flip is gone, and the
  same test now reads −127. When checking this again, hold a stick and read the bytes the other
  machine receives — do not reason from the raw kernel value, which is what went wrong twice.

  Shizuku is not required for this. `show()` waits for the injector only when the destination is
  this device; a machine destination opens straight away and the device sliders and media units
  simply have nothing to show without it. The setup screen says so and no longer gates the later
  steps or the Start button on Shizuku, since someone who only wants a Bluetooth gamepad never
  needs it.

  **A host caches the report descriptor at pairing and does not re-read it.** Changing the shape we
  advertise therefore does nothing for a machine that is already paired: it keeps parsing our
  reports with the old map and quietly ignores any bytes the old one did not cover. Seen on
  2026-09-19, where buttons and the first four axes worked and the triggers and hat did not, and
  `ioreg -c IOHIDDevice -r -l` showed the Mac still holding the 44-byte descriptor from the probe
  rather than the 86-byte one. The only cure is to remove the device on the host and pair again.
  Worth remembering before blaming the report code, and worth telling a user after any change to
  the descriptor.

  Verified whole after re-pairing, with events driven into the controller node: buttons, both
  sticks at full travel and centred, both triggers 0 to 127 and back, and the D-pad arriving as hat
  directions with 8 for centred. Before the re-pair the triggers and hat were silent, which was the
  cached descriptor above and not the report code.

  Verified against a Mac: tapping A, Y and B on the pad arrived as buttons 1, 5 and 2, down and up.
  **Pairing lives in the panel.** A controller normally makes you hold a button until a light
  blinks, which tells you it is listening, that it will stop, and that now is the moment to look on
  the other machine. The Thor said none of that, and handing the user off to Android's Bluetooth
  settings was worse, because Settings is the screen that hides every overlay and the pad vanishes
  with it. So the Pair page asks Android for discoverability itself and then shows the name to look
  for with a live countdown. The consent dialog is left in place deliberately: making a device
  visible without asking is not ours to do, and the dialog doubles as the ritual people expect. It
  is raised by the app rather than through the shell so it names SidePad instead of "Shell".

  Reading the scan mode back needs `BLUETOOTH_SCAN`, the permission tied to finding nearby devices,
  which this app has no business holding. When the read is refused the countdown starts anyway on
  the user's word.

  The panel asks the two questions separately, because adding a machine split them. **Send to** is
  where presses go, this device or a paired machine; **Appears as** is what the receiver thinks they
  come from, which locally means the Thor's own controller or a separate virtual pad and remotely
  has one answer for now. That row stays visible when remote rather than being hidden, because the
  next answer in it is an Xbox-shaped identity chosen so that Mac games accept it.

  **Where each control lands on the host.** Measured on 2026-09-19 against a browser gamepad
  tester on the Mac, driving one control at a time into the Thor's controller node and reading the
  numbered slots back. A, X, Y, Select, Start, both sticks, both triggers and a D-pad direction
  were read directly; the rest follow from the same table in `BluetoothSink`, which every one of
  those landings agreed with. This is what a remapping screen has to be filled in with.

| Thor | Slot | Notes |
| --- | --- | --- |
| A / B / X / Y | B0 / B1 / B2 / B3 | |
| L1 / R1 | B4 / B5 | |
| Select / Start | B6 / B7 | |
| L3 / R3 | B8 / B9 | stick clicks |
| Guide | B10 | where Steam's own guess puts it |
| L2 / R2 | B11 / B12 | the click at the bottom; the travel is on an axis |
| M1 / M2 | B13 / B14 | the Thor's own extra pair, placed after everything standard |
| left stick | AXIS 0, AXIS 1 | up is −1 |
| right stick | AXIS 3, AXIS 4 | up is −1 |
| left trigger | AXIS 2 | |
| right trigger | AXIS 5 | |
| D-pad | AXIS 9 | one axis, eight directions; right reads −0.43 |

  **The ten axes are not a bug.** A host numbers axes by HID usage and not by the order we declare
  them: usage 0x30 becomes AXIS 0 and so on up to the hat at 0x39, which is AXIS 9. The gaps at
  AXIS 6, 7 and 8 are the slider, dial and wheel usages we never send; they sit at 0 forever.
  Nothing needs changing for them, and B15 is the same kind of spare, since buttons are declared in
  a block of sixteen and we use fifteen.

  **Steam already does the part we cannot.** A host has no layout for us because the vendor and
  product numbers belong to the Thor's Qualcomm Bluetooth chip and are published by Android's
  stack from `/system/etc/bluetooth/bt_did.conf`, a root-owned file on a read-only partition. Not
  reachable from the app, and not from shell either, so there is no version of this we can fix at
  our end. Steam fixes it at the other end: its log on 2026-09-19 shows it opening us as
  `Product: XInput Controller #1`, reserving an XInput slot and handing the game an Xbox pad. That
  makes a remapping screen of our own mostly redundant, and it is where the layout above came
  from — Steam prints the mapping it guessed, which is a far better source than pressing buttons
  and reading a tester.

  **The radio will refuse a report, and refusing one used to lose a press.** `sendReport` returns
  false when the interrupt channel is busy, and a controller produces far more reports than the
  link can carry — the Thor's sticks alone outrun it. The first version handed each report straight
  to the radio and dropped it if it was refused. A press made while a stick was moving survived,
  because the next stick frame carried the button with it; a press made with the sticks at rest was
  one report, and if that one was refused the press never happened. Hence buttons that registered
  sometimes and not others. Reports are now queued and offered until taken, on one thread rather
  than from whichever thread happened to make the press. Frames may still be thrown away when they
  pile up, but only ones whose buttons and hat match the frame behind them, so a press is never
  what gets dropped.

  **Measured on 2026-09-19, the link is not where input goes missing.** Worth writing down
  because the next person to feel a dropped press will suspect this first, and four separate
  measurements say otherwise. Twenty physical presses produced twenty downs and twenty ups at the
  Thor's own reader and twenty of each at the Mac's HID layer. Twenty injected presses arrived
  whole again with the sticks sweeping at 2300 events a second. The reader was driven at 4000
  events a second, five times what the controller produces, with no `SYN_DROPPED`. Forty
  right-stick changes sent half a second apart arrived forty for forty, spread 517 to 585
  milliseconds, which is the shell loop and not the radio. Nor is there a wake-up cost: a press
  after five seconds of silence arrives as fast as one after fifty milliseconds, so the link is
  not dropping into a low-power mode between presses.

  The way to test this again is in the scratchpad rather than the repo: a blob of `input_event`
  structs written straight to the device node, sweeping a stick by about two percent of its
  travel, which is inside any game's dead zone and so generates full event traffic without moving
  anything on screen. On the other end, a small `IOHIDManager` listener matching 001d/1200 and
  printing a timestamp per value change. Compare counts, and compare arrival intervals against a
  known cadence rather than against the host's clock, which keeps `adb`'s own jitter out of the
  number.

  **A controller never stops talking, and ours used to.** The measurements above cleared the link
  of losing or delaying anything, and yet a first movement after a pause still arrived late in a
  game while an 8BitDo pad in the same game did not. Listening at the report level rather than the
  value level found the difference in one reading: untouched and idle, the 8BitDo sent 82 reports
  a second, eleven milliseconds apart, and this pad sent none at all. Speaking only when something
  changes is a reasonable thing to do and every layer we can measure is happy with it; something
  above them is not. So there is now a heartbeat, and the pad reports its state a hundred times a
  second for as long as it is connected, which is what the hardware it is imitating does.

  **macOS Steam has no fallback; Windows Steam does, and it is not ours.** Under CrossOver the
  mapping appears by itself, and the log says why: `Controller 0 uses xinput : true`, `reserving
  XInput slot 0`. Wine presents us as an XInput device and XInput has one fixed layout everybody
  knows, so nothing had to be worked out. macOS has no XInput. There Steam sees a gamepad with no
  bindings at all and sends the user to its setup wizard, and the wizard drops the first press of
  each step: measured on 2026-09-19, every press left this pad as a clean single edge, 105 reports
  a second flowing, and Steam still needed two or three of them. What it writes is a mapping one
  step out of line — a stick direction bound to a button, a stick click bound to an axis.

  So do not use the wizard. Steam keeps these as plain text in `config/config.vdf` under
  `SDL_GamepadBind`, one per line, and a correct line can simply be added with Steam closed, since
  Steam rewrites that file when it exits. This is the line, and because the vendor and product
  numbers belong to the Thor's chipset rather than to us, it is the same line on every Thor:

```
03003c391d0000000012000036140000,Thor,a:b0,b:b1,x:b2,y:b3,back:b6,start:b7,guide:b10,leftshoulder:b4,rightshoulder:b5,leftstick:b8,rightstick:b9,leftx:a0,lefty:a1,rightx:a3,righty:a4,lefttrigger:a2,righttrigger:a5,dpup:h0.1,dpright:h0.2,dpdown:h0.4,dpleft:h0.8,paddle1:b13,paddle2:b14,platform:macOS,
```

  The identifier holds a hash of the Bluetooth name, so renaming the handheld stops it matching.
  `paddle1` and `paddle2` are M1 and M2, which have no other name and are otherwise unreachable.
  Showing this line in the app, with something to copy it, is worth more than a remapping screen
  of our own: it is the whole answer for macOS and it is the same for everybody.

  **Which layer an app reads decides whether it sees us.** Four cases, all on the same Mac on
  2026-09-19, and together they say the pad we present is ordinary and the trouble is elsewhere.

| App | What it reads | Result |
| --- | --- | --- |
| PPSSPP | raw joystick, with its own mapping screen | works, no setup at all |
| RPCS3 | whichever handler is selected | was set to Keyboard; nothing to do with us |
| Steam | SDL's named-gamepad layer | blind until a mapping exists for our id |
| a native macOS game | Apple's GameController framework | never, and nothing will change it |

  PPSSPP is the reassuring one: an app that takes raw joystick input picks us up with no help,
  which means the descriptor and reports are fine. Everything that fails does so because it will
  not speak to a device whose name it does not already know.

  Apple's refusal was confirmed the only way worth trusting, with a control. Both pads connected
  at the HID layer at the same moment; `GCController` reported the 8BitDo and not this one. So the
  test tool works and the framework simply rejects us, which is a vendor and product number we
  cannot set. There is no work left to do there — on macOS a native game will not see the Thor,
  and the route through Steam is the answer.

  That also sharpens what the mapping line is for. It is not a Steam fix. Every SDL-based
  program — RPCS3, Dolphin, and most of what is not Steam — asks the same database the same
  question, so one accepted entry upstream covers nearly all of them at once, on every platform.
  Note the identifier should carry zeroes where the one Steam prints carries a hash of the
  Bluetooth name, matching the form of every other entry, so that renaming the handheld does not
  stop it matching.

  **Over Bluetooth Low Energy the identity is ours, and this is tested.** Classic Bluetooth gives
  us no say: the vendor and product numbers come from a root-owned file and are published by
  Android's stack before the app exists. Low Energy carries them somewhere else entirely — a
  characteristic called PnP ID, inside a service the app defines. Three gates, all open, probed on
  2026-09-19 with a throwaway build:

  Android accepted a GATT server holding both the HID service and the device-information service,
  which was the gate expected to fail, since the HID service is the sort of thing a stack reserves
  for itself. It then advertised that HID service without complaint. And a Mac scanning for it
  found the pad, connected, and read the PnP ID back as vendor `0x1209` product `0x5350` — the
  numbers we invented, out of the pool meant for open projects, borrowed from nobody.

  What that is worth is not the Mac. Apple's list is by vendor and product, so choosing our own
  honest numbers still leaves us off it, and the only way onto it is impersonating a controller
  that is on it — which is not a thing to ship in an app other people install. The worth is that
  the identity stops being the handheld's Bluetooth chip. Today every different handheld running
  this app needs its own mapping line, because the numbers belong to whatever radio is inside it.
  Over Low Energy every one of them would present the same identity, so one line, and one entry
  submitted upstream, would cover every device this app is ever installed on.

  The cost is a second transport rather than a replacement: report map and report characteristics
  with notifications, protocol mode, control point, battery service, bonding. Classic stays,
  because it is what Windows and Android want. Two things are still unmeasured and should be
  before any of it is built: what connection interval a host grants, since latency is the whole
  game and the host chooses it, and whether a host will take reports from us once paired rather
  than merely reading our name. Note also that a Mac's CoreBluetooth hides HID services from
  ordinary apps — the probe saw the device-information service and not the HID one — so a scanner
  written this way cannot see the reports even when they are flowing.

  **Decided 2026-09-19: the pad will present itself as an Xbox-compatible controller.** What
  forced the question was a day spent writing the same information into four different files —
  a line in Steam's `config.vdf`, a database for RPCS3, a profile for RetroArch, and nothing at
  all that could help a native macOS game. Each host wants it in its own format, and each new
  handheld this app is installed on needs the whole set again, because the identity belongs to
  whatever Bluetooth radio is inside it.

  **This decision is not currently supported by evidence and should be treated as open.** What it
  rested on was a throwaway build advertising a Low Energy HID gamepad claiming `045E:02E0`, after
  which Apple's framework reported `category=Xbox One`. That looked conclusive and was not: the
  Classic pairing to the same Bluetooth address was live throughout, and the test was never run
  without it. Later the same evening the Mac was made to forget the pad, and from then on both
  `045E:02E0` and an identity of our own behaved identically — connect, read, drop after forty
  seconds — which is what no bond looks like, not what a rejected identity looks like.

  So Apple's gate may well be the vendor and product numbers. It has not been shown. Whoever picks
  this up should assume nothing from the paragraph below until it is rerun against a pad that has
  bonded over Low Energy and nothing else.

  Two things were weighed against it and both were tried first. The Class of Device is the more
  honest lever and does not work: the property exists and shell can write it, but this Qualcomm
  Android 13 build ignores it, and the runtime config where it otherwise lives is root-only.
  Choosing our own numbers works technically — a Mac read back `0x1209:0x5350` verbatim, from the
  pool meant for open projects — but leaves us off Apple's list, so it solves the portability
  problem and not the recognition one.

  **What it appears as has to be a setting, and not for the reason first argued.** The first
  version of this decision proposed hiding the Xbox identity behind a toggle out of scruple, which
  was the wrong reason: a default nobody turns on helps nobody. The right reason is that the
  identity cannot be fixed at all. An Xbox controller's report has no room for a trackpad, a
  keyboard or media keys, so a pad offering those cannot claim to be one. A Nintendo layout is a
  third identity again. Whatever the pad is currently pretending to be follows from what it is
  currently offering, so the panel needs a row for it beside the existing ones, with the standard
  gamepad identity as the default because that is what most of this is for.

  It is worth being plain about what this is. `045E:02E0` is Microsoft's, used as the de facto
  Xbox-compatible identifier by a great deal of third-party hardware — two 8BitDo pads on the
  bench here ship with exactly it, and a third claims Nintendo's. The project is open source and
  aimed at Android retro handhelds, where presenting as a standard controller is what the category
  does. It is disclosed in the README rather than hidden behind a setting, which was considered
  and rejected as needless: a default nobody turns on helps nobody.

  **Being recognised is not the same as working.** Every host that recognises those numbers will
  apply the Xbox layout, which expects Xbox's report format, so our own byte layout would land
  wrong everywhere. Doing this properly means emitting Xbox's report descriptor and its report
  layout as well as its numbers. The prize for that is the whole of the first paragraph: no
  mapping line, no database, no profile, on any host, on any handheld.

  **Low Energy carries a gamepad, measured 2026-09-19.** A probe advertised the HID service, a Mac
  read the eighty-six byte report map out of our characteristic, subscribed, and took notifications
  for half a minute. Reports were driven as fast as the sender could push them:

| | rate | median gap | p90 | p99 | worst |
| --- | --- | --- | --- | --- | --- |
| Low Energy, this probe | 111/sec | 4.4ms | 22.7ms | 56.5ms | 110.7ms |
| Classic, this pad's heartbeat | 100/sec | 11.2ms | — | — | 78.7ms |
| Classic, an 8BitDo pad idling | 82/sec | 11.1ms | — | — | 56.7ms |

  Classic was then measured on its own, with the pad hidden so nothing else held a link, driven by
  the same flood:

| | rate | median gap | p90 | p99 | worst |
| --- | --- | --- | --- | --- | --- |
| Classic alone, flooded | 168/sec | 1.3ms | 22.3ms | 64.3ms | 155.4ms |
| Low Energy, flooded | 111/sec | 4.4ms | 22.7ms | 56.5ms | 110.7ms |

  Classic is the quicker of the two where it matters most, three times better at the median, and
  the worse of the two at the tail. Both are far beyond what a gamepad needs. Neither is a reason
  to choose one over the other; Low Energy earns its place by owning the identity, not by being
  faster.

  **Bonding is the wall, and the probe never got over it.** With the Mac's pairing forgotten, a
  probe that advertises, serves the identity and carries the Thor's real sticks and buttons gets
  as far as being found and connected to, and no further. Requiring encryption on the HID
  characteristics changed nothing; requiring it on the identity itself only made the Mac give up
  sooner. No bond ever formed on either side. That is not Low Energy's fault and probably not
  macOS's: the Thor holds a Low Energy bond with an 8BitDo pad quite happily, so the device can do
  this — a `BluetoothGattServer` that never takes part in pairing cannot. Settling whether a host
  accepts a Low Energy gamepad from an Android app therefore needs the real transport with real
  bonding; there is no smaller experiment left that answers it.

  **Low Energy needs a bond, and that is why the probe kept falling over.** Measuring it in
  isolation did not work: with Classic disconnected the Mac would read the identity and then never
  subscribe, while with the Classic link up it subscribed every time. The probe never pairs, so
  the only bond it ever had was the Classic one the pad already holds with that Mac, which Low
  Energy could lean on. A real implementation has to pair on its own, and until it does this will
  keep looking flaky for reasons that have nothing to do with the radio. The Low Energy row above
  is therefore measured with Classic also connected, and its tail may be the two contending.

  **Closing one link before opening the other is the app's job.** Verified while doing this: when
  the pad hides, the sink closes and the Mac loses the device completely, no HID entry left at all.
  The mechanism exists; it wants attaching to the transport choice rather than to hiding the pad,
  so that picking one identity tears down the other rather than leaving two links to the same host
  in two modes at once.

  **The Low Energy transport works, bonding and all, measured 2026-09-19.** `BleSink` sits beside
  the Classic sink behind `PadTransport`, and the two share one button table and one report
  descriptor so a host can never be shown two gamepads that disagree. The chain, first attempt:
  the Mac connected, was asked to pair, bonded, negotiated a 517-byte packet, subscribed, and took
  presses — arriving under `1209:5350`, an identity of ours rather than the handheld's chipset.

  Three things made the difference over the probe that failed all evening, in the order they
  mattered. **It asks to pair rather than waiting to be asked**: a peripheral is supposed to let
  the host notice an encrypted characteristic and start pairing, and across four attempts this Mac
  never did — it connected, read what was in the clear, and hung up. **Nothing useful is readable
  without encryption**, so a host that ignores the invitation gets a device it cannot use instead
  of one that half works. **The profile is complete**, battery service and report-reference
  descriptor included, both of which the probe lacked.

  **A bonded host does not ask twice to be sent reports, and remembering that is our job.** After a
  reinstall the Mac reconnected by itself, said it was bonded, and then sat in silence: the sink
  had reset its idea of whether anyone was subscribed and was waiting for a request the host had
  no reason to repeat. Storing that per bonded client is the peripheral's responsibility in the
  specification, and skipping it looks exactly like a dead link.

  **The bond was the wrong kind, and that explains all of it.** `createBond()` does not let the
  caller say which radio, and for a machine Android already knows over Classic it picks Classic.
  Checked on 2026-09-19, the bond with the Mac read `[BR/EDR]` every time while the pad was
  talking Low Energy. A Classic bond carries no Low Energy identity key, and without that key a
  Low Energy address is disposable — the host has no way to know the same pad is back. Hence five
  entries called Thor in the Mac's list, none of which it offered to forget, and no re-attaching
  after anything at all. The one session that did work was living off keys derived from the
  Classic bond, which is the same confound that produced the withdrawn Xbox finding above.

  The method that names the radio is not in the public API, so it is asked for by name with the
  plain one as fallback. Whether Android 13 allows the call is unconfirmed; the log says which
  path it took.

  **Both sides must forget, or nothing changes.** Forgetting the pad on the host removes only the
  host's copy. The handheld keeps its own bond with stale keys, and a fresh pairing attempt then
  never reaches the server at all — seen on 2026-09-19 as pairing attempts that produced no
  connection whatsoever on this side while the Thor still listed the Mac as bonded. There is no
  adb command for it; it is done from the handheld's own Bluetooth settings.

  **Confirmed working within a session, over a genuine Low Energy bond.** On 2026-09-19, with
  both sides forgotten first and the `TRANSPORT_LE` request honoured, the Mac bonded `[ DUAL ]`
  (Classic absent from the pair until Android added its own half), created a HID device at
  `045e:02e0`, and `GCController` reported `category=Xbox One`. Real controls were injected and
  arrived correct: A as button one, the D-pad as a hat that rested at eight. So the Xbox identity
  does work over Low Energy — the withdrawn finding above was right in substance and wrong in
  method, and this is the version that stands, because there was no Classic bond underneath it
  this time.

  **Still broken: re-attach after the app's process dies.** Hide and show is fine now. A cold
  restart is not: the Mac reconnects at the GATT level, no new bond is needed, and then nothing —
  no subscription, no HID device, until both sides forget and pair again. Two things point at the
  cause and neither is cheap to fix from the app layer. The attribute table is rebuilt from
  scratch on every process start while a host caches the layout of a bonded peripheral and does
  not re-read it; the proper cure is the Service Changed indication, and Android owns that
  characteristic rather than exposing it. And the advertising address comes up different every
  process start — a scan from the Mac saw a distinct CoreBluetooth identifier each session — so
  even with an identity key from the bond the host may be failing to resolve the new address to
  the old pairing, which is why its list fills with entries called Thor that it will not offer to
  forget. Remembering each host's subscription across restarts is done now and is necessary, but
  it cannot be exercised until the host re-subscribes, and the host does not, so it is not
  sufficient. Whether this is solvable without a persistent identity address — which the app-level
  advertiser does not obviously grant — is the open question, and it is the thing to settle before
  leaning on Low Energy as anything more than a per-session pairing.

  **A pad is findable because someone asked, not because it is switched on.** The first version
  of the Low Energy sink advertised continuously for as long as it was up, defended on the grounds
  that this is simply what a Low Energy peripheral does. It is not. An ordinary controller is
  discoverable because its owner held a button, and the difference matters twice over: a pad that
  shouts its name at every device in the room is rude, and it is how a machine's list fills with
  entries called Thor that it will not offer to forget. So there are two kinds of advertising here
  now. Findable, with the name and the gamepad service, for two minutes after someone presses the
  button on the pairing page. And quiet the rest of the time — still advertising, because a Low
  Energy peripheral cannot dial its host and can only wait to be reached, but with no name and no
  service, so a scanner sees something anonymous rather than a gamepad it will never be offered.
  It also stops entirely once a machine connects, which is what a controller does and what the
  first version did not.

  **Claiming the Xbox identity without Xbox's report layout is worse than not claiming it.**
  Confirmed on 2026-09-19 by playing rather than by reading bytes: a Mac paired over Low Energy
  took the pad as `category=Xbox One`, and then both sticks sat pinned to a corner and no button
  did anything. The reports leaving the pad were correct throughout — read raw, A arrived as
  button one and the hat rested at eight — so nothing was wrong with what was sent. The host was
  reading nine bytes of ours through a map written for about sixteen of Microsoft's, with 16-bit
  axes where ours are 8-bit, so every field came out of the wrong offset. Steam saw nothing at all.

  This is the cost of the identity, and it is all or nothing: a name that a host recognises brings
  that host's layout with it, so claiming the name means emitting that layout too, byte for byte.
  Until that is done the honest identity is the only one that works, because a host with no map
  for us falls back to reading the descriptor we actually publish. Which is the reverse of how it
  was first written up here: the Xbox identity is not a shortcut past the mapping files, it is a
  much larger commitment than them.

  **What an Xbox-compatible pad actually sends, read off one that works.** Two 8BitDo pads here
  claim `045E:02E0`, the same numbers this can claim, and a Mac takes them where it will not take
  this. Their descriptor is readable from the host — `ReportDescriptor` on the `IOHIDDevice` — so
  the shape does not have to be guessed. Theirs is 306 bytes to our 86, and the input report is
  sixteen bytes against our nine:

| | an 8BitDo at 045E:02E0 | this pad |
| --- | --- | --- |
| order | sticks, triggers, hat, buttons | buttons, sticks, triggers, hat |
| left stick | X, Y — 16-bit unsigned, 0‥65535 | 8-bit signed, −127‥127 |
| right stick | Rx, Ry — 16-bit unsigned | 8-bit signed |
| triggers | Z, Rz — 10-bit, 0‥1023, each padded to two bytes | 8-bit, 0‥127 |
| hat | four bits, logical 1‥8, zero for centred | four bits, logical 0‥7, eight for centred |
| buttons | ten, then six bits of padding | sixteen |
| report | sixteen bytes | nine |

  It carries three more reports beyond the pad itself: a system-control bit under report two,
  rumble as an output report under three, and battery strength under four. None of those are
  needed to be believed, but they are what a host expects to find.

  So the sticks pinning was a host reading our button bits as the top half of a sixteen-bit axis,
  and even the hat is out by one — their centre is zero where ours is eight. Claiming the identity
  means sending this, and none of it is difficult now that it has been read rather than guessed.

  **Checked against the real thing, control by control.** The layout above was transcribed from a
  working pad's descriptor; its reports were then watched while each button was pressed in turn,
  which settled the part a descriptor does not say — which physical control is which bit. Bits
  zero through five arrived as A, B, X, Y, L1 and R1 in that order, with Select and Start on six
  and seven, so the conventional arrangement is the real one. A resting report reads
  `00 80 ff ff 00 80 00 80 …`: sticks at `0x8000`, little-endian, and a hat of zero for centred.
  That confirms two things this pad had backwards — a stick's rest is the middle of an unsigned
  range rather than zero, and the hat's rest is zero rather than eight.

  Watching a controller from the host is fiddlier than it looks and two attempts produced nothing
  at all. `IOHIDDeviceRegisterInputReportCallback` keeps the buffer pointer it is given, so a
  Swift array passed with `&` is a dangling pointer the moment the call returns; it wants heap
  memory that outlives the call. And a listener launched with `open` is a background app that
  macOS will quietly stop running, so it needs `NSAppSleepDisabled` and a heartbeat line, without
  which an empty capture cannot be told from a sleeping one.

  **The Xbox shape works, measured 2026-09-19 after it was fitted.** RPCS3 and RetroArch both
  pick the pad up with no configuration at all — worth noting because the mapping files written
  for them earlier are keyed to the old identity and cannot be what is matching. Apple's framework
  reports the pad with an `extendedGamepad` profile, which is the thing native games require, and
  input reaches it: a press arrives as `button value=1.0` and a stick sweep as `x=-1.0`. So the
  whole chain up to and including the framework is sound.

  Native games still not responding is therefore most likely about which controller a game takes
  rather than about this one. Three were attached at once during the test — the pad, an 8BitDo,
  and something calling itself GamePad-1 — and a game usually binds player one to the first it
  finds. Untested; disconnect the others and try again before looking anywhere else.

  ### Next, in the order they are worth doing

  **Report firmware version 0x0903 rather than 0x0100 in the PnP ID.** One line, diagnosed above:
  it is the two bytes that put this pad outside Eastward's mapping table. Changing it changes the
  identity, so everything paired needs pairing again — worth doing first so that the re-pair is
  shared with anything else in this list.

  **Give M1 and M2 somewhere to go, as paddles.** An Xbox Elite Series 2 carries four of them and
  Apple's framework exposes `paddleButton1` through `paddleButton4` on `GCXboxGamepad`, so the
  buttons this handheld has that a standard pad does not are not homeless after all — they just
  need an identity that admits to having them. Two things to establish: the Elite's own product
  number and report layout, which the same trick will read off one if ever there is one to hand;
  and whether a plain Xbox identity tolerates the extra buttons anyway, which is cheaper to try
  first and costs only a re-pair. Until then they are dropped in Xbox mode, which is also why they
  are swallowed rather than left alone — see the rough edge below.

  **Motion, if the handheld has any.** Not yet checked: the Thor was off the wire when this was
  written, and `dumpsys sensorservice` will say. Worth knowing that no Xbox pad reports motion at
  all, so this cannot ride on the identity above. Apple's `GCMotion` comes from DualSense,
  DualShock and Switch Pro, which means a third shape and a third identity rather than an addition
  to the second — and those carry motion inside their own report layouts, so it is the same job
  again: read a real one, match it byte for byte. Larger than it sounds, and worth deciding
  whether anything you play actually wants it before starting.

  **Volume is far too coarse, and the app cannot fix it.** Reported 2026-09-19 for the speaker and
  wired headphones both: the step from nothing to the first notch is already loud, which makes the
  quiet end unusable in a quiet room.

  Measured the same evening. `dumpsys audio` gives `STREAM_MUSIC  Min: 0  Max: 15`, and
  `ro.config.media_vol_steps` is unset, so the handheld is on the framework's default of fifteen
  steps. Fifteen steps across a range the ear hears logarithmically is the whole of it, and no
  curve applied to the slider can help: a curve cannot invent steps that are not there. The pad's
  slider maps its travel onto the index, and there are sixteen places for it to land.

  The lever exists and is out of reach. That property is read-only and was unset, so shell can set
  it — `setprop ro.config.media_vol_steps 30` succeeds — but `Max:` stays at fifteen, because
  AudioService reads it once at boot. And a read-only property is not a persistent one, so it is
  gone by the next boot, which is the only moment it would matter. Setting it at runtime is
  therefore exactly useless.

  Which leaves root, and nothing else: a Magisk `resetprop` early enough in boot, or the build
  properties themselves. Worth saying plainly in case it comes up again, rather than being
  rediscovered as a bug in the pad.

  **A Mac's nearby-devices list stopped settling, and the cause is not known.** Reported
  2026-09-19: devices sit in that list permanently, and an 8BitDo pad that had always stayed
  quietly in the paired list now appears among nearby devices while it is connected. It had never
  done that before that evening's Low Energy work, and it is a pad this project never touched.

  **A previous version of this note blamed a filled bond list. That was wrong and is withdrawn.**
  The reasoning was that every session before the bonding fix left another Low Energy bond behind,
  enough of them to fill the controller's resolving list and stop it recognising anything else.
  Counting them on 2026-09-20 killed it: thirty devices bonded on that Mac and exactly one Thor,
  at the handheld's public address. Nothing accumulated, so nothing was filled. The theory was
  written from a hunch the same evening and never checked.

  What is actually known is thin. The affected pad is `2dc8:6012` over Low Energy, and Low Energy
  is what was churned — but its identity is not one this pad ever claimed, so an identity
  collision does not explain it either, even though the Mac's bond record for the Thor does now
  carry Microsoft's numbers alongside three 8BitDo pads using the same. Ruled out: nothing of ours
  left running, `bluetoothd` not restarted in twenty-two days, and the pad not even advertising
  when the behaviour was seen.

  The test that would settle it, before anyone theorises again: stop the pad, see whether the list
  still churns, then toggle the Mac's Bluetooth and look once more. That separates something
  living in the Mac's state from something this pad is still doing.

  ### Rough edges, found 2026-09-19

  **Fixed 2026-09-20.** Three of these were one fault: the panel is drawn when the app changes
  something and never when the world changes underneath it. So a machine unpaired in Android's own
  settings went on being offered as a destination, a machine that had just paired did not appear
  until the destination was switched away and back, and a countdown put on screen once showed the
  number it had at that moment and then sat there.

  The bond watch used to exist only during a pairing window and only noticed bonding, never
  unbonding. It now runs for the life of the service and handles both, with the window kept for
  the judgement it was really making — a bond formed while the user is pairing from this app is
  one they meant to add, and the handheld's own list is mostly headphones. The countdown had a
  tick already; the Classic path started it, having polled the adapter until the device went
  discoverable, and the Low Energy path had nothing to wait for and so never started it. Same
  tick, now started by both.

  **Claiming an Elite for the sake of the extra pair is not worth it.** Looked at on 2026-09-20,
  because an Elite Series 2 carries four paddles and this handheld has two buttons a standard pad
  has no name for. Its Bluetooth identity is `045E:0B05`, and the mapping SDL already holds for it
  reads `back:b31 … guide:b53 … leftshoulder:b6 … lefttrigger:a6`. Buttons thirty-one and
  fifty-three: a far larger and quite different report from the one copied here, and there is no
  Elite on the bench to read it off, which is the only method that has worked. And the entry maps
  no paddles at all, so the identity would not even deliver the thing it was wanted for.

  **Where the middle button and the extra pair actually go.** The identity this pad now claims is
  in that database by name: `030000005e040000e002000003090000` is an Xbox One Controller, mapped
  through button ten with `guide:b10`. Which settles two things. The middle button belongs at ten,
  and an earlier attempt that put the extra pair there would have made M1 fire as the guide. And
  the pair goes after it, at eleven and twelve, outside what the built-in mapping covers — visible
  to anything reading raw buttons, a layout editor or an emulator's binding screen, and invisible
  to anything leaning on that mapping. That is the honest ceiling without inventing hardware.

  The middle button is sent twice, in the pad's own report at bit ten and as a system-menu bit in
  a second report. Its two readers disagree about where it lives: the hardware this imitates
  carries it in the second report, and the database expects it in the first. One bit is a cheap
  price for not having to guess which reader matters.

  **Only Low Energy is offered now.** Decided 2026-09-20, once the Low Energy path had carried a
  native game, two emulators and Steam without a configuration file between them. Classic works
  and its code is kept, but it has nothing to offer that the other does not: over Classic the
  vendor and product numbers a host reads belong to the handheld's Bluetooth chip and cannot be
  changed, and those numbers are the whole of how a host decides what a pad is. Over Low Energy
  they are the pad's own, which is what makes one mapping serve every handheld this runs on
  instead of one per radio.

  So the panel no longer asks which radio, only what the pad should call itself, and a destination
  is a machine rather than a named one — over Low Energy there is nothing to pick, because the
  computer comes to the pad and whichever one does is the answer.

  One thing is genuinely untested and is why the setting was kept rather than deleted: a Windows
  machine reached through CrossOver was working over Classic and has never been tried over Low
  Energy. If it turns out to want Classic, `btTransport` is how it comes back, and nothing in
  `BluetoothSink` has been removed.

  **A trigger rests at −1, not 0.** The Gamepad API stretches every axis across −1 to +1, so an
  untouched trigger reads as the far negative end and a fully pulled one as +1. Half travel is
  therefore roughly 0. This is normal for a controller the host does not recognise by name and
  cannot be fixed in the descriptor; a remapping screen has to know it.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/jni/sidepad_native.c` | evdev/uinput JNI: open, capabilities, write, read, create |
| `app/src/main/aidl/.../IInjector.aidl` | Binder contract between the app and the shell-side service |
| `app/src/main/java/.../inject/` | `InjectorService` (runs under Shizuku), `Injector` (client), `Codes` catalogue |
| `app/src/main/java/.../pad/` | layout model, press planning, overlay windows, editor, panel, guide, foreground service, QS tile |
| `app/src/main/java/.../MainActivity.kt` | the setup checklist and device-level settings |

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, Android SDK with NDK 28. The native library is compiled by a Gradle task that calls
the NDK's clang directly (ndk-build cannot cope with spaces in a checkout path).

## Release builds

Release APKs are signed with a key kept outside the repo. `app/build.gradle.kts` reads
`~/Library/Application Support/thor-sidepad/keystore.properties` (storeFile, storePassword,
keyAlias, keyPassword); without it a release build falls back to the debug key. A
debug-signed install and a release-signed one cannot update each other.

```bash
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Driving the pad from outside

Send a broadcast, from adb, Tasker or a vendor panel:

```bash
adb shell am broadcast -a dev.lbento.thorsidepad.TOGGLE -n dev.lbento.thorsidepad/.TriggerReceiver
```

Actions: SHOW, HIDE, TOGGLE, EDIT, PANEL, GUIDE, START, STOP.

`TriggerActivity` accepts the same actions and is kept only for launcher shortcuts, which can
launch an activity and nothing else. Prefer the broadcast. Starting any activity on the top
screen pauses the app playing there with `userLeaving` set, which a video app reads as the user
walking away: YouTube and Netflix drop into picture-in-picture, and the launcher then takes the
screen because the video's task is no longer a full-screen one. A shortcut has to live with
that; anything that can send a broadcast should. A caller stuck with `am start` can soften it
with `-f 0x10040000` (`NEW_TASK | NO_USER_ACTION`), which is what the pad's own focus hand-off
uses.

## Starting Shizuku from a computer

```bash
adb shell 'cp /data/app/*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so /data/local/tmp/shizuku_starter && chmod 755 /data/local/tmp/shizuku_starter && /data/local/tmp/shizuku_starter --apk=$(pm path moe.shizuku.privileged.api | sed s/package://)'
```

## The Thor's Home button, and why the pad cannot do anything sensible with it

Home and Back sit on `/dev/input/event9` — the same node as every stick, trigger and face
button. `EVIOCGRAB` takes a node whole, so while the pad forwards the controller it necessarily
holds those two as well. There is no arrangement where the machine gets the sticks and the
handheld keeps its Home key.

Three ways of handing Home back were measured on 2026-09-20, and all three are wrong in the
same way — a synthetic press carries no screen with it, where the real one does:

- **Re-injected on our own uinput device.** Android reads it as a plain home key and sends every
  display home. It also never reaches whatever handles that button natively, because that is
  watching the controller, which we hold.
- **`am start --display N` for the home activity.** Refused: the home app is a single instance,
  so Android answers *"Activity not started, intent has been delivered to currently running
  top-most instance"* and the one running instance takes the whole device home.
- **`input -d N keyevent 3`.** Delivered, and the right display is named, but the result is still
  both screens. This is the route the pad's own Back-on-this-screen button uses, and it works
  for Back; Home is treated differently.

Pressing it on the real node, which is how the on-screen "Home on this screen" button manages
it, is not open to us: we are the ones holding that node, so the press would be read straight
back by our own reader and go round again.

Home is therefore handed back as a plain home key, which is worth more than a button that does
nothing at all. Back needs none of this — it is a generic navigation key, so re-injecting it
from any device behaves exactly like the real thing.

Worth knowing before blaming the pad: on this Thor the per-screen Home behaviour was broken
independently of SidePad, and stayed broken with the app disabled and after a restart. Nine
installed apps declare `android.intent.category.HOME`, one of them the button interceptor
itself, and several accessibility services compete for the key. A single press produced one
`input -d 4 keyevent 3` from us and then two launcher starts from the interceptor — the
duplication is downstream of anything we send.

## The controller follows the display, not the device's sleep state

Measured on the Odin 2 Mini, 2026-09-21, with the Thor confirmed identical in construction.

"Keep playing with the screen dark" holds the screen **on** at zero brightness. That looks like a
workaround for not being allowed to turn the display off, and it is not — turning the display off
is possible, and it breaks the controller. The panel has to stay on. Only its brightness can go.

Both handhelds synthesise their gamepad in software: `/proc/bus/input/devices` reports
`Xbox Wireless Controller` at `/devices/virtual/input/...`, not a real HID node. Something of
AYN's reads the physical controller and feeds that virtual device, and **it stops when the display
powers off**, whatever the device's wakefulness says.

Three routes were tried against a steady 4 Hz stream of real button presses, timestamped by
`getevent` and compared against a window bracketed with `/proc/uptime`:

- **`PARTIAL_WAKE_LOCK`, letting the screen sleep.** The lock is genuinely taken — visible in
  `dumpsys power` as `SidePad:controller`. It keeps the CPU alive and lets the display go, so the
  device dozes and the controller stops with it. This is what the setting used to do, which is why
  it never worked.
- **`SurfaceControl.setDisplayPowerMode(token, 0)`.** Reachable: the shell user holds
  `ACCESS_SURFACE_FLINGER` and `DEVICE_POWER`, so the Shizuku injector can call it, and it does
  blank the panel while `mWakefulness` stays `Awake`. The controller carried on for **0.9 seconds**
  and then went silent for the whole 16-second window, resuming the instant the display returned.
  Thirteen presses before, four in the first second of darkness, nothing at all after that.
- **Screen on, brightness floored to 0.** Works, because the display never leaves. Both handhelds
  report `mScreenBrightnessMinimum=0.0`, so this goes fully black rather than merely dim.

The dock behaves differently for a reason that fits: docked there is an active video output, so
AYN's own condition is satisfied and the internal panel can go dark with the controller still
alive. That is what `settings system keep_screen_on` and their `VideoOutputMode` machinery are
for. Undocked there is no way to satisfy it — hence brightness, not power mode.

### What the setting is actually for

Not comfort — play is interrupted on a timer without it. The injector grabs the controller with
`EVIOCGRAB` so presses do not also reach the handheld, and a grabbed device delivers to nobody
else, Android's own input reader included. So however hard someone is playing, Android registers
no user activity, `screen_off_timeout` runs unopposed (1800000 ms, thirty minutes, on both
handhelds), and the display sleeps in the middle of a game.

What that costs is worth being exact about, because it is easy to overstate and this document
did. **The connection survives.** The link is held by the Bluetooth controller and the bond does
not care that the device slept; confirmed on 2026-09-21 by sleeping a connected handheld and
waking it, whereupon presses reached the Mac immediately with nothing re-paired and nothing lost.
What stops is the *generation* of events, for as long as the panel is off. So the cost is an
interruption that has to be woken out of, not a dead session — annoying mid-game, not fatal.

The fix is to report the presses rather than to pin the screen on. `PowerManager.userActivity`
resets the same timer a real press would, and is exactly what a grabbed device stops happening by
itself. It needs `DEVICE_POWER`, which the app does not hold and the shell user does, so the call
lives in the injector (`pokeUserActivity`, reflection — it is not in the SDK) and is made from
`ControllerForwarder` when a button arrives, throttled to once a minute against a timeout of
thirty.

Only buttons count, not axes: sticks report continuously including at rest, so an axis event says
nothing about whether anybody is there.

Measured 2026-09-21 by making the call as the shell user through `app_process`. On an awake Odin
2 Mini `lastUserActivityTime` went from 949 seconds ago to 919 milliseconds ago — the timer is
reset exactly as a real press resets it, and the three-argument
`userActivity(long, int, int)` overload the injector uses is present on this build.

The same call on the Thor reported success and moved nothing, because that handheld was `Asleep`
at the time. `PowerManagerService` ignores user activity when the device is not awake, and this
call does not wake anything — deliberately. It holds off a timeout on a device somebody is
playing on; it cannot rescue one that has already slept, and is not meant to. Anyone testing this
should check `mWakefulness=Awake` first or they will conclude it is broken.

This is better than holding the screen on for the whole session, which is what the setting used
to do. The handheld now sleeps normally the moment play stops, and while play continues the
screen stays up for the ordinary reason — somebody is using it. It also keeps Doze from arming,
which matters because the app is not on the battery-optimisation whitelist
(`dumpsys deviceidle whitelist`) and its partial wake lock would be ignored there.

Brightness is left alone. Anyone who wants a dark screen can turn it down themselves.

If this is revisited, the test that settles it is the one that removes human timing: capture
`getevent` continuously, wait until presses are demonstrably arriving, and only then black the
screen from a second process. Runs that asked for presses "during the dark period" produced
clusters at the boundary that read equally well as "buffered and flushed" or "pressed late", and
were worthless.

## The trackpad, and the question the dock will answer

First tried on 2026-09-21. A trackpad unit on the pad drives this device's own pointer through a
uinput mouse the injector creates on first touch. It works: dragging moves a cursor, tapping
clicks, two fingers scroll.

The cursor appears on the **top** screen of the Thor — the default display — not the screen the
pad is drawn on. That is Android's doing rather than a choice of ours: a uinput mouse is a system
pointer and the system decides where it points. It happens to be the right answer here, since the
hand is on the bottom screen and the thing being pointed at is on the top.

**Open: what happens when the handheld is docked to an external monitor.** A dock adds a display,
and where the pointer goes then is not something this has been tried against. Three outcomes are
possible and only one needs work — it follows the external display (right, nothing to do), it
stays on the built-in one (wrong, and probably needs the pointer associated with a display), or
it becomes unreachable on whichever screen is not in use. Worth an afternoon with the Odin
Station before any of this is called finished.

Two things already known that bear on it: the pad's own display is chosen by
`PadOverlay.resolveDisplayId`, which prefers a screen that is not the default one — so on a
docked single-screen handheld the pad and the pointer may end up on different displays by
different rules. And `/dev/uinput` devices carry no display association at all, so if the pointer
does land in the wrong place, the fix is not in the device we create.

## Two links at once: a pad on Low Energy, a mouse and keyboard on Classic

Raised 2026-09-21, and it replaces the plan recorded above for how mouse and keyboard reach
another device.

The earlier thinking was a composite descriptor: gamepad, mouse and keyboard in one report map,
which rules out the Xbox identity entirely, because that identity is worth having only while it
is a byte-exact copy of real hardware. Anything added ends it. So mouse and keyboard were going
to cost the good controller.

They do not, if they arrive on a **second connection**. The handheld can be a Low Energy HID
peripheral and a Classic HID device at the same time — `bluetooth.profile.gatt.enabled` and
`bluetooth.profile.hid.device.enabled` are both true on the Thor and the Odin 2 Mini. The other
device then sees two things, which is what it would see if somebody had plugged in a gamepad and
a keyboard:

- **Low Energy** — the Xbox controller, descriptor untouched, exactly as it is now.
- **Classic** — a mouse and keyboard, free to declare whatever it likes because nothing about it
  is pretending to be particular hardware.

This is also why the destination split matters beyond the case it was built for. The built-in
controller and the on-screen controls already point where they like; giving each its own radio
is the same idea one step further.

### What it would take

`btTransport` and `btSink` are both single today: one radio, one sink, shared. This needs a
transport and a sink per destination, and `openMachineSink` to build whichever kind each asks
for. The split itself is done, so this is plumbing rather than design.

### What is not known

Whether Android will hold a GATT HID peripheral and a `BluetoothHidDevice` registration at once.
Both roles are enabled; that is not the same as both working together, and it is the first thing
to find out — a throwaway build that registers both and connects to a Mac answers it in an
afternoon.

And the Classic path is stale. Everything measured this week — the six-platform matrix, the
pairing behaviour, the reconnect story — was over Low Energy. Classic has been unreachable from
the panel for a while, and macOS files the handheld as a phone over it, which is the mess that
cost 2026-09-20. None of that is inherited; it would need its own pass.

## 2026-09-22: a day on stick lag, and what it eliminated

The symptom, in the words that finally made it tractable: the stick overshoots what was meant,
and it is **intermittent inside a single session** — right for a while, then wrong, on the same
build, the same pairing, the same game. That last part is the most useful fact of the day. It
rules out everything fixed at build time before any measurement is taken.

What follows is mostly negative results. They cost a lot to get and they are worth more than the
theories they killed, because each one is a place nobody needs to look again.

### The one difference between us and a controller that works

Three controllers were connected to the same Mac at once and compared: ours, an 8BitDo Ultimate 2
Wireless, and a genuine Xbox Wireless Controller. macOS logs its Low Energy HID decisions, and
they read:

```
LE Connection Interval(30.00 ms) Latency(0) appearanceValue(0x0)   of Device(Odin2Mini)
    is not preferred. Adjust HID Sniff Interval.                               ×26
don't allow LE Connection(default) Parameter Override by Peer, appearanceValue(0x0)   ×18

LE HID [8BitDo Ultimate 2 Wireless] current 15.00 ms latency(0) appearanceValue(0x3c4) updated
LE HID [Xbox Wireless Controller]   ... appearanceValue(0x3c4)
```

`0x03C4` is the Bluetooth SIG appearance for a gamepad — category 15 (Human Interface Device),
subcategory 4. Both working controllers publish it. We publish `0x0`. macOS reads that value when
it decides whether to honour a peripheral's connection-parameter preference, and for `0x0` it
prints, in as many words, that it will not.

This was already known and written down in `BleSink.addServices` on 2026-09-20, including the
attempt to publish Generic Access ourselves and why it fails. It was re-derived from scratch today
at considerable cost. **Read that comment before going near this again.**

What today added: the refusal is not absolute. macOS caches our preference (`cache device's
preference CI is 15.00 ms`, thirty-two times) and does sometimes apply it — 15 ms seven times,
7.5 ms once, 30 ms twice, across one afternoon. The link oscillates rather than sitting at 30 ms.
That is the right shape for a fault that comes and goes inside a session, and it is the only
mechanism found all day that varies on that timescale.

### Everything that was ruled out, and how

**The HID descriptor, the identity, and the driver path.** Ours is byte-identical to the real Xbox
controller as macOS sees it — same vendor `0x045E`, product `0x0B13`, version 1312, same 283-byte
report descriptor, same report IDs, same input/output/feature sizes, same collections. Both are
bound to the generic `IOHIDEventDummyService` rather than Apple's `AppleGCHIDEventDummyService`,
which the 8BitDo gets. So that split is 8BitDo-versus-Xbox, not us-versus-real, and it is not a
fault. Our HID presentation is indistinguishable from the genuine article.

**The connection interval, as a thing to fix.** The 8BitDo opens at `interval 24, LSTO 72` — the
same 30 ms — on a Mac that opens *every* Low Energy link at 30 ms, mice and keyboards included,
and it plays correctly. A number a working controller also lives with is not the fault.

**Report rate.** Measured against the 8BitDo on the handheld's own evdev nodes: roughly 37
syncs/sec from the Odin's controller and 26/sec from the 8BitDo, against a link carrying about 33.
Comparable, not multiples. There was never a flood, so there was never a backlog to remove.

**Both controllers are silent at rest** — zero events in six seconds untouched. Real hardware
reports on change, not every interval. The event-driven design was right.

**Anything fixed at build time**, by the intermittency argument above. The identity change to the
Series pad is cleared directly: it was tested at 11:49 on 09-21 and reported as behaving exactly
like real hardware.

**The root route.** AYN's run-as-root helper (`/system/bin/pservice`, running as root with a
`run_cmd` binder interface) is real but private to AYN's own app, and there is nothing behind it
to turn: no `hci0` debugfs on Android (the stack is userspace Bluedroid, not BlueZ), and the GAP
appearance lives in the stack's compiled attribute table rather than in `bt_config.conf`.

### Two faults of our own, found and fixed

**The settle said the wrong thing.** `scheduleSettle()` repeated `lastFrame`, which is only
assigned when the radio *accepts* a frame. A refused frame never becomes `lastFrame`, so after a
loss the insurance re-sent the state from before it — telling a host that was merely holding a
stale stick position that the stick really was still held over. Now it reads the shape when the
timer fires. Fixed in `b3dd13c`.

**Sticks and triggers were quantised.** The shared `BluetoothSink.CAPS` forced every axis through
±127 and every trigger through 0…127, then the Xbox shape stretched them back to 16 and 10 bits —
255 of 65,535 stick positions and 128 of 1,024 trigger positions. Each shape now declares what its
own report holds. Fixed in `907115f` and `92dfbf5`.

Neither fixed the reported symptom. Both were real.

### Three changes made today that were wrong, and why

Recorded because the reasoning was plausible each time and the results were not.

**Asking for a shorter interval by riding a client connection** (`af3b83c`, reverted in `ba1cc4e`).
The request itself was correct and reached the Mac — the handheld logs
`connectionParameterUpdate() params=1 interval=9/12`, which is 11.25–15 ms. Closing that client
four seconds later took the machine's own link down with it: connect, five seconds, disconnect
with reason `0x13`, about twelve times a minute. In Steam that is a controller that will not hold
still, and the churn wedged `gamecontrollerd` badly enough to hang Eastward.

**Pacing the sender to the radio** (`0805368`, reverted in `33a5c8d`). Removing the queue and
reading the pad only when the radio was free should have removed stale positions. It made presses
and stick movement both arrive late, because `awaitIdle()` blocks on the radio's acknowledgement
before the next snapshot is even taken — the pipeline became serialised on the radio, where the
old queue had work prepared and handed it over the instant a slot opened.

**Latching button presses** (`eeba06f`, reverted with the above). Correct in principle, and
necessary only because the pacing change had removed the protection `collapse()` already provided.

The pattern in all three: a mechanism reasoned out, then built, then measured. The measurement
should have come first every time.

### Apple's connection-parameter rules, for reference

From the Accessory Design Guidelines, verbatim — a request may be rejected if it does not comply
with **all** of:

```
Interval Max * (Slave Latency + 1) ≤ 2 seconds
Interval Min ≥ 20 ms
Interval Min + 20 ms ≤ Interval Max
Slave Latency ≤ 4
connSupervisionTimeout ≤ 6 seconds
Interval Max * (Slave Latency + 1) * 3 < connSupervisionTimeout
```

With a later addition: if Bluetooth Low Energy HID is one of the connected services, an interval
down to 11.25 ms may be accepted. Also stated there: *"The Apple product will not read or use the
parameters in the Peripheral Preferred Connection Parameters characteristic"* — so GAP `0x2A04` is
not a route.

Android's `CONNECTION_PRIORITY_HIGH` asks for 11.25–15 ms, which fails the third rule: the window
must span at least 20 ms and that one spans 3.75. This is consistent with the eighteen
`onServerConnUpdate status=59` refusals on the link. It is a real defect in what we ask for, and
it is **not** the cause of the lag, because a controller that plays correctly shares the same
30 ms.

### Measuring this again

Everything here came from four instruments; they exist because the evidence kept being destroyed.

- `btpair.sh` → `~/Library/Logs/thor-sidepad/macpair.log`. The Mac's half. `log show` cannot read
  this back afterwards without root and bluetoothd writes at debug level, so the only way to have
  it is to be listening first. `log stream` needs no privilege.
- `paircap.sh` → `pair-*.log`. The handheld's half: bond state machine, SMP, GATT.
- `linkwatch.sh` → `linkwatch.log`. A five-second time series of parameter renegotiations,
  congestion, dropped reports and disconnections. Nothing is cleared — the watcher that cleared
  logcat destroyed the evidence for everything else on the device.
- `padprops.py` in the session scratchpad. Every gamepad macOS holds, with descriptor, bound
  service class and report sizes, for diffing one controller against another.

Two traps worth knowing. `EVIOCGRAB` means nothing outside the app can watch the controller while
forwarding is on — including `getevent` — and force-stopping the app does not release it, because
the Shizuku-spawned injector survives and has to be killed separately. And a measurement of a
controller connected to the handheld measures the rate reports *arrive* over its own link, not the
rate the controller's firmware emits.

### What actually fixed it: never run ahead of the radio

Found by measuring delivery *evenness* rather than rate, which is the measurement nobody had
taken. During a stick sweep, with the link at 15 ms:

```
delivery gaps ms  <8:94  <16:57  <32:32  <64:7  <128:7  128+:3
```

Ninety-four reports handed to the stack less than eight milliseconds apart, into a link carrying
one per fifteen. Nothing was lost — `notifyCharacteristicChanged` accepted them all — but
accepting is not sending. They queued in the stack's twenty slots and reached the host late and
in bunches. RPCS3's tester drew our stick path in steps where an official controller drew a line.

The acknowledgement cannot pace this: the callback fires on acceptance, not transmission, and
returns almost at once. So the spacing comes from the clock — fifteen milliseconds since the last
report the stack took, then send, with nothing blocking on anything. `collapse()` runs after the
wait, so what goes out is the newest position rather than the one that was current when the wait
began, and a quiet pad never waits, because its last report was long ago.

After (`b98de26`), same sweep:

```
delivery gaps ms  <8:0  <16:1  <32:146  <64:13  <128:40  128+:0
congestion events: 0        (was about three a second)
reports dropped:   0
```

Reported as feeling "as good as the official control", both sticks.

### Why one stick could feel worse than the other

It cannot be the transport. All four axes travel in the same sixteen-byte report, so anything that
delays or discards one discards both. The asymmetry is either usage — a camera stick shows
decimation that a movement stick hides — or host mapping, since our right stick sits on `Z`/`Rz`
where generic HID convention often expects triggers. The descriptor matches the genuine Xbox
controller byte for byte, so the second is unlikely, but it is testable: move the right stick and
watch whether a trigger readout moves with it.

### Still open

**The interval is not ours, and it is settled early.** Every connection opens at 30 ms — that is
the `interval 24` in every Connection Complete line, for every Low Energy device on this Mac, mice
and keyboards included. macOS then refuses our preference for a second or two (twenty-one times in
one two-second stretch) and either applies 15 ms or leaves it. Laid out against connections across
2026-09-22, **every** decision landed within one to three seconds of a connection, and about half
the sessions had nothing applied at all and stayed at 30.

There is no evidence it reverts mid-session. One mid-session change was seen all day — 11:35:36,
nearly four minutes in — and it went to 7.5 ms, which is better. An earlier version of this note
claimed it could move back; that was wrong.

**The 30 ms interval is an observation, not a diagnosis.** It is written down because it is a
difference we can see and a plausible cause, and for no stronger reason than that. There is no
isolated evidence that it has ever cost us anything:

- The 8BitDo Ultimate 2 Wireless is given the same 30 ms at times
  (`current 30.00 ms latency(0) appearanceValue(0x3c4) updated`) and plays correctly. It is on the
  same band, the same host, the same radio.
- Every measurement of harm taken here at 30 ms was taken while the pad was also overfilling the
  stack, so the two cannot be separated in any of them.
- The one session measured at 15 ms before the pacing fix was still reported as jittery.

The consistent explanation for everything actually observed is the overloading, at both intervals.
Nobody should treat 30 ms as the cause without an experiment that isolates it — which would mean
measuring a paced build at 30 ms, and that has never been done.

What that leaves untested: at a 30 ms link the 15 ms floor in [BleSink.awaitSlot] is still twice
too fast, so bursting should return. Nobody has seen that happen. The delivery-gap counter is how
it would show up — gaps below the floor, and congestion climbing off zero.

The reason macOS argues with us at all is the appearance value above, and that stays out of reach.

`linkwatch.log` records parameter changes, congestion and drops in five-second buckets, so a bad
stretch can be looked up by wall clock rather than guessed at.

