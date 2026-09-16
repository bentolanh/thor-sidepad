# Thor SidePad

Virtual controller buttons on the AYN Thor's bottom screen that the game on the top screen
receives as real gamepad input. Made for one-handed RPG play: the left thumb stays near the
left stick and drops down to A/B/X/Y on the lower screen.

No root. Needs [Shizuku](https://shizuku.rikka.app/) running (wireless debugging).

## How it works

- A Shizuku user service runs as the shell user, which on the Thor can open the controller's
  `/dev/input/event*` node and `/dev/uinput`.
- **Presses go to** a controller of your choice, picked in the pull-down panel: the Thor's own
  controller (default), any other gamepad Android sees such as a Bluetooth pad, or a separate
  virtual pad "Thor SidePad" that shows up as a second player. Writing into a real controller's
  node means games keep seeing one pad; it is limited to buttons that controller advertises.
  Controllers are remembered by name and re-found each time the pad shows, because node
  numbers change when the Thor switches controller style or a Bluetooth pad reconnects.
- **Thor controller style** (Control Center: Standard / Xbox / Ban): the Thor recreates its
  controller node under a new name ("Odin Controller" or "Xbox Wireless Controller"); buttons
  and axes are the same. The service listens for input-device changes and reopens its target
  on the spot (a failed write triggers the same), so a style change or a Bluetooth reconnect
  while the pad is up needs no action. The panel shows the built-in pad as "Thor controller".
- **M1 / M2** are BTN_C / BTN_Z, which the Thor's controller advertises and AYN's key layouts
  map to Android `BUTTON_C` / `BUTTON_Z`. They work in both modes. (The Thor kernel stamps
  every uinput pad with AYN's vendor/product ids, so stock `BUTTON_1..` codes are unmapped.)
- **Shield mode**: one full-screen non-focusable overlay on the second display owns every
  touch. SidePad always starts with the shield off; turn it on per session from the panel or
  the on-pad toggle. Nothing behind the pad can be tapped by accident, a thumb can slide from one
  button to the next, and edge swipes become gestures. Islands mode (one small window per
  button) is the alternative when the app under the pad should stay usable.
- **Pull down from the top edge** of the second screen opens SidePad's own control panel, which
  follows the finger down like the notification shade and settles open, or springs back if the
  pull was too short; it slides away on close. The panel is one
  quick-settings page (controller, show/hide, edit layout, shield, backdrop, opacity, stop)
  plus a Controller page, opened from its
  "Controller: …" row, where presses are routed. While the pad is hidden thin strips stay
  parked on the top and bottom edges so the pulls still work; with start-at-boot on, they are
  always there.
- **Pull up from the bottom edge** shows or hides the pad. While hidden, thin strips stay on
  the top and bottom edges, so both pulls keep working. The app never listens to controller
  buttons, so it cannot interfere with a key mapper.
- **Shield toggle.** Every built-in preset carries a small shield-shaped toggle that switches
  shield mode from the pad itself: filled with a tick while on, outlined while off. Delete it in
  the editor if unwanted. Switching rebuilds only the windows, not the injector, and the new
  windows go up before the old come down, so there is no flash.
- **Backdrop** (panel, shield mode): Clear, Dim, Dark (solid black, for playing in bed) or
  Frosted (real blur behind the window on devices whose compositor supports it, which the
  Thor does; elsewhere a heavy dim). The **transparency** slider makes the buttons more see-through the further right it goes;
  it never affects the backdrop.
- The top screen keeps input focus while the pad is used.
- Show/hide: pull up on the pad's screen, the Quick Settings tile, or the notification.
- **Sticks.** The catalogue includes a left and a right virtual analogue stick. They drive the
  controller's own stick axes (X/Y, and Z/RZ for the right stick as the Thor reports it), so in
  same-controller mode a virtual stick is the same stick the game already reads.
- **Presets.** The pad always has an active preset and the editor shows its name. Built in:
  "Left hand" (you hold the left side; the screen shows A/B/X/Y, the right stick, R1/R2, Start
  and M1/M2 on the left half, under the same thumb), "Right hand" (the mirror) and "Face
  buttons". **Save** in the editor writes into the active preset. When the active one is
  built-in, Save asks for a name and creates your own copy, which becomes active. The Presets
  chooser shows cards with a miniature of each layout, marks the active one, switches with Use,
  and deletes your own. The name box is the one moment the pad takes keyboard focus on the
  bottom screen; an invisible hand-off activity gives focus back to the top screen afterwards.
- **Editor** (Edit layout in the pull-down panel, the app, or the notification): opens on the
  pad's screen. Tap to select, drag to move, pinch to resize, Add / − / + / Delete / Presets in
  the toolbar; choosers can be cancelled or dismissed by tapping outside.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/jni/sidepad_native.c` | evdev/uinput JNI: open, capabilities, write, read, create |
| `app/src/main/aidl/.../IInjector.aidl` | Binder contract between the app and the shell-side service |
| `app/src/main/java/.../inject/` | `InjectorService` (runs under Shizuku), `Injector` (client), `Codes` catalogue |
| `app/src/main/java/.../pad/` | layout model, press planning, overlay windows, editor, foreground service, QS tile |
| `app/src/main/java/.../MainActivity.kt` | permissions, target/device/display choice, test buttons |

## First start

SidePad starts with the pad hidden: only the two edge strips are there. The first time it
starts, an interactive guide runs with the shield on (frosted backdrop, opaque buttons) but
the pad hidden, and waits for the real gestures: pull down (the panel really opens; close it
to continue), pull up (the pad appears, shielded), pull up again (it hides), then an end
screen. Everything is put back afterwards, including whether the pad was
showing. "Skip the guide" ends it early. The app has a button to run it again.

## The app screen vs the panel

The app on the main screen is setup only: permissions (Shizuku, draw over apps,
notifications), which display hosts the pad, start at boot, and a Start button. Everything
about the pad itself is in the pull-down panel on the pad's screen: target controller, show /
hide, edit layout, shield, backdrop, opacity, the Select + Start chord, the Thor Control
Center shortcut, stop.

## Starting Shizuku from a computer

Shizuku must be running (it stops on reboot). From a Mac with adb:

```bash
adb shell 'cp /data/app/*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so /data/local/tmp/shizuku_starter && chmod 755 /data/local/tmp/shizuku_starter && /data/local/tmp/shizuku_starter --apk=$(pm path moe.shizuku.privileged.api | sed s/package://)'
```

Driving the pad from adb, Tasker or a launcher shortcut:

```bash
adb shell am start -n dev.linhhan.thorsidepad/.TriggerActivity -a dev.linhhan.thorsidepad.TOGGLE
```

(actions: SHOW, HIDE, TOGGLE, EDIT, PANEL, START, STOP)

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Notes, plans and status live in the Obsidian vault under `Claude/thor-sidepad/`, not here.
