/* Pantallas La Cole — reproductor para televisores LG webOS.
 * Mismo protocolo que la app de Android: el TV se registra con un código, el panel lo aprueba,
 * y el TV reproduce lo programado (video, imagen, web). Ver README.md de esta carpeta. */
(function () {
  "use strict";

  var APP_VERSION = "1.2";
  var DEFAULT_SERVER = /^https?:$/.test(location.protocol) ? location.origin : "https://pantallas.lacole.edu.co";
  var SYNC_MS = 20000, PING_MS = 15000, TICK_MS = 1000, FAIL_MS = 60000;

  // ---------------------------------------------------------------- almacenamiento
  function lsGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { window.localStorage.setItem(k, v); } catch (e) {} }

  function serverUrl() { return (lsGet("server") || DEFAULT_SERVER).replace(/\/+$/, ""); }

  function tvId() {
    var id = lsGet("tv_id");
    if (id) return id;
    var chars = "abcdef0123456789", out = "";
    for (var i = 0; i < 16; i++) out += chars.charAt(Math.floor(Math.random() * chars.length));
    lsSet("tv_id", out);
    return out;
  }
  function code() { return tvId().slice(0, 8).toUpperCase(); }

  // ---------------------------------------------------------------- estado
  var manifest = null;
  var syncStatus = "";
  var clockOffset = 0;               // hora del servidor - hora del TV (ms)
  var current = null, currentSince = 0, videoReady = false;
  var failedUntil = {};
  var rotation = 0;
  var infoUntil = 0;
  var errNote = "";                  // último error de reproducción (se muestra y se envía al panel)
  var dlStatus = "";                 // texto de descarga en curso
  var extra = { lan_ip: null, model: null, fw: null };

  function nowMs() { return Date.now() + clockOffset; }
  function mono() { return (window.performance && performance.now) ? performance.now() : Date.now(); }

  var $ = function (id) { return document.getElementById(id); };
  var stage = $("stage"), vid = $("vid"), img = $("img"), web = $("web"),
      waiting = $("waiting"), waitText = $("waitText"), info = $("info");

  // ---------------------------------------------------------------- red
  function http(method, path, body, cb) {
    var x = new XMLHttpRequest();
    x.open(method, serverUrl() + path, true);
    x.timeout = 15000;
    if (body) x.setRequestHeader("Content-Type", "application/json");
    x.onload = function () { cb(x.status >= 200 && x.status < 300 ? null : "HTTP " + x.status, x.responseText); };
    x.onerror = function () { cb("sin conexión", null); };
    x.ontimeout = function () { cb("tiempo agotado", null); };
    x.send(body ? JSON.stringify(body) : null);
  }

  // ---------------------------------------------------------------- sincronización
  function sync() {
    http("GET", "/tv/" + tvId() + "/manifest", null, function (err, text) {
      if (err) { syncStatus = "Sin conexión con el servidor"; updateWaiting(); return; }
      var m;
      try { m = JSON.parse(text); } catch (e) { return; }
      syncStatus = "";
      if (m.server_time) clockOffset = m.server_time - Date.now();
      manifest = m;
      if (m.status === "approved") {
        var ori = parseInt(m.orientation, 10) || 0;
        if (ori !== rotation) { rotation = ori; applyGeometry(); }
        var last = lsGet("last_reload");
        if (last !== null && String(m.reload) !== last) restartContent();
        lsSet("last_reload", String(m.reload));
      }
      updateWaiting();
    });
  }

  function webosVersion() {
    var m = /Web0S[^;)]*|webOS[^;)]*/i.exec(navigator.userAgent || "");
    var c = /Chrome\/(\d+)/.exec(navigator.userAgent || "");
    return "webOS" + (c ? " (Chromium " + c[1] + ")" : "");
  }

  function ping() {
    var body = {
      model: extra.model || "LG webOS TV", android: extra.fw || webosVersion(), app: "webOS " + APP_VERSION,
      now_id: current ? current.id : null, now_title: current ? current.title : "",
      state: errNote || dlStatus || syncStatus
    };
    if (extra.lan_ip) body.lan_ip = extra.lan_ip;
    http("POST", "/tv/" + tvId() + "/ping", body, function (err, text) {
      if (err || !text) return;
      try {
        var r = JSON.parse(text);
        if (r.server_time) clockOffset = r.server_time - Date.now();
      } catch (e) {}
    });
  }

  // ---------------------------------------------------------------- lista de reproducción
  function activeAt(it, nowSec) {
    return (it.start == null || it.start <= nowSec) && (it.end == null || it.end > nowSec);
  }

  function playlist() {
    var m = manifest;
    if (!m || m.status !== "approved" || !m.items) return [];
    var nowSec = Math.floor(nowMs() / 1000), t = mono();
    function ok(i) { return (failedUntil[i.id] || 0) <= t; }
    var regular = m.items.filter(function (i) { return !i.fallback && activeAt(i, nowSec) && ok(i); });
    if (regular.length) return regular;
    return m.items.filter(function (i) { return i.fallback && ok(i); });
  }

  function indexOfId(list, id) {
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return i;
    return -1;
  }
  function nextAfter(list, cur) {
    if (!cur) return list[0];
    var idx = indexOfId(list, cur.id);
    return list[(idx + 1) % list.length];
  }

  var tickCount = 0;
  function tick() {
    tickCount++;
    var list = playlist();
    if (!list.length) {
      if (current) { current = null; hideAll(); }
      updateWaiting();
      refreshInfo();
      return;
    }
    waiting.style.display = "none";
    var cur = current;
    if (!cur || indexOfId(list, cur.id) < 0) {
      showItem(nextAfter(list, cur));
    } else {
      var elapsed = mono() - currentSince;
      if (cur.type === "video") {
        if (!videoReady && elapsed > 25000) {
          errNote = "El video no arranca (estado " + vid.readyState + ", red " + vid.networkState + "): " + cur.title;
          fail(cur);
        }
      } else {
        if (list.length > 1 && elapsed >= (cur.duration || 10) * 1000) {
          showItem(nextAfter(list, cur));
        } else if (list.length === 1 && cur.type === "web" && elapsed >= 30 * 60 * 1000) {
          currentSince = mono();
          web.src = embedUrl(cur);
        }
      }
    }
    refreshInfo();
  }

  function fail(item) {
    failedUntil[item.id] = mono() + FAIL_MS;
    if (current && current.id === item.id) current = null;
  }

  // ---------------------------------------------------------------- mostrar contenido
  function hideAll() {
    try { vid.pause(); } catch (e) {}
    vid.removeAttribute("src"); try { vid.load(); } catch (e) {}
    vid.style.display = "none";
    img.style.display = "none"; img.removeAttribute("src");
    web.style.display = "none"; web.src = "about:blank";
  }

  function mediaUrl(it) {
    if (blobUrls[it.id] && blobUrls[it.id].stamp === it.stamp) return blobUrls[it.id].url;   // copia guardada en el TV
    return it.url + (it.stamp ? (it.url.indexOf("?") < 0 ? "?v=" : "&v=") + encodeURIComponent(it.stamp) : "");
  }

  function embedUrl(it) { return serverUrl() + "/tv/" + tvId() + "/embed/" + encodeURIComponent(it.id); }

  function showItem(item) {
    current = item;
    currentSince = mono();
    videoReady = false;
    var list = playlist();
    var single = list.length <= 1;

    if (item.type === "video") {
      img.style.display = "none"; web.style.display = "none"; web.src = "about:blank";
      vid.loop = false;
      vid.muted = false;
      vid.onplaying = function () { videoReady = true; errNote = ""; };
      vid.onended = function () {
        if (current !== item) return;
        var l = playlist();
        if (!l.length) return;
        if (l.length === 1) {
          var u = mediaUrl(item);                 // si ya se guardó una copia en el TV, se pasa a ella
          if (vid.src !== u) { vid.src = u; }
          else { vid.currentTime = 0; }
          vid.play();
          return;
        }
        showItem(nextAfter(l, item));
      };
      vid.onerror = function () {
        var e = vid.error;
        errNote = "Error de video " + (e ? e.code : "?") + (e && e.message ? " " + e.message : "") + " (" + item.title + ")";
        if (current === item) fail(item);
      };
      vid.src = mediaUrl(item);
      vid.style.display = "block";
      var p = vid.play();
      if (p && p.catch) p.catch(function (err) {
        // Si el TV no deja iniciar con sonido, se reintenta en silencio
        vid.muted = true;
        var p2 = vid.play();
        if (p2 && p2.catch) p2.catch(function (e2) {
          errNote = "No inicia el video: " + (e2 && e2.name ? e2.name : "?");
        });
      });
    } else if (item.type === "image") {
      try { vid.pause(); } catch (e) {}
      vid.style.display = "none"; web.style.display = "none"; web.src = "about:blank";
      img.onerror = function () {
        errNote = "Error de imagen: " + item.title;
        if (current === item) fail(item);
      };
      img.src = mediaUrl(item);
      img.style.display = "block";
    } else if (item.type === "web") {
      try { vid.pause(); } catch (e) {}
      vid.style.display = "none"; img.style.display = "none";
      web.src = embedUrl(item);
      web.style.display = "block";
    } else {
      fail(item);
    }
  }

  function restartContent() {
    current = null;
    failedUntil = {};
    hideAll();
  }

  // ---------------------------------------------------------------- pantalla de espera e información
  function updateWaiting() {
    var m = manifest, t;
    if (m && m.status !== "approved") t = "Pantalla pendiente de aprobación\nCódigo: " + code();
    else if (!m && syncStatus) t = syncStatus + "\nCódigo: " + code();
    else if (!m) t = "Conectando…\nCódigo: " + code();
    else if (syncStatus) t = syncStatus;
    else t = "Sin contenido programado";
    if (errNote) t += "\n" + errNote;
    waitText.textContent = t;
    if (!current) waiting.style.display = "flex";
  }

  function refreshInfo() {
    if (Date.now() < infoUntil) {
      info.textContent = "Pantalla: " + (manifest && manifest.name ? manifest.name : "?") + "  (" + code() + ")\n" +
        "Servidor: " + serverUrl() + "\n" +
        "Reproduciendo: " + (current ? current.title : "—") + "\n" +
        "App webOS " + APP_VERSION + ((dlStatus || syncStatus) ? "\n" + (dlStatus || syncStatus) : "");
      info.style.display = "block";
    } else {
      info.style.display = "none";
    }
  }

  // El botón OK / Enter del control muestra la información unos segundos.
  document.addEventListener("keydown", function (e) {
    if (e.keyCode === 13 || e.keyCode === 457 /* Info */) {
      infoUntil = Date.now() + 6000;
      refreshInfo();
    }
  });

  // ---------------------------------------------------------------- geometría (orientación)
  function applyGeometry() {
    var w = window.innerWidth, h = window.innerHeight;
    var swap = rotation === 90 || rotation === 270;
    var sw = swap ? h : w, sh = swap ? w : h;
    stage.style.width = sw + "px";
    stage.style.height = sh + "px";
    stage.style.marginLeft = (-sw / 2) + "px";
    stage.style.marginTop = (-sh / 2) + "px";
    stage.style.transform = "rotate(" + rotation + "deg)";
  }
  window.addEventListener("resize", applyGeometry);
  window.onerror = function (msg) { errNote = "Error: " + String(msg).slice(0, 120); };


  // ---------------------------------------------------------------- copia local de videos e imágenes
  // Los archivos se guardan en trozos dentro de IndexedDB; así siguen disponibles sin internet.
  var DB_NAME = "pantallas-cache", CHUNK = 4 * 1024 * 1024, RESERVE = 300 * 1024 * 1024;
  var blobUrls = {};                 // id -> {stamp, url}
  var dbp = null, downloading = false;
  var MIME = { ".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/mp4", ".webm": "video/webm",
               ".mkv": "video/x-matroska", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
               ".gif": "image/gif", ".webp": "image/webp" };

  function openDb() {
    if (dbp) return dbp;
    dbp = new Promise(function (res, rej) {
      var r = indexedDB.open(DB_NAME, 1);
      r.onupgradeneeded = function () { r.result.createObjectStore("files", { keyPath: "id" }); };
      r.onsuccess = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
    });
    return dbp;
  }
  function idb(mode, fn) {
    return openDb().then(function (db) {
      return new Promise(function (res, rej) {
        var tx = db.transaction("files", mode), st = tx.objectStore("files"), out = fn(st);
        tx.oncomplete = function () { res(out && out.result !== undefined ? out.result : undefined); };
        tx.onerror = tx.onabort = function () { rej(tx.error || new Error("idb")); };
      });
    });
  }
  function dbGet(id) { return idb("readonly", function (st) { return st.get(id); }); }
  function dbPut(rec) { return idb("readwrite", function (st) { return st.put(rec); }); }
  function dbDel(id) { return idb("readwrite", function (st) { return st.delete(id); }); }
  function dbKeys() { return idb("readonly", function (st) { return st.getAllKeys(); }); }

  function useCached(it, rec) {
    var old = blobUrls[it.id];
    if (old && old.stamp === rec.stamp) return;
    if (old) { try { URL.revokeObjectURL(old.url); } catch (e) {} }
    blobUrls[it.id] = { stamp: rec.stamp, url: URL.createObjectURL(new Blob(rec.chunks, { type: rec.type })) };
    if (current && current.id === it.id && it.type === "image") img.src = blobUrls[it.id].url;
  }

  function fetchRange(url, from, to) {
    return new Promise(function (res, rej) {
      var x = new XMLHttpRequest();
      x.open("GET", url, true);
      x.responseType = "blob";
      x.timeout = 60000;
      x.setRequestHeader("Range", "bytes=" + from + "-" + to);
      x.onload = function () {
        if (x.status === 206) res(x.response);
        else if (x.status === 200 && from === 0) res(x.response);   // archivo pequeño servido completo
        else rej(new Error("HTTP " + x.status));
      };
      x.onerror = function () { rej(new Error("red")); };
      x.ontimeout = function () { rej(new Error("tiempo")); };
      x.send();
    });
  }

  function freeSpace() {
    if (navigator.storage && navigator.storage.estimate) {
      return navigator.storage.estimate().then(function (e) { return (e.quota || 0) - (e.usage || 0); },
        function () { return 0; });
    }
    return Promise.resolve(0);
  }

  function ensureCached(it) {
    return dbGet(it.id).then(function (rec) {
      if (rec && rec.complete && rec.stamp === it.stamp) { useCached(it, rec); return; }
      return freeSpace().then(function (free) {
        if (!free || free < (it.size || 0) + RESERVE) { dlStatus = ""; return; }   // sin espacio: se reproduce por internet
        var chunks = [], got = 0, size = it.size || 0, type = MIME[(it.ext || "").toLowerCase()] ||
          (it.type === "video" ? "video/mp4" : "image/jpeg");
        function next() {
          if (got >= size) return Promise.resolve();
          var to = Math.min(got + CHUNK, size) - 1;
          return fetchRange(it.url, got, to).then(function (b) {
            chunks.push(b); got += b.size;
            dlStatus = "Descargando " + (it.title || it.id).slice(0, 40) + " " + Math.floor(got * 100 / size) + "%";
            if (b.size === 0) throw new Error("vacío");
            return next();
          });
        }
        return next().then(function () {
          var r = { id: it.id, stamp: it.stamp, type: type, chunks: chunks, complete: got >= size, size: size };
          return dbPut(r).then(function () { useCached(it, r); });
        });
      });
    });
  }

  function cleanCache(m) {
    var keep = {};
    (m.items || []).forEach(function (i) { keep[i.id] = i.stamp; });
    return dbKeys().then(function (keys) {
      return Promise.all((keys || []).map(function (k) {
        if (!(k in keep)) {
          if (blobUrls[k]) { try { URL.revokeObjectURL(blobUrls[k].url); } catch (e) {} delete blobUrls[k]; }
          return dbDel(k);
        }
      }));
    });
  }

  function cacheAll() {
    if (downloading || !manifest || manifest.status !== "approved" || !window.indexedDB) return;
    downloading = true;
    var m = manifest;
    var media = (m.items || []).filter(function (i) { return (i.type === "video" || i.type === "image") && i.size > 0; });
    cleanCache(m).catch(function () {}).then(function () {
      return media.reduce(function (p, it) {
        return p.then(function () { return ensureCached(it).catch(function () {}); });
      }, Promise.resolve());
    }).then(function () { dlStatus = ""; downloading = false; }, function () { dlStatus = ""; downloading = false; });
  }

  // ---------------------------------------------------------------- datos del televisor (IP y modelo)
  function luna(uri, params, cb) {
    try {
      var b = new PalmServiceBridge();
      b.onservicecallback = function (msg) { try { cb(JSON.parse(msg)); } catch (e) {} };
      b.call(uri, JSON.stringify(params || {}));
    } catch (e) {}
  }
  function readTvInfo() {
    luna("luna://com.palm.connectionmanager/getStatus", {}, function (r) {
      var ip = (r.wired && r.wired.state === "connected" && r.wired.ipAddress) ||
               (r.wifi && r.wifi.state === "connected" && r.wifi.ipAddress) || null;
      if (ip) extra.lan_ip = ip;
    });
    luna("luna://com.webos.service.tv.systemproperty/getSystemInfo", { keys: ["modelName", "firmwareVersion", "sdkVersion"] },
      function (r) {
        if (r.modelName) extra.model = "LG " + r.modelName;
        if (r.firmwareVersion || r.sdkVersion) extra.fw = "webOS " + (r.sdkVersion || "") + " fw " + (r.firmwareVersion || "");
      });
  }

  // ---------------------------------------------------------------- arranque
  applyGeometry();
  updateWaiting();
  readTvInfo();
  setInterval(readTvInfo, 5 * 60 * 1000);
  sync(); ping();
  setInterval(cacheAll, 30000);
  setTimeout(cacheAll, 4000);
  setInterval(sync, SYNC_MS);
  setInterval(ping, PING_MS);
  setInterval(tick, TICK_MS);
})();
