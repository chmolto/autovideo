<p align="center"><img src="docs/icon.png" width="128" alt="AutoVideo icon"></p>

# AutoVideo

AutoVideo is a personal Android app for watching YouTube videos on the Android Auto screen **while the car is parked**.

> **Disclaimer:** This is a personal project, not affiliated with or endorsed by Google or YouTube.
> Use it only while parked. The built-in ad blocking is meant for personal use and may break
> whenever YouTube changes its site.

- Pick the video on your phone (Brave → Share → More → **AutoVideo**) and it plays on the car screen.
- Player only, full screen, no ads (its own blocking, no third-party libraries).
- Buttons on the car screen: pause, −10 s, +10 s, reload.
- Progress bar: one tap shows it; tapping on the bar jumps to that point; tapping the video pauses/resumes.
- Audio keeps playing if you switch to another app in the car (Maps, etc.).

Design: [docs/2026-11-15-video-android-auto-design.md](docs/2026-11-15-video-android-auto-design.md) ·
Plan: [docs/superpowers/plans/2026-11-15-autovideo.md](docs/superpowers/plans/2026-11-15-autovideo.md)

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools). No Android Studio needed.

```bash
./gradlew testDebugUnitTest      # tests
./gradlew assembleRelease        # signed APK: app/build/outputs/apk/release/app-release.apk
```

Release signing uses the key from `~/.autovideo/keystore.properties` (outside the repo).
If that file doesn't exist, the release build is unsigned.

## Installing so Android Auto shows it in the car

Android Auto only shows apps built with the Car App Library if Android believes they came from
Google Play (both the installer **and** the installation initiator). `adb install` doesn't work: it
records the PC (`com.android.shell`) as the initiator, so the app doesn't show up in the car, even
though it does show up in the simulator (DHU). "Unknown sources" alone isn't enough for this kind of app.

Install it with **UnknownAuto** (open source, reviewed and built from source) + **Shizuku** (Play Store):

1. Copy the APK to the phone: `adb push app/build/outputs/apk/release/app-release.apk /sdcard/Download/AutoVideo.apk`
2. Start Shizuku over ADB:
   ```bash
   adb shell "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://;s/base.apk//')lib/arm64/libshizuku.so"
   ```
3. On the phone: UnknownAuto → `Download/AutoVideo.apk` → Install.
4. Check: `adb shell dumpsys package io.github.chmolto.autovideo | grep -E "installer|initiating"` should show
   `installerPackageName=com.android.vending` and `initiatingPackageName=com.google.android.packageinstaller`.
5. In Android Auto → Settings → *Customize launcher*, AutoVideo should appear.

Repeat for every new version (bump `versionCode`).

It's also a good idea to enable **Unknown sources** in Android Auto → Developer settings.

## Testing on the PC with the simulator (DHU)

1. In Android Auto (phone): ⋮ → *Start head unit server*.
2. `adb forward tcp:5277 tcp:5277`
3. `%ANDROID_HOME%\extras\google\auto\desktop-head-unit.exe`

The **debug** build installs side by side (`io.github.chmolto.autovideo.debug`, "AutoVideo (debug)").
Sharing with `--ez autovideo.test_muted true` plays the video **always muted** (via YouTube's `mute()`):

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://youtu.be/aqz-KE-bpKQ" --ez autovideo.test_muted true \
  -n io.github.chmolto.autovideo.debug/com.autovideo.app.ShareActivity
```

Logs: `adb logcat | grep -E "AutoVideo|\[AutoVideo\]"` (blocked ad requests under `AutoVideo/AdBlock`).

## Ad blocking

- Blocked requests: `app/src/main/java/com/autovideo/app/AdBlockRules.kt`
- Hiding elements / skipping ads: `app/src/main/assets/inject.css` and `inject.js`

If YouTube changes its site and ads come back, that's where to make changes.

## Lessons learned

- **ALWAYS control the video through the YouTube player API** (`#movie_player`: `playVideo`, `pauseVideo`,
  `seekTo`, `mute`). If you touch the `<video>` element directly, YouTube's internal state gets out of
  sync and it pauses the video again.
- **YouTube thinks it's in picture-in-picture** if the window (`outerWidth×outerHeight`) is much
  smaller than the largest screen/window it has seen, and then pauses on every progress update.
  Since the player moves between screens of different sizes, the script pins `screen.*` and
  `outer*` to a constant value.
- Don't change the volume from code while the video is playing: YouTube reacts by pausing.

- Mobile YouTube starts videos **muted**: the script unmutes them.
- The WebView already handles **audio focus** (Chromium); also requesting it from the app made playback pause on its own.
- When the WebView moves between displays, Chromium pauses it briefly: playback is resumed after the move.
- With no car screen, the WebView is parked on an invisible virtual display so Chromium doesn't pause it.
