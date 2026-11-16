# AutoVideo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app that plays the YouTube video shared from the phone on the Android Auto screen, without ads.

**Architecture:** The app registers as a navigation app (Car App Library) to get the car's `Surface`; on it, it creates a `VirtualDisplay` + `Presentation` showing a `WebView` with `m.youtube.com`. The `WebView` is owned by a foreground service (`PlaybackService`), so audio keeps playing when the surface goes away; ad blocking is `shouldInterceptRequest` + injected JS/CSS.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, compileSdk/targetSdk 35, minSdk 29, `androidx.car.app:app` + `app-projected` 1.4.0, `androidx.core:core-ktx` 1.13.1, `androidx.media:media` 1.7.0, JUnit 4.13.2.

**Spec:** `docs/2026-11-15-video-android-auto-design.md`

## Global Constraints

- Only official AndroidX/Google libraries. No NewPipe, AAAD or opaque third-party libraries.
- No Google account, no location permissions, no speed monitoring.
- Package `com.autovideo.app`, display name "AutoVideo".
- Loaded page: `https://m.youtube.com/watch?v=<id>` (with `&t=<s>s` when resuming).
- Car screen = player only; when the video ends it stops (no autoplay).
- Car buttons: Pause/Resume, −10 s, +10 s, Reload. Tapping the video = pause/resume.
- With no video loaded, the car shows: "Share a video from your phone".
- Background audio when the surface is lost; pauses if another app takes audio focus; volume ducks for short prompts; stops when Android Auto disconnects.
- Block lists and selectors in a single file each.
- UI strings in English (with a Spanish translation).

## Review Focus

1. YouTube pauses the video when the page becomes hidden (`visibilitychange`) after the surface is lost → audio must keep playing: `inject.js` forces `document.hidden=false` / `visibilityState='visible'` and suppresses `visibilitychange` (verified in Task 6, DHU step "switch to another app").
2. The `WebView` is created with one context and later shown in different `Presentation`s → use `MutableContextWrapper` and switch the base context to the `Presentation`'s on every `attach` (Task 4; verified in Task 5 by connecting/disconnecting the surface twice).
3. Shared links with surrounding text ("Check this out https://youtu.be/xyz?si=…"), Shorts, `m.`/`www.`, `music.youtube.com` → tests in Task 2.
4. Sharing a non-YouTube link → notice "That link isn't a YouTube link" and the current video is left untouched (Task 2 test + Task 4 manual).
5. Starting the foreground service from the car session may throw `ForegroundServiceStartNotAllowedException` → catch it and continue as a regular service; promote to foreground when playback starts (Task 4).

---

### Task 1: Project skeleton that builds

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml` (`app_name` = "AutoVideo"), `app/src/main/res/drawable/ic_launcher.xml` (simple vector)
- Create: `app/src/main/java/com/autovideo/app/MainActivity.kt` (empty screen with a text)

- [ ] **Step 1:** Generate the wrapper with the downloaded Gradle 8.9: `gradle wrapper --gradle-version 8.9`.
- [ ] **Step 2:** Write the Gradle files with the Tech Stack versions; `local.properties` with `sdk.dir` (git-ignored).
- [ ] **Step 3:** Run: `./gradlew assembleDebug` → Expected: `BUILD SUCCESSFUL`, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 4:** Commit `chore: Android skeleton that builds`.

### Task 2: `VideoLink` — extract the video ID from the shared content

**Files:**
- Create: `app/src/main/java/com/autovideo/app/VideoLink.kt`
- Test: `app/src/test/java/com/autovideo/app/VideoLinkTest.kt`

**Interfaces:**
- Produces: `object VideoLink { fun extractVideoId(text: String): String?; fun watchUrl(id: String, startSeconds: Int = 0): String }`

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun watch() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"))
@Test fun mobile() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
@Test fun short() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
@Test fun shorts() = assertEquals("abcdefghijk", VideoLink.extractVideoId("https://youtube.com/shorts/abcdefghijk?si=x"))
@Test fun live() = assertEquals("abcdefghijk", VideoLink.extractVideoId("https://www.youtube.com/live/abcdefghijk"))
@Test fun music() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
@Test fun withText() = assertEquals("dQw4w9WgXcQ", VideoLink.extractVideoId("Mira esto\nhttps://youtu.be/dQw4w9WgXcQ"))
@Test fun notYoutube() = assertNull(VideoLink.extractVideoId("https://vimeo.com/123456"))
@Test fun badId() = assertNull(VideoLink.extractVideoId("https://youtube.com/watch?v=short"))
@Test fun url0() = assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", VideoLink.watchUrl("dQw4w9WgXcQ"))
@Test fun urlT() = assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=95s", VideoLink.watchUrl("dQw4w9WgXcQ", 95))
```

