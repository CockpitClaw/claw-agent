package main

// Generic helper scripts for common web automation patterns.
// These prevent the agent from writing raw JS (the main source of selector
// errors and timing bugs). Each script returns JSON via JSON.stringify so
// the Go side uses evalJSON (per the evalJSON rule from porting experience).

// ── search_and_extract_links ──────────────────────────────────────────

const searchExtractLinksScript = `(function(){
  var sel = %s;
  var anchors = sel
    ? document.querySelectorAll(sel)
    : document.querySelectorAll('a[href]');
  var out = [];
  var seen = new Set();
  var limit = 50;
  for (var i = 0; i < anchors.length && out.length < limit; i++) {
    var a = anchors[i];
    var href = a.href || '';
    if (!href || href === '#' || href.startsWith('javascript:')) continue;
    if (seen.has(href)) continue;
    seen.add(href);
    var rect = a.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) continue;
    var text = (a.innerText || a.textContent || '').trim().replace(/\s+/g, ' ').substring(0, 120);
    if (!text) text = a.getAttribute('aria-label') || '';
    out.push({
      text: text,
      href: href,
      x: Math.round(rect.left + rect.width / 2),
      y: Math.round(rect.top + rect.height / 2)
    });
  }
  return JSON.stringify({count: out.length, links: out});
})()
`

// ── media_status ──────────────────────────────────────────────────────

const mediaStatusScript = `(function(){
  var v = document.querySelector('video');
  var a = document.querySelector('audio');
  var el = v || a;
  if (el) {
    var playing = !el.paused && !el.ended;
    return JSON.stringify({
      found: true,
      type: v ? 'video' : 'audio',
      paused: el.paused,
      ended: el.ended,
      playing: playing,
      currentTime: Math.round(el.currentTime * 10) / 10,
      duration: el.duration ? Math.round(el.duration * 10) / 10 : 0,
      muted: el.muted,
      volume: el.volume,
      src: (el.src || el.currentSrc || '').substring(0, 120)
    });
  }

  // No <audio>/<video> — check for custom player (Apple Music, Spotify, etc.)
  // These use Web Audio API or MSE without exposing media elements.
  var player = document.querySelector('[class*="player-bar"], [class*="chrome-player"], [data-testid*="player"], [class*="now-playing"]');
  if (!player) {
    // Fallback: any element with playback controls
    player = document.querySelector('[class*="playback-controls"], [class*="player-controls"]');
  }
  if (!player) return JSON.stringify({found: false, type: 'none'});

  // Detect play/pause state from the play button
  var playBtn = player.querySelector('[class*="play-button"], [data-testid*="play-button"], [aria-label*="Play" i], [aria-label*="Pause" i], [aria-label*="播放"], [aria-label*="暂停"]');
  var isPlaying = false;
  var btnState = 'unknown';
  if (playBtn) {
    var pressed = playBtn.getAttribute('aria-pressed');
    var label = playBtn.getAttribute('aria-label') || '';
    var dataPlaying = playBtn.getAttribute('data-playing');
    var cls = playBtn.className || '';
    // If button shows "Pause" or "playing" state, media is playing
    if (/pause/i.test(label) || /暂停/.test(label) || pressed === 'true' || dataPlaying === 'true' || /playing|is-playing/i.test(cls)) {
      isPlaying = true;
      btnState = 'playing';
    } else if (/play/i.test(label) || /播放/.test(label) || pressed === 'false') {
      btnState = 'paused';
    }
  }

  // Try to get now-playing track info from player text
  var trackTitle = '';
  var titleEl = player.querySelector('[class*="now-playing"], [class*="track-name"], [class*="song-name"], [data-testid*="title"]');
  if (titleEl) trackTitle = (titleEl.innerText || '').trim().substring(0, 80);
  // Fallback: whole player text (short)
  if (!trackTitle) {
    var t = (player.innerText || '').trim().replace(/\s+/g, ' ');
    if (t.length > 0 && t.length < 200) trackTitle = t.substring(0, 80);
  }

  return JSON.stringify({
    found: true,
    type: 'custom_player',
    paused: !isPlaying,
    playing: isPlaying,
    button_state: btnState,
    track: trackTitle,
    player_class: (player.className || '').substring(0, 60)
  });
})()
`

