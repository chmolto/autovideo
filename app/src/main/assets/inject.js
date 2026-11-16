// AutoVideo: injected into the YouTube site before the page loads.
// - Leaves only the player, full screen (CSS).
// - Skips in-video ads.
// - Stops YouTube from pausing the video when the page is "not visible" (background audio).
// - Exposes window.AutoVideo so the app can control the video.
(function () {
  if (window.__autoVideoInstalled) return;
  window.__autoVideoInstalled = true;

  var bridge = window.AutoVideoBridge;

  // --- CSS (filled in by the app from inject.css) ---
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

  // --- The page is always "visible" ---
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

  // --- YouTube must never think it is in picture-in-picture ---
  // YouTube compares the window area (outerWidth×outerHeight) with the largest screen/window
  // area it has seen; if the window is much smaller, it assumes PiP and pauses the video
  // on every progress update. Since the player moves between displays of different sizes
  // (phone, car, invisible display), it is always given the same fixed size.
  // Page layout uses innerWidth/CSS, which are left untouched.
  try {
    var FIXED = 4096;
    var fixed = { get: function () { return FIXED; }, configurable: true };
    ['width', 'height', 'availWidth', 'availHeight'].forEach(function (k) {
      Object.defineProperty(Screen.prototype, k, fixed);
    });
    Object.defineProperty(window, 'outerWidth', fixed);
    Object.defineProperty(window, 'outerHeight', fixed);
  } catch (e) {}

  // Diagnostics (debug): who calls pause() from JavaScript.
  if (bridge && bridge.isDebug()) {
    Error.stackTraceLimit = 40;
    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function () {
      var frames = (new Error().stack || '').split(/\n/).slice(2, 40).map(function (l) {
        return l.trim().replace(/^at /, '').replace(/https:[^ )]*base\.js/, 'yt');
      });
      console.log('[AutoVideo] pause() called from JS: ' + frames.join(' | '));
      return origPause.apply(this, arguments);
    };
  }

  // --- Video control ---
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

  // YouTube's player (#movie_player) and its internal API. The video must be controlled
  // THROUGH it: if the <video> is touched directly, YouTube thinks it is still paused
  // and pauses it again (pauseVideo) every time it starts.
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

  // If the user pauses, don't restart the video on our own.
  var userPaused = false;
  var autoplayUntil = 0;

  window.AutoVideo = {
    toggle: function () {
      var v = video(); if (!v) return;
      if (v.paused) { userPaused = false; doPlay(); } else { userPaused = true; doPause(); }
    },
    play: function () { userPaused = false; doPlay(); },
    pause: function () { userPaused = true; doPause(); },
    // Resume after the player moves to another display, unless the user paused it.
    resumeAfterMove: function () { var v = video(); if (v && v.paused && !v.ended && !userPaused) doPlay(); },
    seek: function (s) { var v = video(); if (v) seekTo(v.currentTime + s); },
    seekTo: function (t) { seekTo(t); },
    time: function () { var v = video(); return v && !adShowing() ? Math.floor(v.currentTime) : -1; },
    paused: function () { var v = video(); return !v || v.paused; },
    // For the progress bar: current seconds, duration, paused, ad.
    status: function () {
      var v = video();
      if (!v) return null;
      var ad = adShowing();
      return { t: ad ? 0 : v.currentTime, d: ad ? 0 : duration(), p: v.paused || v.ended, ad: ad };
    }
  };

  // Notify the app of play/pause (<video> events don't bubble: listen in the capture phase).
  function report() {
    if (!bridge) return;
    var v = video();
    bridge.onState(!!v && !v.paused && !v.ended);
  }
  document.addEventListener('play', report, true);
  document.addEventListener('playing', report, true);
  document.addEventListener('pause', report, true);
  document.addEventListener('pause', function () {
    if (bridge && bridge.isDebug()) console.log('[AutoVideo] pause; userPaused=' + userPaused + ' ' + new Error().stack);
  }, true);
  document.addEventListener('ended', report, true);
  // Test mode: mute the instant the video tries to play sound.
  ['play', 'playing', 'volumechange', 'loadedmetadata'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      if (bridge && bridge.testMuted() && e.target && e.target.muted === false) e.target.muted = true;
    }, true);
  });

  // --- Loop: ads, autoplay, title, don't advance to the next video ---
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
    // Mobile YouTube starts videos muted ("tap to unmute"):
    // unmute them, which also stops Chromium from pausing them when hidden.
    var mp = document.getElementById('movie_player');
    var silent = bridge && bridge.testMuted();
    // CAREFUL: change the volume of a playing video only when strictly necessary: YouTube
    // reacts to programmatic volume changes by pausing the video.
    if (silent) {
      // Test mode: ALWAYS muted, and through YouTube (mute) so that its state matches
      // the video's; otherwise YouTube pauses it on resume.
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

    // YouTube jumped to another video on its own (autoplay): stop.
    if (wanted && current && current !== wanted) {
      if (!v.paused) doPause();
      return;
    }
    // First start of the requested video. YouTube sometimes pauses it while initializing:
    // keep retrying for 15 s unless the user has paused it.
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
