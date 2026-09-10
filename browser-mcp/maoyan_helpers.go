package main

// JS scripts ported verbatim from car-machine mcp.js (web_demo_skills/android-webview-mcp/web_demo/maoyan-movie/mcp.js).
// These are the DOM-extraction + modal-handling helpers that made the car flow
// robust. They are site-specific to maoyan but encoding-agnostic (run via
// Runtime.evaluate from Go the same way the car's Node wrapper ran them).
//
// physScale note: on PC Chrome window.outerWidth==innerWidth so physScale==1;
// kept for parity with the car version which ran inside a scaled WebView.

const maoyanCinemaScript = `(function(){
  var physScale = (window.outerWidth > 0 && window.innerWidth > 0) ? window.outerWidth / window.innerWidth : 1;
  var out = [];
  var cells = document.querySelectorAll('.cinema-cell,[class*="cinema-cell"],.cinemas-list li,.cinemas-list>div>div');
  var seen = new Set();
  for (var i = 0; i < cells.length; i++) {
    var el = cells[i];
    var link = el.querySelector('a[href*="/cinema/"]');
    if (!link) continue;
    var m = link.href.match(/\/cinema\/(\d+)/);
    if (!m) continue;
    var cinemaId = m[1];
    if (seen.has(cinemaId)) continue;
    seen.add(cinemaId);
    var r = link.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    var nameEl = el.querySelector('.cinema-name,[class*="cinema-name"]') || link;
    var priceEl = el.querySelector('[class*="price"],.cinema-price');
    var distEl = el.querySelector('[class*="dist"],.distance');
    var addrEl = el.querySelector('[class*="address"],[class*="addr"],.cinema-address');
    var distTxt = distEl ? (distEl.innerText || '').trim() : '';
    var distKm = parseFloat(distTxt) || 9999;
    var addr = addrEl ? (addrEl.innerText || '').trim().slice(0, 50) : '';
    out.push({ index: out.length, cinemaId: cinemaId,
      name: (nameEl.innerText || '').trim().slice(0, 40),
      address: addr,
      price: priceEl ? (priceEl.innerText || '').trim().slice(0, 20) : '',
      distance: distTxt.slice(0, 15), distKm: distKm,
      x: Math.round((r.left + r.width / 2) * physScale), y: Math.round((r.top + r.height / 2) * physScale) });
    if (out.length >= 10) break;
  }
  return JSON.stringify(out);
})()`

// maoyanShowsScript is the "Path A + B + C" fallback that does NOT filter by
// movieId/date — used when caller doesn't have those (e.g. cinema detail page
// reached directly). Car version's _showsScript.
const maoyanShowsScript = `(function(){
  var physScale = (window.outerWidth > 0 && window.innerWidth > 0) ? window.outerWidth / window.innerWidth : 1;
  var now = new Date();
  var nowMin = now.getHours() * 60 + now.getMinutes();
  var seen = new Set();
  var out = [];
  function pushFrom(container, buyEl, timeHint) {
    if (!buyEl) return;
    var tag = (buyEl.tagName || '').toUpperCase();
    if (tag === 'TH' || tag === 'TD' || tag === 'THEAD') return;
    var r = buyEl.getBoundingClientRect();
    var x = Math.round((r.left + r.width / 2) * physScale), y = Math.round((r.top + r.height / 2) * physScale);
    var key = x + ',' + y;
    if (seen.has(key)) return;
    seen.add(key);
    var text = (container && container.innerText || '').replace(/\s+/g, ' ').trim();
    var timeText = timeHint;
    if (!timeText) { var tm2 = text.match(/(\d{1,2}):(\d{2})/); timeText = tm2 ? tm2[0] : ''; }
    if (!timeText) return;
    var tm = timeText.match(/(\d{1,2}):(\d{2})/);
    var t = tm ? parseInt(tm[1]) * 60 + parseInt(tm[2]) : -1;
    out.push({ time: timeText, timeMin: t, passed: t >= 0 && t < nowMin,
      buyText: (buyEl.innerText || '').trim().slice(0, 20),
      href: buyEl.href || '', x: x, y: y });
  }
  var times = document.querySelectorAll('.begin-time,[class*="begin-time"]');
  for (var i = 0; i < times.length; i++) {
    var tEl = times[i], tTxt = (tEl.innerText || '').trim();
    if (!/\d{1,2}:\d{2}/.test(tTxt)) continue;
    var container = tEl, buyEl = null;
    for (var d = 0; d < 8 && container; d++) {
      buyEl = container.querySelector('.buy-btn.normal,[class*="buy-btn"],a[href*="xseats"]');
      if (buyEl) break;
      container = container.parentElement;
    }
    pushFrom(container, buyEl, tTxt.split('\n')[0]);
  }
  var allEls = document.querySelectorAll('a,button,div,span');
  for (var j = 0; j < allEls.length; j++) {
    var el = allEls[j];
    var tag2 = (el.tagName || '').toUpperCase();
    if (tag2 === 'TH' || tag2 === 'TD' || tag2 === 'THEAD') continue;
    var txt = (el.innerText || '').trim();
    if (txt !== '选座购票' && txt !== '购票') continue;
    var rr = el.getBoundingClientRect();
    if (rr.width <= 0 || rr.width > 400) continue;
    var c2 = el;
    for (var d2 = 0; d2 < 6 && c2; d2++) {
      if (/\d{1,2}:\d{2}/.test(c2.innerText || '')) break;
      c2 = c2.parentElement;
    }
    pushFrom(c2, el, null);
  }
  var xs = document.querySelectorAll('a[href*="xseats"]');
  for (var k = 0; k < xs.length; k++) {
    var xEl = xs[k], c3 = xEl;
    for (var d3 = 0; d3 < 6 && c3; d3++) {
      if (/\d{1,2}:\d{2}/.test(c3.innerText || '')) break;
      c3 = c3.parentElement;
    }
    pushFrom(c3, xEl, null);
  }
  out.sort(function(a, b) { return a.timeMin - b.timeMin; });
  return JSON.stringify({ count: out.length, shows: out, nowMin: nowMin });
})()`

