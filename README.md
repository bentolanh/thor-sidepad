# Thor SidePad

Virtual controller buttons on the AYN Thor's bottom screen that the game on the top screen
receives as real gamepad input. Made for one-handed RPG play: the left thumb stays near the
left stick and drops down to A/B/X/Y on the lower screen.

No root. Needs [Shizuku](https://shizuku.rikka.app/) running (wireless debugging).

## How it works

- A Shizuku user service runs as the shell user, which on the Thor can open the controller's
  `/dev/input/event*` node and `/dev/uinput`.
- **Same-controller mode** writes key events straight into the Thor's own controller node, so
  games keep seeing one pad. Limited to buttons that controller physically has.
- **Virtual pad mode** creates a separate uinput gamepad "Thor SidePad". Some games and
  emulators will treat it as a second player, so the same-controller mode is the default.
- **M1 / M2** are BTN_C / BTN_Z, which the Thor's controller advertises and AYN's key layouts
  map to Android `BUTTON_C` / `BUTTON_Z`. They work in both modes. (The Thor kernel stamps
  every uinput pad with AYN's vendor/product ids, so stock `BUTTON_1..` codes are unmapped.)
- **Shield mode (default)**: one full-screen non-focusable overlay on the second display owns
  every touch. Nothing behind the pad can be tapped by accident, a thumb can slide from one
  button to the next, and edge swipes become gestures. Islands mode (one small window per
  button) is the alternative when the app under the pad should stay usable.
- **Pull down from the top edge** of the second screen opens SidePad's own control panel:
  show/hide, edit layout, shield, edge swipes, opacity, a button for the Thor Control Center
  (it presses the AYN key), stop. While the pad is hidden a thin strip stays parked on the top
  edge so the pull-down still works; with start-at-boot on, it is always there.
- **Pull up from the bottom edge** shows or hides the pad. While hidden, thin strips stay on
  the top and bottom edges, so both pulls keep working. **Swipe in from the left** presses the
  Thor's Back key through the controller's input node (switchable in the panel).
- **SHLD button.** Every built-in preset carries a small SHLD button that toggles shield mode
  from the pad itself; lit blue while the shield is on. Delete it in the editor if unwanted.
- **Opacity** applies to the buttons only. The shield itself is invisible; only its three
  edge pills are drawn.
- The top screen keeps input focus while the pad is used.
- Show/hide: the app, a Quick Settings tile, the notification, or holding Select + Start.
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
