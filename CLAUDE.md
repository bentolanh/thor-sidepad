# thor-sidepad

Android app (Kotlin + small C JNI) for the AYN Thor: on-screen controller buttons on the second
display, injected as real gamepad events through a Shizuku user service. See README.md.

- Build: `./gradlew :app:assembleDebug` (JDK 17, Android SDK at ~/Library/Android/sdk, NDK 28).
- Install: the handhelds carry release-signed builds, so a debug APK will not go over one
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Build `./gradlew :app:assembleRelease` and install
  `app/build/outputs/apk/release/app-release.apk` with `adb install -r`. The device must be
  plugged in or on adb wifi. Installing needs no permission asked each time — it is the normal
  way work gets checked here. Never `adb uninstall`: it deletes the user's presets.
- The injector runs as shell UID via Shizuku. Anything touching `/dev/input` or `/dev/uinput`
  belongs in `inject/InjectorService.kt` + `jni/sidepad_native.c`, never in the app process.
- All overlay windows must stay `FLAG_NOT_FOCUSABLE`: focus on the second display would steal
  the injected key events from the top-screen game.
- Durable notes (design, status, handoffs) live outside this repo; the README and DEVELOPMENT.md are the only docs here.