// ── media_play ────────────────────────────────────────────────────────

const mediaPlayScript = `(function(){
  var el = document.querySelector('video') || document.querySelector('audio');
  if (!el) return JSON.stringify({ok: false, error: 'no_media_element'});

  // Strategy 1: try .play() — fire and forget. We don't await the Promise
  // because evaluateJS uses awaitPromise=false. After calling play(), check
  // if the element is actually playing. If not, fall back to button click.
  try { el.play(); } catch(e) {}

  // Give the browser a moment to process the play request
  // (not truly async, but some browsers set paused=false after a microtask)

  // Check if play() worked
  if (!el.paused) {
    return JSON.stringify({ok: true, method: 'play_api'});
  }

  // Strategy 2: click a play button. Covers autoplay-policy-blocked cases
  // where the browser requires user gesture.
  var btn = document.querySelector(
    'button.ytp-play-button, ' +
    'button[data-testid="play-button"], ' +
    'button[aria-label*="Play" i], ' +
    'button[aria-label*="play" i], ' +
    '.play-button, ' +
    '[class*="play-btn"], ' +
    'button[title*="Play" i]'
  );
  if (btn) {
    btn.click();
    return JSON.stringify({ok: true, method: 'play_button_click'});
  }

  // Strategy 3: dispatch click on the media element itself
  // (some custom players use click-to-play on the video container)
  var container = el.closest('[class*="player"]') || el.parentElement;
  if (container && container !== el) {
    container.click();
    return JSON.stringify({ok: true, method: 'player_container_click'});
  }

  return JSON.stringify({ok: false, error: 'no_play_method', paused: el.paused});
})()
`

// ── media_pause ───────────────────────────────────────────────────────

const mediaPauseScript = `(function(){
  var el = document.querySelector('video') || document.querySelector('audio');
  if (!el) return JSON.stringify({ok: false, error: 'no_media_element'});
  try {
    el.pause();
    return JSON.stringify({ok: true, method: 'pause_api'});
  } catch(e) {
    var btn = document.querySelector('button.ytp-play-button, button[data-testid="pause-button"], button[aria-label*="Pause"], button[aria-label*="pause"], .pause-button, [class*="pause-btn"]');
    if (btn) {
      btn.click();
      return JSON.stringify({ok: true, method: 'pause_button_click'});
    }
    return JSON.stringify({ok: false, error: 'pause_failed', detail: e.message || String(e)});
  }
})()
`

// ── wait_for_element ──────────────────────────────────────────────────

const waitForElementScriptTemplate = `(function(){
  var sel = %s;
  var timeoutMs = %d;
  var start = Date.now();
  function check() {
    var el = document.querySelector(sel);
    if (el) {
      var rect = el.getBoundingClientRect();
      return JSON.stringify({found: true, waited: Date.now() - start, visible: rect.width > 0 && rect.height > 0, x: Math.round(rect.left + rect.width/2), y: Math.round(rect.top + rect.height/2)});
    }
    if (Date.now() - start > timeoutMs) {
      return JSON.stringify({found: false, waited: Date.now() - start, error: 'timeout'});
    }
    return null;
  }
  return check();
})()
`

// ── dismiss_overlays ──────────────────────────────────────────────────

