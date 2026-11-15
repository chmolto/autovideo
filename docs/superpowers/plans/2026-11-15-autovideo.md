# AutoVídeo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android que reproduce en la pantalla de Android Auto el vídeo de YouTube compartido desde el móvil, sin anuncios.

**Architecture:** La app se declara app de navegación (Car App Library) para recibir la `Surface` del coche; sobre ella crea un `VirtualDisplay` + `Presentation` que muestra un `WebView` con `m.youtube.com`. El `WebView` lo posee un servicio en primer plano (`PlaybackService`), de modo que el audio sigue cuando la superficie desaparece; el bloqueo de anuncios es `shouldInterceptRequest` + JS/CSS inyectados.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JDK 17, compileSdk/targetSdk 35, minSdk 29, `androidx.car.app:app` + `app-projected` 1.4.0, `androidx.core:core-ktx` 1.13.1, `androidx.media:media` 1.7.0, JUnit 4.13.2.

**Spec:** `docs/2026-11-15-video-android-auto-design.md`

## Global Constraints

- Solo librerías AndroidX/Google oficiales. Nada de NewPipe, AAAD ni librerías de terceros opacas.
- Sin cuenta de Google, sin permisos de ubicación, sin vigilancia de velocidad.
- Paquete `com.autovideo.app`, nombre visible "AutoVídeo".
- Web cargada: `https://m.youtube.com/watch?v=<id>` (con `&t=<s>s` al reanudar).
- Pantalla del coche = solo reproductor; al terminar el vídeo se para (sin autoplay).
- Botones del coche: Pausa/Reanudar, −10 s, +10 s, Recargar. Tocar el vídeo = pausa/reanudar.
- Sin vídeo cargado, el coche muestra: "Comparte un vídeo desde el móvil".
- Audio de fondo al perder la superficie; se pausa si otra app toma el foco de audio; se baja el volumen en indicaciones breves; se detiene al desconectar Android Auto.
- Listas de bloqueo y selectores en un único fichero cada uno.
- Textos de la interfaz en español.

## Review Focus

1. YouTube pausa el vídeo cuando la página queda oculta (`visibilitychange`) al perder la superficie → el audio debe seguir: `inject.js` fuerza `document.hidden=false` / `visibilityState='visible'` y anula `visibilitychange` (verificado en Task 6, paso DHU "cambiar a otra app").
2. El `WebView` se crea con un contexto y se muestra luego en `Presentation`s distintas → usar `MutableContextWrapper` y cambiar el contexto base al del `Presentation` en cada `attach` (Task 4; verificado en Task 5 conectando/desconectando la superficie dos veces).
3. Enlaces compartidos con texto alrededor ("Mira esto https://youtu.be/xyz?si=…"), Shorts, `m.`/`www.`, `music.youtube.com` → tests en Task 2.
4. Compartir un enlace que no es de YouTube → aviso "Ese enlace no es de YouTube" y no se toca el vídeo actual (Task 2 test + Task 4 manual).
5. Iniciar el servicio en primer plano desde la sesión del coche puede lanzar `ForegroundServiceStartNotAllowedException` → capturarla y continuar como servicio normal; promocionar a primer plano al empezar a reproducir (Task 4).

---

### Task 1: Esqueleto del proyecto que compila

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml` (`app_name` = "AutoVídeo"), `app/src/main/res/drawable/ic_launcher.xml` (vector simple)
- Create: `app/src/main/java/com/autovideo/app/MainActivity.kt` (pantalla vacía con un texto)

- [ ] **Step 1:** Generar wrapper con el Gradle 8.9 descargado: `gradle wrapper --gradle-version 8.9`.
- [ ] **Step 2:** Escribir los ficheros Gradle con las versiones de Tech Stack; `local.properties` con `sdk.dir` (ignorado por git).
- [ ] **Step 3:** Run: `./gradlew assembleDebug` → Expected: `BUILD SUCCESSFUL`, existe `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 4:** Commit `chore: esqueleto Android que compila`.

### Task 2: `VideoLink` — sacar el ID del vídeo de lo compartido

**Files:**
- Create: `app/src/main/java/com/autovideo/app/VideoLink.kt`
- Test: `app/src/test/java/com/autovideo/app/VideoLinkTest.kt`

**Interfaces:**
- Produces: `object VideoLink { fun extractVideoId(text: String): String?; fun watchUrl(id: String, startSeconds: Int = 0): String }`

- [ ] **Step 1: Tests que fallan**

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

- [ ] **Step 2:** `./gradlew testDebugUnitTest` → FAIL (no compila).
- [ ] **Step 3:** Implementar. ID válido = exactamente 11 caracteres `[A-Za-z0-9_-]`. Hosts: `youtube.com`, `www.`, `m.`, `music.`, `youtu.be`. Rutas: `/watch?v=`, `/shorts/`, `/live/`, `/embed/`, `youtu.be/<id>`. Primer enlace válido del texto.
- [ ] **Step 4:** `./gradlew testDebugUnitTest` → PASS.
- [ ] **Step 5:** Commit `feat: extraer ID de enlaces de YouTube`.