// maoyanShowsByDateScript filters by targetDate+movieId — car _buildGetShowsScript.
// Placeholders TARGET_DATE / MOVIE_ID are substituted by Go before eval.
const maoyanShowsByDateScriptTemplate = `(function(){
  var physScale = (window.outerWidth > 0 && window.innerWidth > 0) ? window.outerWidth / window.innerWidth : 1;
  var targetDate = TARGET_DATE;
  var movieId    = MOVIE_ID;
  var now = new Date();
  var nowMin = now.getHours() * 60 + now.getMinutes();
  var xsLinks = document.querySelectorAll('a[href*="xseats"]');
  var byShowId = {};
  for (var k = 0; k < xsLinks.length; k++) {
    var href = xsLinks[k].href || '';
    var mShow = href.match(/xseats\/(\d+)\?.*movieId=(\d+)/);
    if (!mShow) continue;
    var showId  = mShow[1];
    var mId     = mShow[2];
    if (mId !== movieId)    continue;
    if (showId.indexOf(targetDate) !== 0) continue;
    var container = xsLinks[k];
    for (var d = 0; d < 8 && container; d++) {
      if (/\d{1,2}:\d{2}/.test(container.innerText || '')) break;
      container = container.parentElement;
    }
    var timeM = (container.innerText || '').match(/(\d{1,2}:\d{2})/);
    if (!timeM) continue;
    var timeStr = timeM[1];
    var tm = timeStr.match(/(\d{1,2}):(\d{2})/);
    var timeMin = tm ? parseInt(tm[1]) * 60 + parseInt(tm[2]) : -1;
    if (!byShowId[showId]) {
      byShowId[showId] = { showId: showId, time: timeStr, timeMin: timeMin,
        passed: timeMin >= 0 && timeMin < nowMin,
        href: href, x: 0, y: 0 };
    }
  }
  var byTime = {};
  for (var id in byShowId) {
    var s = byShowId[id];
    if (!byTime[s.time]) byTime[s.time] = s;
  }
  var allEls = document.querySelectorAll('a,button,div,span');
  for (var j = 0; j < allEls.length; j++) {
    var el = allEls[j];
    var tag = (el.tagName || '').toUpperCase();
    if (tag === 'TH' || tag === 'TD' || tag === 'THEAD') continue;
    var txt = (el.innerText || '').trim();
    if (txt !== '选座购票' && txt !== '购票') continue;
    var rr = el.getBoundingClientRect();
    if (rr.width <= 0 || rr.width > 400) continue;
    var c2 = el;
    for (var d2 = 0; d2 < 6 && c2; d2++) {
      if (/\d{1,2}:\d{2}/.test(c2.innerText || '')) break;
      c2 = c2.parentElement;
    }
    var tM = (c2.innerText || '').match(/(\d{1,2}:\d{2})/);
    if (!tM) continue;
    var tStr = tM[1];
    if (byTime[tStr] && byTime[tStr].x === 0) {
      byTime[tStr].x = Math.round((rr.left + rr.width / 2) * physScale);
      byTime[tStr].y = Math.round((rr.top  + rr.height / 2) * physScale);
    }
  }
  var out = Object.values(byShowId).sort(function(a, b) { return a.timeMin - b.timeMin; });
  return JSON.stringify({ count: out.length, shows: out, nowMin: nowMin });
})()`

