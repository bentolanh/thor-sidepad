# thor-sidepad

Android app (Kotlin + small C JNI) for the AYN Thor: on-screen controller buttons on the second
display, injected as real gamepad events through a Shizuku user service. See README.md.

- Build: `./gradlew :app:assembleDebug` (JDK 17, Android SDK at ~/Library/Android/sdk, NDK 28).
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; the device must be
  plugged in or on adb wifi. Never install or launch anything on the device without asking.
- The injector runs as shell UID via Shizuku. Anything touching `/dev/input` or `/dev/uinput`
  belongs in `inject/InjectorService.kt` + `jni/sidepad_native.c`, never in the app process.
- All overlay windows must stay `FLAG_NOT_FOCUSABLE`: focus on the second display would steal
  the injected key events from the top-screen game.
- Durable notes (design, status, handoffs) live outside this repo; the README and DEVELOPMENT.md are the only docs here.
