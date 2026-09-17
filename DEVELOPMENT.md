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
- **M1 / M2** are BTN_C / BTN_Z, which the Thor's controller advertises and AYN's key layouts
  map to Android `BUTTON_C` / `BUTTON_Z`; stock `BUTTON_1..` codes are unmapped on the Thor.
- **Windows.** Shield mode is one full-screen non-focusable overlay on the second display;
  islands mode is one small window per button. All overlay windows stay non-focusable so the
  top screen keeps input focus, which is where gamepad events are delivered. The one
  exception is the preset name box; a transparent hand-off activity returns focus afterwards.
- **Focus and the guide.** The service resets shield off at every start. The interactive
  guide forces shield on, frosted backdrop and full opacity while it runs and restores the
  user's look afterwards, persisting the snapshot so an interrupted guide is undone on the
  next start.
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

- **The media unit's volume.** The Thor scales the audio of apps on the second screen apart from
  the main stream, so "the volume of what is playing" depends on which screen the player sits on.
  The unit's track sends `Slider.VOLUME_MEDIA`, which the service resolves: it asks
  `dumpsys media_session` which package holds the media keys, finds that package under a display
  in `dumpsys window displays`, and then moves either the second screen's level or the media
  stream. It resolves when the pad shows, when a transport key is pressed, and once per drag, so
  the lookup never runs per touch move.

- **The Back key and the back gesture.** SidePad holds Back only when the user is configuring,
  never while the buttons are driving the game, because the focused screen is where injected
  presses land. The panel, editor and pickers take window focus (`focusableFlags`) and swallow
  Back in a `backFrame` root, so Back closes them; a nested picker closes first and leaves the
  editor open, and closing the editor or panel hands focus back to display 0 through
  `FocusHandoffActivity`. The pad itself stays non-focusable, so with the shield off or the pad
  hidden the system back gesture reaches the app behind as usual. With the shield up the screen
  behind is not meant to be touched, so `ShieldPadView` claims the left and right edges from the
  system back gesture with `systemGestureExclusionRects`; a back swipe there lands on the shield
  and does nothing. That needs no focus, so injection is unaffected. Android caps edge exclusion
  (`system_gesture_exclusion_limit_dp=200` on the Thor), but the full height of both side edges
  was accepted on the device: `SkRegion((0,0,73,1080)(1167,0,1240,1080))`.

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

Exported trigger activity, usable from adb, Tasker or a launcher shortcut:

```bash
adb shell am start -n dev.lbento.thorsidepad/.TriggerActivity -a dev.lbento.thorsidepad.TOGGLE
```

Actions: SHOW, HIDE, TOGGLE, EDIT, PANEL, GUIDE, START, STOP.

## Starting Shizuku from a computer

```bash
adb shell 'cp /data/app/*/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so /data/local/tmp/shizuku_starter && chmod 755 /data/local/tmp/shizuku_starter && /data/local/tmp/shizuku_starter --apk=$(pm path moe.shizuku.privileged.api | sed s/package://)'
```
