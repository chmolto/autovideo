# App propia de vídeo (YouTube) para Android Auto — Diseño

Fecha: 2026-11-15

## Por qué

El usuario quiere ver vídeos de YouTube en la pantalla del coche (Hyundai Kona 2020) cuando está
aparcado o de acampada. Android Auto no lo permite, y la vía habitual (CarStream instalado con AAAD)
no le inspira confianza: quiere **una app propia**, con código que podamos leer, sin depender de
instaladores ni apps de terceros.

Hoy ve YouTube en el móvil a través de la **web de YouTube en Brave**, con su cuenta iniciada y sin
anuncios gracias al bloqueador de Brave. La app debe mantener esa experiencia "sin anuncios".

## Contexto técnico

- Móvil: Google Pixel 9 (Android 16), conectado al PC por USB.
- Coche: Hyundai Kona 2020, pantalla con Android Auto por cable. El usuario usa un **adaptador
  inalámbrico** (convierte AA por cable en inalámbrico; no es un "AI box").
- PC: Windows 11. No hay Java ni Android Studio ni SDK instalados.
- Uso personal: la app se instala por ADB (sideload), nunca se publica en Play Store.

## Objetivos

1. Reproducir en la pantalla del coche un vídeo de YouTube que el usuario elige en el móvil.
2. Flujo de elección: en Brave (móvil, con su cuenta), el usuario pulsa **Compartir → nuestra app**
   en un vídeo; el vídeo se abre en la pantalla del coche.
3. Control básico **en la pantalla del coche**: tocar para pausar/reanudar y botones de la propia
   app para avanzar/retroceder (ver "Pantalla del coche").
4. Sin anuncios: bloqueo de publicidad propio (peticiones + saltar anuncios dentro del vídeo).
5. Audio por los altavoces del coche (lo enruta Android Auto como cualquier audio multimedia).
6. El móvil puede quedar guardado / con la pantalla apagada mientras se reproduce.

## No-objetivos (primera versión)

- **Sin cuenta de Google en la app.** Se asume y acepta: no se apunta el historial, no se ven vídeos
  con restricción de edad o solo para miembros, recomendaciones genéricas. Posible fase 2.
- Sin películas locales, Netflix u otras plataformas (posible fase posterior).
- Sin búsqueda con teclado en la pantalla del coche (se elige en el móvil).
- Sin publicación en Play Store.

## Anti-objetivos (sería un fracaso aunque "funcione")

- Usar código o librerías de terceros opacos para obtener el vídeo (p. ej. NewPipeExtractor) o
  instaladores tipo AAAD. Solo librerías oficiales de Android/Google (AndroidX).
- Que aparezcan anuncios de forma habitual.
- Que el usuario tenga que manejar el móvil para pausar o avanzar cuando ya está viendo el vídeo.

## Enfoques considerados

En todos, la app se declara ante Android Auto como **app de navegación** (Car App Library de
AndroidX, categoría `NAVIGATION`), porque es el único tipo de app que recibe una superficie libre
(`Surface`) donde dibujar en la pantalla del coche.

- **A) Web de YouTube dentro de la app, dibujada en la superficie del coche** — ELEGIDO.
  La app carga la web móvil de YouTube en un `WebView` que se muestra en la pantalla del coche, con
  bloqueo de anuncios propio. Ventajas: móvil libre, control desde el coche, experiencia diseñada
  para el coche, solo código propio + AndroidX. Coste: el bloqueo de anuncios habrá que retocarlo
  cuando YouTube cambie.
- **B) Espejo de Brave en la pantalla del coche** (captura de pantalla de una sola app con
  MediaProjection). Más sencillo y reutiliza Brave tal cual, pero pide permiso de grabación cada vez,
  el móvil debe estar encendido/desbloqueado/en horizontal, se controla desde el móvil y deja franjas
  negras. Descartado por el usuario.