### Task 3: `AdBlockRules` — qué peticiones se bloquean

**Files:**
- Create: `app/src/main/java/com/autovideo/app/AdBlockRules.kt` (las listas viven aquí, arriba del todo)
- Test: `app/src/test/java/com/autovideo/app/AdBlockRulesTest.kt`

**Interfaces:**
- Produces: `object AdBlockRules { fun shouldBlock(url: String): Boolean }`

- [ ] **Step 1: Tests que fallan**

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
- [ ] **Step 3:** Implementar: bloqueo por host (igual o subdominio de `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `googletagservices.com`, `imasdk.googleapis.com`) o por ruta en hosts `*.youtube.com` (`/pagead/`, `/api/stats/ads`, `/ptracking`, `/get_midroll_`, `/pcs/activeview`). URL no parseable → `false`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: reglas de bloqueo de anuncios`.

### Task 4: Reproductor (`PlayerController`), JS inyectado, `PlaybackService`, compartir y prueba en el móvil

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
  - `class PlaybackService : Service` con `companion object { val player: PlayerController?; fun loadVideo(ctx: Context, videoId: String); fun ensureStarted(ctx: Context) }`
  - `object VideoStore { fun save(ctx: Context, videoId: String, positionSeconds: Int); fun load(ctx: Context): Pair<String, Int>? }` (SharedPreferences)
  - JS global `window.AutoVideo = { toggle(), seek(seconds), time(): number, paused(): boolean }`; avisa cambios de estado a Kotlin vía `AutoVideoBridge.onState(playing)` (`addJavascriptInterface`).

- [ ] **Step 1:** `inject.js`: define `window.AutoVideo`; anula visibilidad (`document.hidden=false`, `visibilityState='visible'`, captura y detiene `visibilitychange`/`webkitvisibilitychange`); `MutationObserver` + intervalo 500 ms: si el reproductor tiene `ad-showing` → `video.muted=true`, `video.currentTime=video.duration`, clic en `.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button`; desactiva autoplay del siguiente vídeo (pausa en `ended`). `inject.css`: oculta todo salvo el reproductor, vídeo a 100vw×100vh con fondo negro; oculta `ytm-promoted-*`, `ad-slot-renderer`, `#player-ads`, `.ytp-ad-overlay-container`.
- [ ] **Step 2:** `PlayerController`: `WebView(MutableContextWrapper(app))`; JS, DOM storage, `mediaPlaybackRequiresUserGesture=false`; UA móvil por defecto; `WebViewClient.shouldInterceptRequest` → `WebResourceResponse("text/plain","utf-8", vacío)` si `AdBlockRules.shouldBlock`, con `Log.d("AutoVideo/AdBlock", url)`; inyecta CSS+JS en `onPageStarted` y `onPageFinished`; `attach` cambia la base del `MutableContextWrapper`, quita el WebView de su padre y lo añade a `container` a pantalla completa; `detach` lo quita del padre sin pausar. `reload()` = `load(id, time())`. Cada 5 s y en `detach` guarda posición en `VideoStore`.
- [ ] **Step 3:** `PlaybackService` (`foregroundServiceType="mediaPlayback"`): crea el `PlayerController` en `onCreate`; `loadVideo` arranca el servicio con `ACTION_LOAD` + extra `videoId`; `startForeground` con notificación mínima al empezar a reproducir; envolver `startForegroundService` en try/catch `ForegroundServiceStartNotAllowedException` → `startService`. Permisos: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`.
- [ ] **Step 4:** `ShareActivity` (tema translúcido, sin interfaz): filtros `ACTION_SEND text/plain` y `ACTION_VIEW` para `youtube.com`/`youtu.be`; `extractVideoId` → `PlaybackService.loadVideo` + Toast "Abierto en AutoVídeo" (o "Ese enlace no es de YouTube" si null, sin tocar el vídeo actual); `finish()`.
- [ ] **Step 5:** `MainActivity`: texto de instrucciones + botón "Probar aquí" que hace `attach` del reproductor en la propia actividad (para probar en el móvil sin coche) y `detach` en `onStop`. Pide `POST_NOTIFICATIONS`.
- [ ] **Step 6:** `./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 7: Verificación en el móvil:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`; compartir un vídeo desde Brave → Toast; abrir AutoVídeo → "Probar aquí" → el vídeo se reproduce a pantalla completa; en `adb logcat -s AutoVideo/AdBlock` aparecen peticiones bloqueadas; compartir `https://vimeo.com/1` → Toast de error. Salir de la app → el audio sigue.
- [ ] **Step 8:** Commit `feat: reproductor WebView sin anuncios, servicio y compartir`.

### Task 5: Pantalla del coche (Car App Library)

**Files:**
- Create: `app/src/main/java/com/autovideo/app/car/AutoVideoCarAppService.kt`
- Create: `app/src/main/java/com/autovideo/app/car/AutoVideoSession.kt`
- Create: `app/src/main/java/com/autovideo/app/car/PlayerScreen.kt`
- Create: `app/src/main/java/com/autovideo/app/car/SurfaceBridge.kt`
- Create: `app/src/main/res/xml/automotive_app_desc.xml`
- Modify: `AndroidManifest.xml`, `strings.xml`

**Interfaces:**
- Consumes: `PlaybackService.ensureStarted`, `PlaybackService.player`, todo `PlayerController`
- Produces: `class SurfaceBridge(carContext: CarContext, onDisplayReady: (Presentation) -> Unit) : SurfaceCallback` con `fun release()`

- [ ] **Step 1:** Manifest: servicio con `intent-filter` `androidx.car.app.CarAppService` + categoría `androidx.car.app.category.NAVIGATION`; permisos `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`; `meta-data` `com.google.android.gms.car.application` → `automotive_app_desc.xml` (`<uses name="template"/>`); `minCarApiLevel` 1. `createHostValidator()` = `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (app personal).
- [ ] **Step 2:** `SurfaceBridge`: `onSurfaceAvailable` → `DisplayManager.createVirtualDisplay("AutoVideo", w, h, dpi, surface, VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)` → `Presentation(carContext, display)` con un `FrameLayout` negro → `onDisplayReady`; `onSurfaceDestroyed` → `player.detach()`, `presentation.dismiss()`, `virtualDisplay.release()`; `onClick(x,y)` → `player.togglePlay()`.
- [ ] **Step 3:** `PlayerScreen`: `NavigationTemplate` con `ActionStrip` de 4 acciones (Pausa/Reanudar según estado, "−10 s", "+10 s", "Recargar") llamando a `PlayerController`; `invalidate()` en `onStateChanged`. Sin vídeo (`!hasVideo`): el `Presentation` muestra un `TextView` blanco centrado "Comparte un vídeo desde el móvil"; al llegar un vídeo se sustituye por el WebView.
- [ ] **Step 4:** `AutoVideoSession.onCreateScreen` → `PlaybackService.ensureStarted`, `appManager.setSurfaceCallback(bridge)`, devuelve `PlayerScreen`. Al llegar un vídeo con el coche abierto (`loadVideo`), se muestra en el coche sin más acciones. Si no hay vídeo pero `VideoStore.load` tiene uno, se carga en pausa en esa posición.
- [ ] **Step 5:** `./gradlew assembleDebug` → BUILD SUCCESSFUL; instalar.
- [ ] **Step 6: Verificación DHU:** en Android Auto (móvil) iniciar "servidor de unidad principal"; `adb forward tcp:5277 tcp:5277`; lanzar `%ANDROID_HOME%\extras\google\auto\desktop-head-unit.exe`. AutoVídeo aparece en el lanzador; sin vídeo muestra el aviso; compartir desde Brave → se reproduce en la ventana DHU; botones y toque funcionan; cambiar a otra app del DHU y volver dos veces → el vídeo sigue en la misma posición y se ve.
- [ ] **Step 7:** Commit `feat: pantalla de Android Auto`.

### Task 6: Audio de fondo — MediaSession, foco de audio, notificación

**Files:**
- Modify: `PlaybackService.kt`
- Create: `app/src/main/java/com/autovideo/app/MediaControls.kt`

**Interfaces:**
- Consumes: `PlayerController.togglePlay/seekBy/onStateChanged`
- Produces: `class MediaControls(service: Service, player: PlayerController)` con `fun update(playing: Boolean, title: String?)`, `fun release()`

- [ ] **Step 1:** `MediaSessionCompat` con callbacks `onPlay/onPause` → `togglePlay` si procede, `onFastForward/onRewind` → `seekBy(±10)`, `onStop` → pausar y `stopSelf`. Título = `document.title` sin " - YouTube".
- [ ] **Step 2:** Foco de audio (`AudioFocusRequest`, `USAGE_MEDIA`, `CONTENT_TYPE_MOVIE`, `setWillPauseWhenDucked(false)`): `LOSS`/`LOSS_TRANSIENT` → pausar; `LOSS_TRANSIENT_CAN_DUCK` → dejar que el sistema baje volumen; pedir foco al reproducir.
- [ ] **Step 3:** Notificación `MediaStyle` con Pausa/Reanudar y Detener.
- [ ] **Step 4:** Desconexión de Android Auto: `AutoVideoSession` en `ON_DESTROY` → pausar y `stopForeground`/`stopSelf` del servicio.
- [ ] **Step 5:** `./gradlew assembleDebug`, instalar. **Verificación DHU:** reproducir, cambiar al reproductor de música/Maps del DHU → el audio sigue; abrir Spotify y ponerlo a sonar → nuestro vídeo se pausa; cerrar el DHU → el vídeo se detiene y desaparece la notificación.
- [ ] **Step 6:** Commit `feat: audio de fondo con MediaSession`.

### Task 7: Cierre — README de uso y prueba completa

- [ ] **Step 1:** README: requisitos (Fuentes desconocidas en AA), cómo compilar/instalar (`./gradlew installDebug`), cómo probar con DHU, dónde tocar las listas de anuncios.
- [ ] **Step 2:** Prueba completa en DHU de todos los puntos de Global Constraints.
- [ ] **Step 3:** Commit y push.
