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
- **Virtual pad mode** creates a separate uinput gamepad "Thor SidePad" that also carries
  M1–M4 (Android `BUTTON_1..4`). Some games and emulators will treat it as a second player.
- The pad is drawn as one small non-focusable overlay window per button on the second display,
  so the space between buttons still belongs to whatever app is on that screen and the top
  screen keeps input focus.
- Show/hide: the app, a Quick Settings tile, the notification, or holding Select + Start.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/jni/sidepad_native.c` | evdev/uinput JNI: open, capabilities, write, read, create |
| `app/src/main/aidl/.../IInjector.aidl` | Binder contract between the app and the shell-side service |
| `app/src/main/java/.../inject/` | `InjectorService` (runs under Shizuku), `Injector` (client), `Codes` catalogue |
| `app/src/main/java/.../pad/` | layout model, press planning, overlay windows, editor, foreground service, QS tile |
| `app/src/main/java/.../MainActivity.kt` | permissions, target/device/display choice, test buttons |

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Notes, plans and status live in the Obsidian vault under `Claude/thor-sidepad/`, not here.