- **C) Extraer el flujo de vídeo con NewPipeExtractor y reproducirlo con ExoPlayer.** Sin anuncios y
  muy fluido, pero depende de una librería de terceros que se rompe con frecuencia. Descartado
  (choca con el anti-objetivo).

## Diseño

### Piezas

1. **CarAppService (categoría navegación)** — punto de entrada que Android Auto descubre.
   Crea una `Session` y una `Screen` con plantilla de navegación (`NavigationTemplate` o
   `MapWithContentTemplate`, la que encaje con el nivel de API del host).
2. **Puente superficie → WebView.** Al recibir la superficie (`SurfaceCallback.onSurfaceAvailable`),
   se crea un `VirtualDisplay` del tamaño/densidad de esa superficie y sobre él un `Presentation`
   que contiene el `WebView`. Así el `WebView` (y su vídeo) se pinta en la pantalla del coche.
   Al perder la superficie se liberan `VirtualDisplay` y `Presentation`, pero el `WebView` sigue
   vivo (ver punto 9); al volver, se engancha al nuevo display.
3. **WebView de YouTube.** Carga la web móvil (`m.youtube.com/watch?v=…`), JavaScript activado,
   reproducción automática sin gesto (`mediaPlaybackRequiresUserGesture = false`), y el
   reproductor ocupando toda la pantalla del coche (pantalla completa del vídeo por
   `WebChromeClient.onShowCustomView` o CSS inyectado; a elegir por el constructor).
4. **Bloqueo de anuncios** (dos capas):
   - `shouldInterceptRequest`: devolver respuesta vacía para dominios/rutas de publicidad
     (p. ej. `doubleclick.net`, `googlesyndication.com`, `googleadservices.com`,
     `youtube.com/pagead/`, `/api/stats/ads`, `/ptracking`, `/get_midroll_`). Lista en un único
     fichero fácil de editar.
   - JavaScript/CSS inyectado al cargar: ocultar huecos de anuncio de la página y, si el reproductor
     entra en modo anuncio (clase `ad-showing` o equivalente), silenciarlo, saltar al final del
     anuncio y pulsar el botón "Saltar". Los selectores también en un único fichero.
5. **Recepción por "Compartir".** Una `Activity` en el móvil con filtro `ACTION_SEND` (`text/plain`)
   y también `ACTION_VIEW` para enlaces de YouTube. Extrae el ID del vídeo de `youtube.com/watch`,
   `youtu.be/`, `youtube.com/shorts/` y `m.youtube.com`. Guarda el vídeo "pendiente" (estado en
   memoria + persistido) y:
   - si la sesión del coche está activa → lo carga en el coche al instante;
   - si no → lo deja preparado y muestra un aviso ("Abre la app en la pantalla del coche").
   La `Activity` se cierra sola enseguida; no hay interfaz en el móvil más allá de eso.
6. **La pantalla del coche es solo reproductor (decisión del usuario).** Nada de navegar por
   YouTube en el coche: CSS inyectado oculta toda la interfaz de la página excepto el vídeo, que
   ocupa la pantalla entera. Un toque en la superficie (`SurfaceCallback.onClick`) = pausar/reanudar.
   No hace falta reenviar desplazamientos ni toques precisos al `WebView`.
7. **Pantalla del coche: botones de la app** (barra de acciones de la plantilla, máximo ~4):
   Pausa/Reanudar, −10 s, +10 s, y Recargar (vuelve a cargar el vídeo actual en el segundo en que
   iba). Implementados con JavaScript sobre el elemento `<video>`. Sin vídeo cargado, la pantalla
   muestra un aviso: "Comparte un vídeo desde el móvil".
8. **Sin vigilancia de velocidad (decisión del usuario).** La app no lee la velocidad del coche ni
   el GPS y no bloquea el vídeo en marcha: el usuario se responsabiliza de usarla solo aparcado.
   No se piden permisos de ubicación.