const dismissOverlaysScript = `(function(){
  // Common cookie consent / overlay close button selectors.
  // Order matters: most common first. Each is tried independently.
  var selectors = [
    // Generic consent buttons
    'button#onetrust-accept-btn-handler',
    'button[data-testid="cookie-consent-accept"]',
    'button[data-testid="cookie-accept"]',
    'button#L2AGLb',                       // Google consent
    'ytd-button-renderer#accept-button button', // YouTube consent
    'button[aria-label*="Accept all" i]',
    'button[aria-label*="Accept" i]',
    'button[aria-label*="同意" i]',
    'button[aria-label*="接受" i]',
    'button[aria-label*="Agree" i]',
    // Close buttons for modals/popups
    'button[aria-label*="Close" i]',
    'button[aria-label*="关闭" i]',
    'button[aria-label*="Dismiss" i]',
    '[class*="modal"] button[class*="close"]',
    '[class*="dialog"] button[class*="close"]',
    '[class*="overlay"] button[class*="close"]',
    '[class*="popup"] button[class*="close"]',
    'button[class*="dismiss"]',
    '.cookie-banner button',
    '[class*="cookie"] button[class*="accept"]',
    // Spotify-specific
    'button[data-testid="cookie-accept-all"]',
    // Generic X/close icon buttons in corners
    'button[class*="close"][class*="button"]',
    'div[role="dialog"] button'
  ];
  var closed = [];
  for (var i = 0; i < selectors.length; i++) {
    try {
      var els = document.querySelectorAll(selectors[i]);
      for (var j = 0; j < els.length; j++) {
        var el = els[j];
        // Skip invisible elements
        var rect = el.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) continue;
        // Skip elements that look like page content (too large)
        if (rect.width > 600 && rect.height > 400) continue;
        el.click();
        closed.push({selector: selectors[i], text: (el.innerText || '').substring(0, 40)});
      }
    } catch(e) {}
  }
  return JSON.stringify({dismissed: closed.length, details: closed.slice(0, 5)});
})()
`

// ── get_page_text ─────────────────────────────────────────────────────
// Extracts key visible text from the page (titles, headings, main content).
// Returns structured data, not raw text dump, to keep LLM context small.

const getPageTextScript = `(function(){
  var title = document.title || '';
  var url = location.href;
  var headings = [];
  var hEls = document.querySelectorAll('h1, h2, h3');
  for (var i = 0; i < Math.min(hEls.length, 20); i++) {
    var t = (hEls[i].innerText || '').trim().substring(0, 120);
    if (t) headings.push(t);
  }
  var paragraphs = [];
  var pEls = document.querySelectorAll('p');
  for (var i = 0; i < Math.min(pEls.length, 15); i++) {
    var t = (pEls[i].innerText || '').trim().substring(0, 200);
    if (t.length > 10) paragraphs.push(t);
  }
  var rawText = (document.body && document.body.innerText) ? document.body.innerText : '';
  var mainText = rawText.replace(/\s+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  var textLen = mainText.length;
  if (textLen > 4000) mainText = mainText.substring(0, 4000) + '...';

  // Visible buttons / clickable actions. Order/seat-selection/payment pages
  // render their actions as <button>, <a role=button>, or div.btn with text
  // like "确认选座"/"去付款"/"立即支付". Surfacing them here lets the agent
  // pick the next click in one call instead of snapshot+screenshot loops.
  var btns = [];
  var seen = {};
  var bsel = document.querySelectorAll('button, a, [role="button"], [class*="btn"], [class*="Btn"]');
  for (var i = 0; i < bsel.length && btns.length < 15; i++) {
    var el = bsel[i];
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    var t = (el.innerText || el.textContent || '').trim();
    if (!t || t.length > 40) continue;
    // skip nav/header/footer chrome that shows on every page
    var lc = t.toLowerCase();
    if (lc === '首页' || lc === '电影' || lc === '影院' || lc === '演出' ||
        lc === '登录' || lc === '注册' || lc === '更多' || lc === '搜索') continue;
    if (seen[t]) continue;
    seen[t] = 1;
    btns.push(t);
  }

  // Canvas/image-rendered pages (maoyan seat map, image captchas, video
  // players) have almost no text — main_text comes back empty even though
  // the page is fully rendered. Detect this and tell the agent what to do
  // instead of looping get_page_text.
  var canvases = document.querySelectorAll('canvas');
  var imgs = document.querySelectorAll('img');
  var visibleCanvas = 0, visibleImg = 0;
  for (var i = 0; i < canvases.length; i++) {
    var r = canvases[i].getBoundingClientRect();
    if (r.width > 100 && r.height > 100) visibleCanvas++;
  }
  for (var i = 0; i < imgs.length; i++) {
    var r = imgs[i].getBoundingClientRect();
    if (r.width > 100 && r.height > 100) visibleImg++;
  }

  var sparse = (headings.length === 0 && paragraphs.length === 0);
  var renderHint = '';
  if (textLen < 30 && (visibleCanvas > 0 || visibleImg > 0)) {
    renderHint = 'page is canvas/image-rendered (main_text empty): use take_snapshot for DOM tree or platform-specific tools — do NOT loop get_page_text';
  } else if (sparse) {
    renderHint = 'page is SPA: use main_text (rendered body.innerText) — no need to re-call evaluate_script';
  }

  return JSON.stringify({
    title: title.substring(0,120),
    url: url,
    headings: headings,
    paragraphs: paragraphs,
    main_text: mainText,
    text_length: textLen,
    visible_buttons: btns,
    canvas_count: visibleCanvas,
    large_image_count: visibleImg,
    render_hint: renderHint
  });
})()
`

