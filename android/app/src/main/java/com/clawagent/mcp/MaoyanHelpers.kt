package com.clawagent.mcp

/**
 * Maoyan (猫眼) movie-booking helper JS scripts, ported verbatim from
 * browser-mcp/maoyan_helpers.go (which were themselves ported from the car
 * mcp.js). The Kotlin side evaluates them via WebViewBridge.evaluateJs and
 * parses the returned JSON string.
 *
 * Coordinates returned by these scripts are CSS px (physScale pinned to 1);
 * the Kotlin click path converts CSS -> physical View px via viewportZoom.
 */

const val MAOYAN_CINEMA_SCRIPT = """(function(){
  var physScale = 1;
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
})()"""

const val MAOYAN_SHOWS_BY_DATE_SCRIPT_TEMPLATE = """(function(){
  var physScale = 1;
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
})()"""

const val MAOYAN_CLICK_SHOW_SCRIPT_TEMPLATE = """(function(){
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
})()"""

const val MAOYAN_SEAT_QUERY_SCRIPT = """(function(){
  var physScale = 1;
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
})()"""

const val MAOYAN_SEAT_VERIFY_SCRIPT = """(function(){
  var physScale = 1;
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
})()"""

const val MAOYAN_DISMISS_MODAL_SCRIPT = """(function(){
  var physScale = 1;
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
})()"""

const val MAOYAN_CHECK_CONFIRM_BTN_SCRIPT = """(function(){
  var physScale = 1;
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
})()"""

const val MAOYAN_DOM_CLICK_SCRIPT_TEMPLATE = """(function(){
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
})()"""