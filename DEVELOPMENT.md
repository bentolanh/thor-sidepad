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

  The open question is host compatibility rather than plumbing. Apple's controller framework
  favours known controller families, so Steam and emulators are likely to take a generic pad while
  some native Mac games may not. Whether the advertised device class can be changed from an app was
  not established.

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
  report. `CAPS` deliberately mirrors the local virtual pad: the D-pad is left out of the buttons so
  it plans onto the hat, and the triggers get `BRAKE` and `GAS` so they do not fight the right
  stick, which lives on `Z`/`RZ`.

  Two things that cost time. Without a chosen host, picking the first paired device is wrong on a
  handheld: this one's list begins with headphones and holds six controllers, so the fallback takes
  a paired *computer* by device class. And the destination is chosen in `show()`, where opening is
  asynchronous, so the pad appears before the host answers; the local reopen path is skipped
  entirely for a Bluetooth destination since there is nothing there to reopen.

  Verified against a Mac: tapping A, Y and B on the pad arrived as buttons 1, 5 and 2, down and up.
  `DEST` flips the destination until the panel carries a row for it. Development only.

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
