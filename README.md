# AutoVídeo

App Android personal para ver vídeos de YouTube en la pantalla de Android Auto **con el coche aparcado**.

- Eliges el vídeo en el móvil (Brave → Compartir → Más → **AutoVídeo**) y se reproduce en la pantalla del coche.
- Solo el reproductor, a pantalla completa, sin anuncios (bloqueo propio, sin librerías de terceros).
- Botones en el coche: pausa, −10 s, +10 s, recargar. Tocar el vídeo = pausa/reanudar.
- El audio sigue si cambias a otra app en el coche (Maps, etc.).

Diseño: [docs/2026-11-15-video-android-auto-design.md](docs/2026-11-15-video-android-auto-design.md) ·
Plan: [docs/superpowers/plans/2026-11-15-autovideo.md](docs/superpowers/plans/2026-11-15-autovideo.md)

> Úsala solo con el coche parado.

## Compilar

Requisitos: JDK 17 y Android SDK (platform 36, build-tools). Sin Android Studio.

```bash
./gradlew testDebugUnitTest      # tests
./gradlew assembleRelease        # APK firmado: app/build/outputs/apk/release/app-release.apk
```

La firma de release usa la clave de `~/.autovideo/keystore.properties` (fuera del repo).
Si no existe, el release sale sin firmar.

## Instalar para que Android Auto la muestre en el coche

Android Auto solo muestra apps hechas con la Car App Library si Android cree que vienen de
Google Play (instalador **e** iniciador de la instalación). `adb install` no sirve: deja al
PC (`com.android.shell`) como iniciador y la app no aparece en el coche, aunque sí en el
simulador (DHU). «Fuentes desconocidas» no basta para este tipo de apps.

Se instala con **UnknownAuto** (código abierto, revisado y compilado desde su fuente) + **Shizuku** (Play Store):

1. Copiar el APK al móvil: `adb push app/build/outputs/apk/release/app-release.apk /sdcard/Download/AutoVideo.apk`
2. Arrancar Shizuku por ADB:
   ```bash
   adb shell "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://;s/base.apk//')lib/arm64/libshizuku.so"
   ```
3. En el móvil: UnknownAuto → `Descargas/AutoVideo.apk` → Instalar.
4. Comprobar: `adb shell dumpsys package io.github.chmolto.autovideo | grep -E "installer|initiating"` debe mostrar
   `installerPackageName=com.android.vending` e `initiatingPackageName=com.google.android.packageinstaller`.
5. En Android Auto → Ajustes → *Personalizar menú de apps* debe aparecer AutoVídeo.

Repetir en cada versión nueva (subir `versionCode`).

En Android Auto → Ajustes para desarrolladores también conviene tener **Fuentes desconocidas** activado.

## Probar en el PC con el simulador (DHU)

1. En Android Auto (móvil): ⋮ → *Iniciar servidor de la unidad principal*.
2. `adb forward tcp:5277 tcp:5277`
3. `%ANDROID_HOME%\extras\google\auto\desktop-head-unit.exe`

En la versión **debug**, compartir con `--ez autovideo.test_muted true` reproduce en silencio (volumen −80 dB):

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://youtu.be/aqz-KE-bpKQ" --ez autovideo.test_muted true \
  -n com.autovideo.app/.ShareActivity
```

Registro: `adb logcat | grep -E "AutoVideo|\[AutoVideo\]"` (bloqueos de anuncios en `AutoVideo/AdBlock`).

## Bloqueo de anuncios

- Peticiones bloqueadas: `app/src/main/java/com/autovideo/app/AdBlockRules.kt`
- Ocultar elementos / saltar anuncios: `app/src/main/assets/inject.css` y `inject.js`

Si YouTube cambia su web y vuelven a salir anuncios, es ahí donde hay que tocar.

## Lecciones aprendidas

- YouTube móvil arranca los vídeos **silenciados**: el script quita el silencio.
- El WebView ya gestiona el **foco de audio** (Chromium); pedirlo también desde la app hacía que se pausara solo.
- Al mover el WebView entre pantallas Chromium lo pausa un instante: se reanuda tras moverlo.
- Sin pantalla del coche, el WebView se aparca en una pantalla virtual invisible para que Chromium no lo pause.
