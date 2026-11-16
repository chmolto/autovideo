# Custom video (YouTube) app for Android Auto — Design

Date: 2026-11-15

## Why

The user wants to watch YouTube videos on the car screen (Hyundai Kona 2020) while parked or
camping. Android Auto doesn't allow it, and the usual route (CarStream installed via AAAD) doesn't
feel trustworthy to them: they want **their own app**, with code we can read, without relying on
third-party installers or apps.

Today they watch YouTube on the phone through the **YouTube website in Brave**, signed in to their
account and ad-free thanks to Brave's blocker. The app must keep that "no ads" experience.

## Technical context

- Phone: Google Pixel 9 (Android 16), connected to the PC over USB.
- Car: Hyundai Kona 2020, wired Android Auto screen. The user uses a **wireless adapter**
  (turns wired AA into wireless; it's not an "AI box").
- PC: Windows 11. No Java, Android Studio or SDK installed.
- Personal use: the app is installed over ADB (sideloaded) and never published on the Play Store.

## Goals

1. Play a YouTube video, chosen by the user on the phone, on the car screen.
2. Selection flow: in Brave (phone, signed in), the user taps **Share → our app** on a video; the
   video opens on the car screen.
3. Basic control **on the car screen**: tap to pause/resume, plus the app's own buttons to seek
   forward/back (see "Car screen").
4. No ads: custom ad blocking (requests + skipping in-video ads).
5. Audio through the car speakers (Android Auto routes it like any other media audio).
6. The phone can be put away / have its screen off during playback.

## Non-goals (first version)

- **No Google account in the app.** Assumed and accepted: watch history isn't recorded, age-restricted
  and members-only videos can't be watched, recommendations are generic. Possible phase 2.
- No local movies, Netflix or other platforms (possible later phase).
- No keyboard search on the car screen (videos are chosen on the phone).
- No Play Store release.

## Anti-goals (would count as failure even if it "works")

- Using opaque third-party code or libraries to obtain the video (e.g. NewPipeExtractor) or
  AAAD-style installers. Only official Android/Google libraries (AndroidX).
- Ads showing up regularly.
- The user having to handle the phone to pause or seek once the video is playing.

## Approaches considered

In all of them, the app registers with Android Auto as a **navigation app** (AndroidX Car App
Library, `NAVIGATION` category), because that is the only app type that gets a free-form surface
(`Surface`) to draw on the car screen.

- **A) YouTube website inside the app, drawn on the car surface** — CHOSEN.
  The app loads the mobile YouTube site in a `WebView` shown on the car screen, with its own ad
  blocking. Pros: phone stays free, control from the car, experience designed for the car, only
  our own code + AndroidX. Cost: the ad blocking will need tweaking whenever YouTube changes.
- **B) Mirror Brave onto the car screen** (single-app screen capture with MediaProjection).
  Simpler and reuses Brave as is, but asks for recording permission every time, the phone must be
  on/unlocked/in landscape, it's controlled from the phone and leaves black bars. Rejected by the user.
- **C) Extract the video stream with NewPipeExtractor and play it with ExoPlayer.** Ad-free and
  very smooth, but depends on a third-party library that breaks often. Rejected
  (conflicts with the anti-goal).

## Design

### Components

