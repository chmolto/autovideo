// AutoVídeo: se inyecta en la web de YouTube antes de que cargue la página.
// - Deja solo el reproductor a pantalla completa (CSS).
// - Salta los anuncios dentro del vídeo.
// - Evita que YouTube pause el vídeo cuando la página "no se ve" (audio de fondo).
// - Expone window.AutoVideo para que la app controle el vídeo.
(function () {
  if (window.__autoVideoInstalled) return;
  window.__autoVideoInstalled = true;

  var bridge = window.AutoVideoBridge;

  // --- CSS (lo rellena la app con inject.css) ---
  var css = __AUTOVIDEO_CSS__;
  function addStyle() {
    if (document.getElementById('autovideo-style')) return;
    var s = document.createElement('style');
    s.id = 'autovideo-style';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }
  addStyle();
  document.addEventListener('DOMContentLoaded', addStyle);

  // --- La página siempre está "visible" ---
  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'webkitHidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
    Object.defineProperty(document, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
    document.hasFocus = function () { return true; };
  } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'blur', 'freeze'].forEach(function (t) {
    window.addEventListener(t, function (e) { e.stopImmediatePropagation(); }, true);
    document.addEventListener(t, function (e) { e.stopImmediatePropagation(); }, true);
  });

  // --- Que YouTube nunca crea estar en "imagen en imagen" ---
  // YouTube compara el área de la ventana (outerWidth×outerHeight) con la mayor área de
  // pantalla/ventana que ha visto; si la ventana es mucho menor, cree que está en PiP y pausa
  // el vídeo en cada actualización de progreso. Como el reproductor pasa por pantallas de
  // distinto tamaño (móvil, coche, pantalla invisible), se le da siempre el mismo tamaño
  // fijo. La maquetación de la página usa innerWidth/CSS, que no se tocan.
  try {
    var FIXED = 4096;
    var fixed = { get: function () { return FIXED; }, configurable: true };
    ['width', 'height', 'availWidth', 'availHeight'].forEach(function (k) {
      Object.defineProperty(Screen.prototype, k, fixed);
    });
    Object.defineProperty(window, 'outerWidth', fixed);
    Object.defineProperty(window, 'outerHeight', fixed);
  } catch (e) {}

  // Diagnóstico (debug): quién llama a pause() desde JavaScript.
  if (bridge && bridge.isDebug()) {
    Error.stackTraceLimit = 40;
    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function () {
      var frames = (new Error().stack || '').split(/\n/).slice(2, 40).map(function (l) {
        return l.trim().replace(/^at /, '').replace(/https:[^ )]*base\.js/, 'yt');
      });
      console.log('[AutoVideo] pause() llamado desde JS: ' + frames.join(' | '));
      return origPause.apply(this, arguments);
    };
  }

  // --- Control del vídeo ---
  function video() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }
  function player() {
    return document.querySelector('.html5-video-player');
  }
  function adShowing() {
    var p = player();
    return !!p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'));
  }

  // El reproductor de YouTube (#movie_player) con su API interna. Hay que controlar el vídeo
  // A TRAVÉS de ella: si se toca el <video> directamente, YouTube cree que sigue en pausa
  // y lo vuelve a pausar (pauseVideo) cada vez que arranca.
  function yt() {
    var mp = document.getElementById('movie_player');
    return mp && typeof mp.playVideo === 'function' ? mp : null;
  }
  function doPlay() {
    var p = yt();
    if (p) { p.playVideo(); return; }
    var v = video();
    if (v) { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); }
  }
  function doPause() {
    var p = yt();
    if (p) { p.pauseVideo(); return; }
    var v = video();
    if (v) v.pause();
  }
  function duration() {
    var p = yt(), v = video();
    var d = p && p.getDuration ? p.getDuration() : (v ? v.duration : 0);
    return isFinite(d) && d > 0 ? d : 0;
  }
  function seekTo(t) {
    if (adShowing()) return;
    var d = duration();
    if (d) t = Math.min(t, d - 1);
    t = Math.max(0, t);
    var p = yt();
    if (p && p.seekTo) p.seekTo(t, true);
    else { var v = video(); if (v) v.currentTime = t; }
  }

  // Si el usuario pausa, no volver a arrancar el vídeo por nuestra cuenta.
  var userPaused = false;
  var autoplayUntil = 0;

  window.AutoVideo = {
    toggle: function () {
      var v = video(); if (!v) return;
      if (v.paused) { userPaused = false; doPlay(); } else { userPaused = true; doPause(); }
    },
    play: function () { userPaused = false; doPlay(); },
    pause: function () { userPaused = true; doPause(); },
    // Reanudar tras mover el reproductor de pantalla, salvo que el usuario lo haya pausado.
    resumeAfterMove: function () { var v = video(); if (v && v.paused && !v.ended && !userPaused) doPlay(); },
    seek: function (s) { var v = video(); if (v) seekTo(v.currentTime + s); },
    seekTo: function (t) { seekTo(t); },
    time: function () { var v = video(); return v && !adShowing() ? Math.floor(v.currentTime) : -1; },
    paused: function () { var v = video(); return !v || v.paused; },
    // Para la barra de progreso: segundos actuales, duración, en pausa, anuncio.
    status: function () {
      var v = video();
      if (!v) return null;
      var ad = adShowing();
      return { t: ad ? 0 : v.currentTime, d: ad ? 0 : duration(), p: v.paused || v.ended, ad: ad };
    }
  };

  // Avisar a la app de play/pausa (los eventos de <video> no burbujean: se escuchan en captura).
  function report() {
    if (!bridge) return;
    var v = video();
    bridge.onState(!!v && !v.paused && !v.ended);
  }
  document.addEventListener('play', report, true);
  document.addEventListener('playing', report, true);
  document.addEventListener('pause', report, true);
  document.addEventListener('pause', function () {
    if (bridge && bridge.isDebug()) console.log('[AutoVideo] pausa; userPaused=' + userPaused + ' ' + new Error().stack);
  }, true);
  document.addEventListener('ended', report, true);
  // Modo de prueba: silenciar en el mismo instante en que el vídeo intenta sonar.
  ['play', 'playing', 'volumechange', 'loadedmetadata'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      if (bridge && bridge.testMuted() && e.target && e.target.muted === false) e.target.muted = true;
    }, true);
  });

  // --- Bucle: anuncios, arranque automático, título, no pasar al siguiente vídeo ---
  var startedFor = null;
  var debugTick = 0;
  var rafCount = 0;
  (function raf() { rafCount++; requestAnimationFrame(raf); })();
  var lastTitle = null;
  setInterval(function () {
    var v = video();
    if (bridge && bridge.isDebug() && ++debugTick % 7 === 0) {
      console.log('[AutoVideo] video=' + !!v + (v ? ' ready=' + v.readyState + ' paused=' + v.paused +
        ' t=' + v.currentTime.toFixed(1) + ' muted=' + v.muted + ' vol=' + v.volume : '') + ' ad=' + adShowing() +
        ' raf=' + rafCount + ' started=' + startedFor + ' url=' + location.href);
    }
    if (!v) return;

    if (adShowing()) {
      if (!v.__autoVideoMuted) { v.__autoVideoMuted = true; v.muted = true; }
      if (isFinite(v.duration) && v.duration > 0) v.currentTime = Math.max(v.duration - 0.1, 0);
      document.querySelectorAll(
        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button'
      ).forEach(function (b) { b.click(); });
      return;
    }
    // YouTube móvil arranca los vídeos silenciados ("toca para activar el sonido"):
    // quitar el silencio, que además evita que Chromium lo pause al dejar de verse.
    var mp = document.getElementById('movie_player');
    var silent = bridge && bridge.testMuted();
    // OJO: no cambiar el volumen con el vídeo en marcha más de lo imprescindible: YouTube
    // reacciona a los cambios de volumen hechos por código pausando el vídeo.
    if (silent) {
      // Modo de prueba: silenciado SIEMPRE, y a través de YouTube (mute) para que su estado
      // coincida con el del vídeo; si no, YouTube lo pausa al reanudar.
      if (mp && mp.isMuted && !mp.isMuted()) mp.mute();
      if (!v.muted) v.muted = true;
    } else if (v.muted || v.__autoVideoMuted) {
      v.__autoVideoMuted = false;
      if (mp && mp.unMute) mp.unMute();
      v.muted = false;
    }

    if (!bridge) return;
    var wanted = bridge.videoId();
    var current = new URLSearchParams(location.search).get('v');

    // YouTube ha saltado a otro vídeo por su cuenta (reproducción automática): parar.
    if (wanted && current && current !== wanted) {
      if (!v.paused) doPause();
      return;
    }
    // Primer arranque del vídeo pedido. YouTube a veces lo pausa al inicializarse:
    // durante 15 s se insiste mientras el usuario no lo haya pausado.
    if (wanted && startedFor !== wanted && v.readyState >= 1) {
      startedFor = wanted;
      userPaused = !bridge.autoplay();
      autoplayUntil = userPaused ? 0 : Date.now() + 15000;
      if (userPaused) doPause();
    }
    if (v.paused && !v.ended && !userPaused && Date.now() < autoplayUntil) doPlay();
    var title = document.title.replace(/ - YouTube$/, '');
    if (title && title !== lastTitle) { lastTitle = title; bridge.onTitle(title); }
  }, 300);
})();