9. **Audio de fondo (decisión del usuario).** Si el usuario cambia a otra app en la pantalla del
   coche (Maps, Spotify…) o el host retira la superficie, el vídeo **sigue sonando** como un
   podcast. Implicaciones:
   - El `WebView` no vive atado a la superficie: lo posee un servicio en primer plano
     (foreground service de tipo `mediaPlayback`) y se vuelve a enganchar a un nuevo
     `VirtualDisplay`/`Presentation` cuando la superficie vuelve. Mientras no hay superficie, el
     `WebView` sigue vivo fuera de pantalla (sin destruirse ni pausarse: no llamar a
     `onPause()`/`pauseTimers()`).
   - `MediaSession` para que aparezcan controles de reproducción (pausa/reanudar, ±10 s) en la
     pantalla del coche y en el móvil, y gestión del foco de audio: si otra app toma el audio
     (p. ej. Spotify empieza a sonar), nuestro vídeo se pausa; una indicación breve de navegación
     solo baja el volumen.
   - Notificación del servicio en el móvil con Pausa/Reanudar y Detener.
   - Al desconectar Android Auto por completo, se pausa y se cierra el servicio.
10. **Fin del vídeo.** Cuando el vídeo termina, se para (sin reproducción automática del siguiente).

### Construcción y pruebas

- Kotlin + Gradle (wrapper), sin Android Studio. Instalación mínima en el PC: JDK 17 (winget) y
  Android SDK command-line tools (plataforma, build-tools, platform-tools/ADB). Todo de fuentes
  oficiales.
- Pruebas en el PC con el **Desktop Head Unit (DHU)** de Google (simulador de pantalla de Android
  Auto), conectado al Pixel por USB, antes de probar en el coche.
- Instalación en el móvil con `adb install`. El usuario debe activar **"Fuentes desconocidas"** en los
  ajustes de desarrollador de Android Auto (necesario para cualquier app que no venga de Play Store).
- Para depuración: una forma de ver el log del bloqueo de anuncios (qué se bloqueó) con `adb logcat`.

## Riesgos conocidos

- El adaptador inalámbrico puede limitar la fluidez del vídeo; si va a tirones, probar con cable.
- YouTube cambia su web: el bloqueo de anuncios y los selectores necesitarán mantenimiento.
- Google puede limitar en el futuro las apps de navegación instaladas fuera de Play Store.
- Es posible que algunos toques no lleguen igual de precisos que en el móvil; por eso los botones
  de la barra de acciones cubren lo esencial.

## Decidido por el usuario

- Enfoque A (web de YouTube en la superficie del coche).
- Elegir el vídeo en Brave en el móvil y compartirlo a la app; nada de teclado en el coche.
- Sin cuenta de Google en la primera versión.
- Sin Android Studio: herramientas mínimas en el PC.
- Sin vigilancia de velocidad: el usuario se responsabiliza de usarla aparcado.
- Audio de fondo al cambiar de app en el coche (como un podcast).
- Pantalla del coche = solo reproductor (navegar por YouTube en el coche, quizá más adelante).

## Decisiones propias (corregibles)

- Web móvil de YouTube (`m.youtube.com`) en lugar de la de escritorio: se adapta mejor a pantallas
  pequeñas y pesa menos.
- Botones en la pantalla del coche: Pausa/Reanudar, −10 s, +10 s, Recargar; tocar el vídeo = pausa.
- Al terminar el vídeo, se para (sin autoplay).
- Si otra app toma el audio, nuestro vídeo se pausa; al desconectar Android Auto, se detiene.
- Nombre de la app: "AutoVídeo" (provisional).

## Para el constructor

- Versiones concretas de SDK, AGP, Kotlin y Car App Library.
- Plantilla exacta (`NavigationTemplate` vs `MapWithContentTemplate`) según el nivel de API del host.
- Cómo forzar el reproductor a pantalla completa.
- Lista inicial de dominios y selectores de anuncios.
- Estructura de paquetes y nombre del paquete.