1. **CarAppService (navigation category)** — the entry point Android Auto discovers.
   Creates a `Session` and a `Screen` with a navigation template (`NavigationTemplate` or
   `MapWithContentTemplate`, whichever fits the host's API level).
2. **Surface → WebView bridge.** When the surface arrives (`SurfaceCallback.onSurfaceAvailable`),
   a `VirtualDisplay` matching the surface's size/density is created, and on it a `Presentation`
   containing the `WebView`. That way the `WebView` (and its video) is drawn on the car screen.
   When the surface is lost, the `VirtualDisplay` and `Presentation` are released, but the `WebView`
   stays alive (see item 9); when the surface returns, it is attached to the new display.
3. **YouTube WebView.** Loads the mobile site (`m.youtube.com/watch?v=…`), JavaScript enabled,
   autoplay without a user gesture (`mediaPlaybackRequiresUserGesture = false`), and the player
   filling the whole car screen (video fullscreen via `WebChromeClient.onShowCustomView` or
   injected CSS; the builder's choice).
4. **Ad blocking** (two layers):
   - `shouldInterceptRequest`: return an empty response for ad domains/paths
     (e.g. `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`,
     `youtube.com/pagead/`, `/api/stats/ads`, `/ptracking`, `/get_midroll_`). The list lives in a
     single, easy-to-edit file.
   - JavaScript/CSS injected on load: hide ad slots on the page and, if the player enters ad mode
     (`ad-showing` class or equivalent), mute it, seek to the end of the ad and click the "Skip"
     button. The selectors also live in a single file.
5. **Receiving via "Share".** An `Activity` on the phone with an `ACTION_SEND` (`text/plain`)
   filter and also `ACTION_VIEW` for YouTube links. Extracts the video ID from `youtube.com/watch`,
   `youtu.be/`, `youtube.com/shorts/` and `m.youtube.com`. Stores the "pending" video (in-memory
   state + persisted) and:
   - if the car session is active → loads it in the car immediately;
   - if not → keeps it ready and shows a notice ("Ready: open AutoVideo on the car screen").
   The `Activity` closes itself right away; there is no phone UI beyond that.
6. **The car screen is a player only (user's decision).** No browsing YouTube in the car: injected
   CSS hides the whole page UI except the video, which fills the entire screen. A tap on the surface
   (`SurfaceCallback.onClick`) = pause/resume. No need to forward scrolls or precise taps to the
   `WebView`.
7. **Car screen: app buttons** (template action strip, ~4 max):
   Pause/Resume, −10 s, +10 s, and Reload (reloads the current video at the second it was at).
   Implemented with JavaScript on the `<video>` element. With no video loaded, the screen shows a
   notice: "Share a video from your phone".
8. **No speed monitoring (user's decision).** The app doesn't read the car's speed or GPS and doesn't
   block the video while driving: the user takes responsibility for using it only while parked.
   No location permissions are requested.
9. **Background audio (user's decision).** If the user switches to another app on the car screen
   (Maps, Spotify…) or the host removes the surface, the video **keeps playing** like a podcast.
   Implications:
   - The `WebView` isn't tied to the surface: it is owned by a foreground service (of type
     `mediaPlayback`) and is re-attached to a new `VirtualDisplay`/`Presentation` when the surface
     returns. While there is no surface, the `WebView` stays alive off-screen (not destroyed or
     paused: don't call `onPause()`/`pauseTimers()`).
   - `MediaSession` so playback controls (pause/resume, ±10 s) appear on the car screen and on the
     phone, plus audio focus handling: if another app takes the audio (e.g. Spotify starts
     playing), our video pauses; a short navigation prompt only lowers the volume.
   - Service notification on the phone with Pause/Resume and Stop.
   - When Android Auto disconnects completely, playback pauses and the service shuts down.
10. **End of video.** When the video ends, it stops (no autoplay of the next one).

### Build and testing

- Kotlin + Gradle (wrapper), no Android Studio. Minimal setup on the PC: JDK 17 (winget) and the
  Android SDK command-line tools (platform, build-tools, platform-tools/ADB). All from official
  sources.
- Testing on the PC with Google's **Desktop Head Unit (DHU)** (Android Auto screen simulator),
  connected to the Pixel over USB, before testing in the car.
- Installation on the phone with `adb install`. The user must enable **"Unknown sources"** in
  Android Auto's developer settings (required for any app that doesn't come from the Play Store).
- For debugging: a way to see the ad-blocking log (what was blocked) with `adb logcat`.

## Known risks

- The wireless adapter may limit video smoothness; if it stutters, try a cable.
- YouTube changes its site: the ad blocking and selectors will need maintenance.
- Google may in the future restrict navigation apps installed outside the Play Store.
- Some taps may not arrive as precisely as on the phone; that's why the action-strip buttons
  cover the essentials.

## Decided by the user

- Approach A (YouTube website on the car surface).
- Pick the video in Brave on the phone and share it to the app; no keyboard in the car.
- No Google account in the first version.
- No Android Studio: minimal tooling on the PC.
- No speed monitoring: the user takes responsibility for using it while parked.
- Background audio when switching apps in the car (like a podcast).
- Car screen = player only (browsing YouTube in the car, maybe later).

## Our own decisions (open to change)

- Mobile YouTube site (`m.youtube.com`) instead of desktop: it adapts better to small screens and
  is lighter.
- Buttons on the car screen: Pause/Resume, −10 s, +10 s, Reload; tapping the video = pause.
- When the video ends, it stops (no autoplay).
- If another app takes the audio, our video pauses; when Android Auto disconnects, it stops.
- App name: "AutoVideo" (provisional).

## Left to the builder

- Exact versions of the SDK, AGP, Kotlin and the Car App Library.
- Exact template (`NavigationTemplate` vs `MapWithContentTemplate`) depending on the host's API level.
- How to force the player into fullscreen.
- Initial list of ad domains and selectors.
- Package structure and package name.
