// AutoVídeo: se inyecta en la web de YouTube antes de que cargue la página.
// - Deja solo el reproductor a pantalla completa (CSS).
// - Salta los anuncios dentro del vídeo.
// - Evita que YouTube pause el vídeo cuando la página "no se ve" (audio de fondo).
// - Expone window.AutoVideo para que la app controle el vídeo.
(function () {
  if (window.__autoVideoInstalled) return;
  window.__autoVideoInstalled = true;

  var bridge = window.AutoVideoBridge;
  // Modo de prueba (solo debug): volumen ínfimo (−80 dB), inaudible pero distinto de 0,
  // porque Chromium trata el volumen 0 como "sin sonido" y lo pausa en segundo plano.
  var TEST_VOLUME = 0.0001;

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

  // Si el usuario pausa, no volver a arrancar el vídeo por nuestra cuenta.
  var userPaused = false;
  var autoplayUntil = 0;

  window.AutoVideo = {
    toggle: function () {
      var v = video(); if (!v) return;
      if (v.paused) { userPaused = false; v.play(); } else { userPaused = true; v.pause(); }
    },
    play: function () { var v = video(); userPaused = false; if (v) v.play(); },
    pause: function () { var v = video(); userPaused = true; if (v) v.pause(); },
    // Reanudar tras mover el reproductor de pantalla, salvo que el usuario lo haya pausado.
    resumeAfterMove: function () { var v = video(); if (v && v.paused && !v.ended && !userPaused) v.play(); },
    seek: function (s) {
      var v = video();
      if (!v || adShowing()) return;
      var t = v.currentTime + s;
      if (isFinite(v.duration)) t = Math.min(t, v.duration - 1);
      v.currentTime = Math.max(0, t);
    },
    time: function () { var v = video(); return v && !adShowing() ? Math.floor(v.currentTime) : -1; },
    paused: function () { var v = video(); return !v || v.paused; }
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
    if (silent) { if (mp && mp.setVolume) mp.setVolume(0); v.volume = TEST_VOLUME; }
    if (v.muted || v.__autoVideoMuted) {
      v.__autoVideoMuted = false;
      if (mp && mp.unMute) mp.unMute();
      if (silent) v.volume = TEST_VOLUME;
      v.muted = false;
    }

    if (!bridge) return;
    var wanted = bridge.videoId();
    var current = new URLSearchParams(location.search).get('v');

    // YouTube ha saltado a otro vídeo por su cuenta (reproducción automática): parar.
    if (wanted && current && current !== wanted) {
      if (!v.paused) v.pause();
      return;
    }
    // Primer arranque del vídeo pedido. YouTube a veces lo pausa al inicializarse:
    // durante 15 s se insiste mientras el usuario no lo haya pausado.
    if (wanted && startedFor !== wanted && v.readyState >= 1) {
      startedFor = wanted;
      userPaused = !bridge.autoplay();
      autoplayUntil = userPaused ? 0 : Date.now() + 15000;
      if (userPaused) v.pause();
    }
    if (v.paused && !v.ended && !userPaused && Date.now() < autoplayUntil) {
      var pr = v.play();
      if (pr && pr.catch) pr.catch(function (e) { console.log('[AutoVideo] play() rechazado: ' + e); });
    }
    var title = document.title.replace(/ - YouTube$/, '');
    if (title && title !== lastTitle) { lastTitle = title; bridge.onTitle(title); }
  }, 300);
})();