// ── detect_login_wall ─────────────────────────────────────────────────
// Detects login walls, sign-in prompts, and "preview only" indicators.
// Returns structured signals so the agent can HARD STOP instead of
// looping on a page that requires authentication.

const detectLoginWallScript = `(function(){
  var url = location.href.toLowerCase();
  var signals = [];

  // URL-based detection — use string indexOf to avoid regex escaping issues
  if (url.indexOf('/login') >= 0 || url.indexOf('/signin') >= 0 ||
      url.indexOf('/sign-in') >= 0 || url.indexOf('/account/login') >= 0 ||
      url.indexOf('/auth') >= 0) {
    signals.push({type: 'url', value: 'login_url_pattern', detail: location.href.substring(0, 120)});
  }

  // Password field presence (visible)
  var pwInputs = document.querySelectorAll('input[type="password"]');
  for (var i = 0; i < pwInputs.length; i++) {
    var r = pwInputs[i].getBoundingClientRect();
    if (r.width > 0 && r.height > 0) {
      signals.push({type: 'password_field', value: 'visible_password_input'});
      break;
    }
  }

  // Login modal / dialog detection
  var loginSelectors = [
    '[class*="login-modal"]',
    '[class*="signin-modal"]',
    '[class*="login-dialog"]',
    '[class*="login-popup"]',
    '[data-testid*="login"]',
    '[data-testid*="signin"]',
    '[aria-label*="登录" i]',
    '[aria-label*="Log in" i]',
    '[aria-label*="Sign in" i]'
  ];
  for (var i = 0; i < loginSelectors.length; i++) {
    var els = document.querySelectorAll(loginSelectors[i]);
    for (var j = 0; j < els.length; j++) {
      var r = els[j].getBoundingClientRect();
      if (r.width > 0 && r.height > 0) {
        signals.push({type: 'login_modal', value: loginSelectors[i], detail: (els[j].innerText || '').substring(0, 60)});
        break;
      }
    }
    if (signals.some(function(s){return s.type === 'login_modal';})) break;
  }

  // Phone number input (common in Chinese sites' login walls)
  var telInputs = document.querySelectorAll('input[type="tel"], input[placeholder*="手机"], input[placeholder*="phone" i]');
  for (var i = 0; i < telInputs.length; i++) {
    var r = telInputs[i].getBoundingClientRect();
    if (r.width > 0 && r.height > 0) {
      // Check if it's in a modal/dialog (not a search bar)
      var parent = telInputs[i].closest('[class*="modal"], [class*="dialog"], [class*="login"], [role="dialog"]');
      if (parent) {
        signals.push({type: 'phone_login', value: 'tel_input_in_modal'});
        break;
      }
    }
  }

  // Hard login wall text indicators (these block playback entirely)
  // Note: "试听" is NOT a login wall — it means preview mode (30s clip still plays).
  // "试听" is reported separately as preview_mode so agent can still try playing.
  var bodyText = (document.body.innerText || '').substring(0, 5000);
  var hardLoginPatterns = [
    /登录后/,
    /登录以/,
    /请登录/,
    /需要登录/,
    /登录才能/,
    /Sign in to/i,
    /Log in to/i,
    /Please sign in/i
  ];
  for (var i = 0; i < hardLoginPatterns.length; i++) {
    if (hardLoginPatterns[i].test(bodyText)) {
      signals.push({type: 'login_text', value: hardLoginPatterns[i].source});
    }
  }

  // Preview mode indicators (soft signal — playback may still work for 30s clips)
  var previewMode = false;
  var previewPatterns = [/试听/, /Preview only/i, /30秒/, /30 sec/i];
  for (var i = 0; i < previewPatterns.length; i++) {
    if (previewPatterns[i].test(bodyText)) {
      previewMode = true;
      break;
    }
  }

  // Empty body (after dismiss_overlays closed a login modal)
  if (document.body && document.body.innerText.trim().length < 50) {
    signals.push({type: 'empty_body', value: 'body_text_under_50_chars'});
  }

  return JSON.stringify({
    has_login_wall: signals.length > 0,
    preview_mode: previewMode,
    signal_count: signals.length,
    signals: signals.slice(0, 5),
    url: location.href
  });
})()
`