// maoyanClickShowScriptTemplate — navigate to xseats URL directly via
// window.location.href for the target time. Car _buildClickShowScript.
const maoyanClickShowScriptTemplate = `(function(){
  var time = CLICK_TIME;
  var movieId = CLICK_MOVIE_ID;
  var targetDate = CLICK_DATE;
  var xsLinks = document.querySelectorAll('a[href*="xseats"]');
  var chosen = null;
  for (var k = 0; k < xsLinks.length; k++) {
    var href = xsLinks[k].href || '';
    var mShow = href.match(/xseats\/(\d+)\?.*movieId=(\d+)/);
    if (!mShow) continue;
    var showId  = mShow[1];
    var mId     = mShow[2];
    if (mId !== movieId) continue;
    if (showId.indexOf(targetDate) !== 0) continue;
    var container = xsLinks[k];
    for (var d = 0; d < 8 && container; d++) {
      if (/\d{1,2}:\d{2}/.test(container.innerText || '')) break;
      container = container.parentElement;
    }
    var timeM = (container.innerText || '').match(/(\d{1,2}:\d{2})/);
    if (!timeM) continue;
    if (timeM[1] === time) { chosen = href; break; }
  }
  if (chosen) { window.location.href = chosen; return JSON.stringify({found:true, href:chosen}); }
  return JSON.stringify({found:false});
})()`

// maoyanSeatQueryScript — car _seatQueryScript. Returns row layout + orderBtn.
const maoyanSeatQueryScript = `(function(){
  var physScale = (window.outerWidth > 0 && window.innerWidth > 0) ? window.outerWidth / window.innerWidth : 1;
  var allSeats = document.querySelectorAll('span[class*="seat"]');
  var rows = {};
  var vw = window.innerWidth || 1280;
  var cx = vw / 2;
  for (var i = 0; i < allSeats.length; i++) {
    var s = allSeats[i];
    var cls = s.className;
    if (cls.indexOf('selectable') < 0 && cls.indexOf('sold') < 0 && cls.indexOf('empty') < 0 && cls.indexOf('selected') < 0) continue;
    var r = s.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    var y = Math.round(r.top);
    if (!rows[y]) rows[y] = [];
    var avail = cls.indexOf('selectable') >= 0 || cls.indexOf('selected') >= 0;
    rows[y].push({ x: Math.round((r.left + r.width / 2) * physScale), y: Math.round((r.top + r.height / 2) * physScale),
                   cls: cls, avail: avail });
  }
  var ys = Object.keys(rows).map(Number).sort(function(a, b) { return a - b; });
  var rowList = ys.map(function(y, idx) {
    var allInRow = rows[y].sort(function(a, b) { return a.x - b.x; });
    var selectable = allInRow.filter(function(s) { return s.avail; });
    return { rowIdx: idx, y: y, count: selectable.length,
             seats: selectable, allSeats: allInRow };
  });
  var selectedNow = document.querySelectorAll('span[class*="seat"][class*="selected"]').length;
  var priceEl = document.querySelector('.total-price,[class*="total-price"],[class*="totalPrice"]');
  var orderBtn = null;
  var cands = document.querySelectorAll('a,button,div,span');
  for (var j = 0; j < cands.length; j++) {
    if ((cands[j].innerText || '').trim() === '确认选座') {
      var rr = cands[j].getBoundingClientRect();
      if (rr.width > 0 && rr.width < 500) {
        orderBtn = { x: Math.round((rr.left + rr.width / 2) * physScale), y: Math.round((rr.top + rr.height / 2) * physScale) }; break;
      }
    }
  }
  var availTotal = rowList.reduce(function(sum, r) { return sum + r.count; }, 0);
  return JSON.stringify({ availableCount: availTotal, rowCount: rowList.length,
    rows: rowList, selectedNow: selectedNow,
    price: priceEl ? (priceEl.innerText || '').trim() : '',
    orderBtn: orderBtn, centerX: Math.round(cx * physScale) });
})()`