- [ ] **Step 2:** `./gradlew testDebugUnitTest` → FAIL (doesn't compile).
- [ ] **Step 3:** Implement. Valid ID = exactly 11 characters `[A-Za-z0-9_-]`. Hosts: `youtube.com`, `www.`, `m.`, `music.`, `youtu.be`. Paths: `/watch?v=`, `/shorts/`, `/live/`, `/embed/`, `youtu.be/<id>`. First valid link in the text.
- [ ] **Step 4:** `./gradlew testDebugUnitTest` → PASS.
- [ ] **Step 5:** Commit `feat: extract ID from YouTube links`.

### Task 3: `AdBlockRules` — which requests get blocked

**Files:**
- Create: `app/src/main/java/com/autovideo/app/AdBlockRules.kt` (the lists live here, at the very top)
- Test: `app/src/test/java/com/autovideo/app/AdBlockRulesTest.kt`

**Interfaces:**
- Produces: `object AdBlockRules { fun shouldBlock(url: String): Boolean }`

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun dc() = assertTrue(AdBlockRules.shouldBlock("https://googleads.g.doubleclick.net/pagead/id"))
@Test fun syn() = assertTrue(AdBlockRules.shouldBlock("https://tpc.googlesyndication.com/x.js"))
@Test fun adsvc() = assertTrue(AdBlockRules.shouldBlock("https://www.googleadservices.com/pagead/conversion"))
@Test fun pagead() = assertTrue(AdBlockRules.shouldBlock("https://m.youtube.com/pagead/viewthroughconversion/1"))
@Test fun stats() = assertTrue(AdBlockRules.shouldBlock("https://m.youtube.com/api/stats/ads?ver=2"))
@Test fun ptrack() = assertTrue(AdBlockRules.shouldBlock("https://www.youtube.com/ptracking?pltype=adhost"))
@Test fun midroll() = assertTrue(AdBlockRules.shouldBlock("https://www.youtube.com/get_midroll_info?x=1"))
@Test fun video() = assertFalse(AdBlockRules.shouldBlock("https://rr3---sn-abc.googlevideo.com/videoplayback?id=1"))
@Test fun page() = assertFalse(AdBlockRules.shouldBlock("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
@Test fun player() = assertFalse(AdBlockRules.shouldBlock("https://m.youtube.com/youtubei/v1/player?key=x"))
@Test fun lookalike() = assertFalse(AdBlockRules.shouldBlock("https://notdoubleclick.net.example.com/"))
@Test fun garbage() = assertFalse(AdBlockRules.shouldBlock("not a url"))
```

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement: block by host (equal to or a subdomain of `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `googletagservices.com`, `imasdk.googleapis.com`) or by path on `*.youtube.com` hosts (`/pagead/`, `/api/stats/ads`, `/ptracking`, `/get_midroll_`, `/pcs/activeview`). Unparseable URL → `false`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: ad-blocking rules`.

### Task 4: Player (`PlayerController`), injected JS, `PlaybackService`, sharing and on-phone test

**Files:**
- Create: `app/src/main/assets/inject.js`, `app/src/main/assets/inject.css`
- Create: `app/src/main/java/com/autovideo/app/PlayerController.kt`
- Create: `app/src/main/java/com/autovideo/app/PlaybackService.kt`
- Create: `app/src/main/java/com/autovideo/app/VideoStore.kt`
- Create: `app/src/main/java/com/autovideo/app/ShareActivity.kt`
- Modify: `MainActivity.kt`, `AndroidManifest.xml`, `strings.xml`

**Interfaces:**
- Consumes: `VideoLink.extractVideoId`, `VideoLink.watchUrl`, `AdBlockRules.shouldBlock`
- Produces:
  - `class PlayerController(app: Context)`: `fun load(videoId: String, startSeconds: Int = 0)`, `fun togglePlay()`, `fun seekBy(seconds: Int)`, `fun reload()`, `fun attach(host: Context, container: ViewGroup)`, `fun detach()`, `fun release()`, `val hasVideo: Boolean`, `var onStateChanged: ((playing: Boolean) -> Unit)?`
  - `class PlaybackService : Service` with `companion object { val player: PlayerController?; fun loadVideo(ctx: Context, videoId: String); fun ensureStarted(ctx: Context) }`
  - `object VideoStore { fun save(ctx: Context, videoId: String, positionSeconds: Int); fun load(ctx: Context): Pair<String, Int>? }` (SharedPreferences)
  - Global JS `window.AutoVideo = { toggle(), seek(seconds), time(): number, paused(): boolean }`; reports state changes to Kotlin via `AutoVideoBridge.onState(playing)` (`addJavascriptInterface`).

- [ ] **Step 1:** `inject.js`: defines `window.AutoVideo`; overrides visibility (`document.hidden=false`, `visibilityState='visible'`, captures and stops `visibilitychange`/`webkitvisibilitychange`); `MutationObserver` + 500 ms interval: if the player has `ad-showing` → `video.muted=true`, `video.currentTime=video.duration`, click `.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button`; disables next-video autoplay (pause on `ended`). `inject.css`: hides everything except the player, video at 100vw×100vh on a black background; hides `ytm-promoted-*`, `ad-slot-renderer`, `#player-ads`, `.ytp-ad-overlay-container`.
- [ ] **Step 2:** `PlayerController`: `WebView(MutableContextWrapper(app))`; JS, DOM storage, `mediaPlaybackRequiresUserGesture=false`; default mobile UA; `WebViewClient.shouldInterceptRequest` → `WebResourceResponse("text/plain","utf-8", empty)` if `AdBlockRules.shouldBlock`, with `Log.d("AutoVideo/AdBlock", url)`; injects CSS+JS in `onPageStarted` and `onPageFinished`; `attach` switches the `MutableContextWrapper` base, removes the WebView from its parent and adds it to `container` full screen; `detach` removes it from its parent without pausing. `reload()` = `load(id, time())`. Saves the position to `VideoStore` every 5 s and on `detach`.
- [ ] **Step 3:** `PlaybackService` (`foregroundServiceType="mediaPlayback"`): creates the `PlayerController` in `onCreate`; `loadVideo` starts the service with `ACTION_LOAD` + `videoId` extra; `startForeground` with a minimal notification when playback starts; wrap `startForegroundService` in a try/catch for `ForegroundServiceStartNotAllowedException` → `startService`. Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`.
- [ ] **Step 4:** `ShareActivity` (translucent theme, no UI): `ACTION_SEND text/plain` and `ACTION_VIEW` filters for `youtube.com`/`youtu.be`; `extractVideoId` → `PlaybackService.loadVideo` + Toast "Opened in AutoVideo" (or "That link isn't a YouTube link" if null, leaving the current video untouched); `finish()`.
- [ ] **Step 5:** `MainActivity`: instructions text + a "Try here" button that `attach`es the player in the activity itself (to test on the phone without a car) and `detach`es it in `onStop`. Requests `POST_NOTIFICATIONS`.
- [ ] **Step 6:** `./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 7: On-phone verification:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`; share a video from Brave → Toast; open AutoVideo → "Try here" → the video plays full screen; blocked requests show up in `adb logcat -s AutoVideo/AdBlock`; share `https://vimeo.com/1` → error Toast. Leave the app → audio keeps playing.
- [ ] **Step 8:** Commit `feat: ad-free WebView player, service and sharing`.

### Task 5: Car screen (Car App Library)

**Files:**
- Create: `app/src/main/java/com/autovideo/app/car/AutoVideoCarAppService.kt`
- Create: `app/src/main/java/com/autovideo/app/car/AutoVideoSession.kt`
- Create: `app/src/main/java/com/autovideo/app/car/PlayerScreen.kt`
- Create: `app/src/main/java/com/autovideo/app/car/SurfaceBridge.kt`
- Create: `app/src/main/res/xml/automotive_app_desc.xml`
- Modify: `AndroidManifest.xml`, `strings.xml`

**Interfaces:**
- Consumes: `PlaybackService.ensureStarted`, `PlaybackService.player`, all of `PlayerController`
- Produces: `class SurfaceBridge(carContext: CarContext, onDisplayReady: (Presentation) -> Unit) : SurfaceCallback` with `fun release()`

- [ ] **Step 1:** Manifest: service with `intent-filter` `androidx.car.app.CarAppService` + category `androidx.car.app.category.NAVIGATION`; permissions `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`; `meta-data` `com.google.android.gms.car.application` → `automotive_app_desc.xml` (`<uses name="template"/>`); `minCarApiLevel` 1. `createHostValidator()` = `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (personal app).
- [ ] **Step 2:** `SurfaceBridge`: `onSurfaceAvailable` → `DisplayManager.createVirtualDisplay("AutoVideo", w, h, dpi, surface, VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)` → `Presentation(carContext, display)` with a black `FrameLayout` → `onDisplayReady`; `onSurfaceDestroyed` → `player.detach()`, `presentation.dismiss()`, `virtualDisplay.release()`; `onClick(x,y)` → `player.togglePlay()`.
- [ ] **Step 3:** `PlayerScreen`: `NavigationTemplate` with an `ActionStrip` of 4 actions (Pause/Resume depending on state, "−10 s", "+10 s", "Reload") calling `PlayerController`; `invalidate()` on `onStateChanged`. No video (`!hasVideo`): the `Presentation` shows a centered white `TextView` "Share a video from your phone"; when a video arrives it is replaced by the WebView.
- [ ] **Step 4:** `AutoVideoSession.onCreateScreen` → `PlaybackService.ensureStarted`, `appManager.setSurfaceCallback(bridge)`, returns `PlayerScreen`. When a video arrives while the car app is open (`loadVideo`), it shows in the car with no further action. If there is no video but `VideoStore.load` has one, it is loaded paused at that position.
- [ ] **Step 5:** `./gradlew assembleDebug` → BUILD SUCCESSFUL; install.
- [ ] **Step 6: DHU verification:** in Android Auto (phone) run "Start head unit server"; `adb forward tcp:5277 tcp:5277`; launch `%ANDROID_HOME%\extras\google\auto\desktop-head-unit.exe`. AutoVideo appears in the launcher; with no video it shows the notice; share from Brave → it plays in the DHU window; buttons and tap work; switch to another DHU app and back twice → the video is still at the same position and visible.
- [ ] **Step 7:** Commit `feat: Android Auto screen`.

### Task 6: Background audio — MediaSession, audio focus, notification

**Files:**
- Modify: `PlaybackService.kt`
- Create: `app/src/main/java/com/autovideo/app/MediaControls.kt`

**Interfaces:**
- Consumes: `PlayerController.togglePlay/seekBy/onStateChanged`
- Produces: `class MediaControls(service: Service, player: PlayerController)` with `fun update(playing: Boolean, title: String?)`, `fun release()`

- [ ] **Step 1:** `MediaSessionCompat` with callbacks `onPlay/onPause` → `togglePlay` when appropriate, `onFastForward/onRewind` → `seekBy(±10)`, `onStop` → pause and `stopSelf`. Title = `document.title` without " - YouTube".
- [ ] **Step 2:** Audio focus (`AudioFocusRequest`, `USAGE_MEDIA`, `CONTENT_TYPE_MOVIE`, `setWillPauseWhenDucked(false)`): `LOSS`/`LOSS_TRANSIENT` → pause; `LOSS_TRANSIENT_CAN_DUCK` → let the system lower the volume; request focus when playing.
- [ ] **Step 3:** `MediaStyle` notification with Pause/Resume and Stop.
- [ ] **Step 4:** Android Auto disconnect: `AutoVideoSession` on `ON_DESTROY` → pause and `stopForeground`/`stopSelf` the service.
- [ ] **Step 5:** `./gradlew assembleDebug`, install. **DHU verification:** play, switch to the DHU music player/Maps → audio keeps playing; open Spotify and start playback → our video pauses; close the DHU → the video stops and the notification disappears.
- [ ] **Step 6:** Commit `feat: background audio with MediaSession`.

### Task 7: Wrap-up — usage README and full test

- [ ] **Step 1:** README: requirements (Unknown sources in AA), how to build/install (`./gradlew installDebug`), how to test with the DHU, where to edit the ad lists.
- [ ] **Step 2:** Full DHU test of every item in Global Constraints.
- [ ] **Step 3:** Commit and push.