// ── click_play_button ─────────────────────────────────────────────────
// Finds a play button on the page and returns its coordinates.
// The Go layer then uses CDP dispatchClick (with mouseMoved = hover first)
// to actually click it — JS .click() doesn't trigger hover-revealed buttons
// on Apple Music / Spotify, which need a real mouse hover+press sequence.

const clickPlayButtonScript = `(function(){
  // Strategy 1: song row play button (Apple Music / Spotify)
  var songRowSelectors = [
    '.songs-list-row',
    '[data-testid*="track-row"]',
    '[class*="track-list"] [class*="row"]',
    'tr[class*="song"]'
  ];
  for (var s = 0; s < songRowSelectors.length; s++) {
    var rows = document.querySelectorAll(songRowSelectors[s]);
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var rowR = row.getBoundingClientRect();
      if (rowR.width === 0 || rowR.height === 0) continue;
      // Find play button inside row
      var btn = row.querySelector('[class*="play-button"], [data-testid*="play-button"], [aria-label*="Play" i], [class*="interactive-play-button"]');
      if (btn) {
        var br = btn.getBoundingClientRect();
        if (br.width > 0 && br.height > 0) {
          return JSON.stringify({ok: true, x: br.left + br.width/2, y: br.top + br.height/2, method: 'song_row_play_button'});
        }
      }
      // Fallback: click row center (often triggers preview)
      return JSON.stringify({ok: true, x: rowR.left + rowR.width*0.1, y: rowR.top + rowR.height/2, method: 'song_row_click', selector: songRowSelectors[s]});
    }
  }

  // Strategy 2: generic play button (main player)
  var selectors = [
    '[class*="play-button"]',
    '[data-testid*="play-button"]',
    'button[aria-label*="Play" i]',
    'button[aria-label*="播放"]'
  ];
  for (var i = 0; i < selectors.length; i++) {
    var els = document.querySelectorAll(selectors[i]);
    for (var j = 0; j < els.length; j++) {
      var r = els[j].getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      return JSON.stringify({ok: true, x: r.left + r.width/2, y: r.top + r.height/2, method: 'generic_' + selectors[i]});
    }
  }
  return JSON.stringify({ok: false, method: 'none'});
})()
`