// maoyanSeatVerifyScript — check selected count + confirm btn after click.
const maoyanSeatVerifyScript = `(function(){
  var physScale = (window.outerWidth > 0 && window.innerWidth > 0) ? window.outerWidth / window.innerWidth : 1;
  var n = document.querySelectorAll('span[class*="seat"][class*="selected"]').length;
  var p = (document.querySelector('.total-price,[class*="total-price"],[class*="totalPrice"]') || {}).innerText || '';
  var btn = null;
  var cands = document.querySelectorAll('a,button,div,span');
  for (var j = 0; j < cands.length; j++) {
    if ((cands[j].innerText || '').trim() === '确认选座') {
      var rr = cands[j].getBoundingClientRect();
      if (rr.width > 0 && rr.width < 500) { btn = { x: Math.round((rr.left + rr.width / 2) * physScale), y: Math.round((rr.top + rr.height / 2) * physScale) }; break; }
    }
  }
  return JSON.stringify({ selectedNow: n, price: p, orderBtn: btn });
})()`

// maoyanChildModalScript — find "我知道了" modal close button coords.
const maoyanChildModalScript = `(function(){
  var physScale = (window.outerWidth > 0 && window.innerWidth > 0) ? window.outerWidth / window.innerWidth : 1;
  var all = Array.prototype.slice.call(document.querySelectorAll('*'));
  var btn = null;
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    var t = (el.innerText || el.textContent || '').trim();
    if (t === '我知道了') {
      var r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) { btn = el; break; }
    }
  }
  if (btn) {
    var r = btn.getBoundingClientRect();
    return JSON.stringify({ x: Math.round((r.left + r.width/2)*physScale), y: Math.round((r.top + r.height/2)*physScale) });
  }
  return 'no-modal';
})()`

// maoyanDismissModalScript — car autoConfirmSeat's DISMISS_MODAL_SCRIPT:
// returns 'isolated-warning' | 'no-modal' | {x,y} for the "我知道了" button.
const maoyanDismissModalScript = `(function(){
  var physScale=(window.outerWidth>0&&window.innerWidth>0)?window.outerWidth/window.innerWidth:1;
  var body = document.body.innerText || '';
  if (body.indexOf('留空') >= 0 && body.indexOf('孤立') >= 0) return 'isolated-warning';
  var all = Array.prototype.slice.call(document.querySelectorAll('*'));
  var btn = null;
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    var t = (el.innerText || el.textContent || '').trim();
    if (t === '我知道了') {
      var r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) { btn = el; break; }
    }
  }
  if (btn) {
    var r = btn.getBoundingClientRect();
    return JSON.stringify({ x: Math.round((r.left + r.width/2)*physScale), y: Math.round((r.top + r.height/2)*physScale) });
  }
  return 'no-modal';
})()`

// maoyanCheckConfirmBtnScript — car autoConfirmSeat's CHECK_MODAL_SCRIPT:
// is "确认选座" blocked by an overlay? returns {blocked, x, y}.
const maoyanCheckConfirmBtnScript = `(function(){
  var physScale=(window.outerWidth>0&&window.innerWidth>0)?window.outerWidth/window.innerWidth:1;
  var cands = document.querySelectorAll('a,button,div,span');
  for (var j = 0; j < cands.length; j++) {
    var t = (cands[j].innerText || '').trim();
    if (t === '确认选座') {
      var rr = cands[j].getBoundingClientRect();
      if (rr.width > 0 && rr.width < 500) {
        var top = document.elementFromPoint(rr.left + rr.width/2, rr.top + rr.height/2);
        var blocked = top && top !== cands[j] && !cands[j].contains(top);
        return JSON.stringify({
          blocked: blocked,
          topEl: top ? top.className : null,
          x: Math.round((rr.left + rr.width/2)*physScale),
          y: Math.round((rr.top + rr.height/2)*physScale)
        });
      }
    }
  }
  return JSON.stringify({blocked: false, topEl: null, x: 0, y: 0});
})()`

// maoyanDomClickScript — click an element by visible innerText (car domClick.byText).
// Returns {clicked:true} if an element with the exact text was .click()-ed.
const maoyanDomClickScriptTemplate = `(function(){
  var texts = TEXTS_JSON;
  var maxW = MAX_WIDTH;
  var all = Array.prototype.slice.call(document.querySelectorAll('a,button,div,span'));
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    var t = (el.innerText || '').trim();
    if (texts.indexOf(t) === -1) continue;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.width > maxW) continue;
    el.click();
    return JSON.stringify({clicked:true, text:t, x:Math.round(r.left+r.width/2), y:Math.round(r.top+r.height/2)});
  }
  return JSON.stringify({clicked:false});
})()`
