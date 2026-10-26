"""Pantallas La Cole - servidor central (panel + API para los TV)."""
import hashlib
import json
import os
import re
import secrets
import sqlite3
import time
import urllib.parse
import uuid
import zipfile

import requests as http
from flask import (Flask, Response, abort, g, jsonify, redirect, request,
                   send_file, send_from_directory, session)
from google.auth.transport import requests as g_requests
from google.oauth2 import id_token as g_id_token

# ---------------------------------------------------------------- configuración
DATA_DIR = os.environ.get("DATA_DIR", "/data")
MEDIA_DIR = os.path.join(DATA_DIR, "media")
DB_PATH = os.path.join(DATA_DIR, "pantallas.db")
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8000").rstrip("/")
CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID", "")
CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET", "")
ADMIN_EMAILS = {e.strip().lower() for e in os.environ.get("ADMIN_EMAILS", "").split(",") if e.strip()}
ALLOWED_DOMAIN = os.environ.get("ALLOWED_DOMAIN", "lacole.edu.co").strip().lower()
TZ_NAME = os.environ.get("TZ_NAME", "America/Bogota")
DEV_LOGIN = os.environ.get("DEV_LOGIN", "0") == "1"
MAX_UPLOAD_MB = int(os.environ.get("MAX_UPLOAD_MB", "300"))
ONLINE_SECONDS = 60
PRELOAD_SECONDS = 2 * 86400   # el TV descarga un contenido hasta 2 días antes de su inicio
GRACE_SECONDS = 1 * 86400     # y lo conserva 1 día después de su fin

VIDEO_EXT = {".mp4", ".m4v", ".mkv", ".webm", ".mov", ".ts"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif"}

APP_DIR = os.path.join(DATA_DIR, "app")
APK_PATH = os.path.join(APP_DIR, "app.apk")
RELEASE_PATH = os.path.join(APP_DIR, "release.json")
MAX_APK_MB = 200
THUMB_DIR = os.path.join(DATA_DIR, "thumbs")
os.makedirs(MEDIA_DIR, exist_ok=True)
os.makedirs(THUMB_DIR, exist_ok=True)
os.makedirs(APP_DIR, exist_ok=True)

app = Flask(__name__, static_folder=None)
def _secret():
    k = os.environ.get("SECRET_KEY")
    if k:
        return k
    p = os.path.join(DATA_DIR, ".secret_key")
    if not os.path.exists(p):
        with open(p, "w") as f:
            f.write(secrets.token_hex(32))
        os.chmod(p, 0o600)
    return open(p).read().strip()


app.secret_key = _secret()
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=BASE_URL.startswith("https"),
    PERMANENT_SESSION_LIFETIME=14 * 86400,
    MAX_CONTENT_LENGTH=MAX_UPLOAD_MB * 1024 * 1024 + 1024,
)
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")


# ---------------------------------------------------------------- base de datos
SCHEMA = """
CREATE TABLE IF NOT EXISTS screens (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',      -- pending | approved
  orientation INTEGER NOT NULL DEFAULT 0,      -- 0, 90, 180, 270 (grados de giro del contenido)
  sleep TEXT NOT NULL DEFAULT '[]',            -- [{"days":[1..7],"time":"HH:MM"}]
  created REAL NOT NULL,
  last_seen REAL, ip TEXT, now_id TEXT, now_title TEXT, state TEXT,
  free_mb INTEGER, model TEXT, android TEXT, app TEXT,
  app_code INTEGER, owner INTEGER, upd_sha TEXT,
  reload INTEGER NOT NULL DEFAULT 0,
  cmd TEXT
);
CREATE TABLE IF NOT EXISTS contents (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  type TEXT NOT NULL,                          -- video | image | web
  file TEXT, orig_name TEXT, size INTEGER, mtime INTEGER,
  url TEXT,
  duration INTEGER,                            -- segundos (imagen / web)
  start_at INTEGER, end_at INTEGER,            -- epoch (s) o NULL
  fallback INTEGER NOT NULL DEFAULT 0,
  interactive INTEGER NOT NULL DEFAULT 0,
  targets TEXT,                                -- NULL = todas | JSON lista de ids
  sort INTEGER NOT NULL DEFAULT 0,
  created REAL NOT NULL, created_by TEXT
);
CREATE TABLE IF NOT EXISTS log (
  ts REAL, who TEXT, what TEXT
);
"""


def db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH, timeout=30)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA journal_mode=WAL")
    return g.db


@app.teardown_appcontext
def _close(_exc):
    d = g.pop("db", None)
    if d is not None:
        d.close()


def init_db():
    c = sqlite3.connect(DB_PATH)
    c.executescript(SCHEMA)
    cols = {r[1] for r in c.execute("PRAGMA table_info(screens)")}
    for name, typ in (("app_code", "INTEGER"), ("owner", "INTEGER"), ("upd_sha", "TEXT"), ("lan_ip", "TEXT")):
        if name not in cols:
            c.execute(f"ALTER TABLE screens ADD COLUMN {name} {typ}")
    # El apagado programado ya no se usa: se limpian los horarios que hubiera.
    c.execute("UPDATE screens SET sleep='[]' WHERE sleep != '[]'")
    c.commit()
    c.close()


init_db()


def audit(what):
    db().execute("INSERT INTO log VALUES (?,?,?)", (time.time(), session.get("email", "-"), what))
    db().commit()


# ---------------------------------------------------------------- autenticación
def email_allowed(email, hd=None):
    email = (email or "").lower()
    if ADMIN_EMAILS:
        return email in ADMIN_EMAILS
    return email.endswith("@" + ALLOWED_DOMAIN)


def admin_required(f):
    def wrapper(*a, **kw):
        if not session.get("email"):
            return jsonify(error="auth"), 401
        if request.method not in ("GET", "HEAD") and request.headers.get("X-Requested-With") != "panel":
            return jsonify(error="csrf"), 403
        return f(*a, **kw)
    wrapper.__name__ = f.__name__
    return wrapper


@app.get("/login")
def login_page():
    if session.get("email"):
        return redirect("/")
    err = request.args.get("e", "")
    msg = {"denied": "Tu cuenta no tiene acceso a este panel.",
           "fail": "No se pudo iniciar sesión. Intenta de nuevo."}.get(err, "")
    html = (LOGIN_HTML.replace("__MSG__", msg)
            .replace("__MD__", "block" if msg else "none")
            .replace("__DD__", "block" if DEV_LOGIN else "none"))
    return Response(html, mimetype="text/html")


@app.get("/auth/google")
def auth_google():
    if not CLIENT_ID:
        return "Falta configurar GOOGLE_CLIENT_ID", 500
    state = secrets.token_urlsafe(24)
    session["oauth_state"] = state
    q = urllib.parse.urlencode({
        "client_id": CLIENT_ID, "redirect_uri": BASE_URL + "/auth/callback",
        "response_type": "code", "scope": "openid email profile", "state": state,
        "prompt": "select_account", "hd": ALLOWED_DOMAIN,
    })
    return redirect("https://accounts.google.com/o/oauth2/v2/auth?" + q)


@app.get("/auth/callback")
def auth_callback():
    if request.args.get("state") != session.pop("oauth_state", None) or "code" not in request.args:
        return redirect("/login?e=fail")
    try:
        r = http.post("https://oauth2.googleapis.com/token", data={
            "code": request.args["code"], "client_id": CLIENT_ID, "client_secret": CLIENT_SECRET,
            "redirect_uri": BASE_URL + "/auth/callback", "grant_type": "authorization_code"}, timeout=15)
        r.raise_for_status()
        info = g_id_token.verify_oauth2_token(r.json()["id_token"], g_requests.Request(), CLIENT_ID)
    except Exception:
        return redirect("/login?e=fail")
    email = (info.get("email") or "").lower()
    if not info.get("email_verified") or not email_allowed(email, info.get("hd")):
        return redirect("/login?e=denied")
    if not ADMIN_EMAILS and (info.get("hd") or "").lower() != ALLOWED_DOMAIN:
        return redirect("/login?e=denied")
    session.clear()
    session.permanent = True
    session["email"] = email
    session["name"] = info.get("name") or email
    audit("login")
    return redirect("/")


@app.get("/dev-login")
def dev_login():
    if not DEV_LOGIN:
        abort(404)
    session["email"] = request.args.get("email", "dev@lacole.edu.co")
    session["name"] = "Dev"
    return redirect("/")


@app.get("/logout")
def logout():
    session.clear()
    return redirect("/login")


@app.get("/")
def index():
    if not session.get("email"):
        return redirect("/login")
    return send_from_directory(STATIC_DIR, "panel.html")


@app.get("/static/<path:name>")
def static_files(name):
    if name == "panel.html":
        abort(404)
    return send_from_directory(STATIC_DIR, name, max_age=3600)


@app.get("/healthz")
def healthz():
    return "ok"


# ---------------------------------------------------------------- utilidades
def now():
    return int(time.time())


def thumb_path(cid):
    return os.path.join(THUMB_DIR, os.path.basename(cid) + ".jpg")


def row_content(r):
    tp = thumb_path(r["id"])
    return {
        "thumb": int(os.path.getmtime(tp)) if os.path.isfile(tp) else None,
        "id": r["id"], "title": r["title"], "type": r["type"], "orig_name": r["orig_name"],
        "size": r["size"], "url": r["url"], "duration": r["duration"],
        "start_at": r["start_at"], "end_at": r["end_at"],
        "fallback": bool(r["fallback"]), "interactive": bool(r["interactive"]),
        "targets": json.loads(r["targets"]) if r["targets"] else None, "sort": r["sort"],
    }


def targets_screen(row, screen_id):
    if not row["targets"]:
        return True
    try:
        return screen_id in json.loads(row["targets"])
    except Exception:
        return False


def validate_sleep(v):
    out = []
    for it in (v or []):
        t = str(it.get("time", ""))
        days = [int(d) for d in it.get("days", []) if 1 <= int(d) <= 7]
        if re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", t) and days:
            out.append({"days": sorted(set(days)), "time": t})
    return out


def clean_url(u):
    u = (u or "").strip()
    if not re.match(r"^https?://[^\s]+$", u):
        return None
    return u


# ---------------------------------------------------------------- API de administración
@app.get("/api/admin/state")
@admin_required
def admin_state():
    t = time.time()
    screens = []
    for r in db().execute("SELECT * FROM screens ORDER BY status DESC, name"):
        online = bool(r["last_seen"] and t - r["last_seen"] < ONLINE_SECONDS)
        screens.append({
            "id": r["id"], "name": r["name"], "status": r["status"], "orientation": r["orientation"],
            "sleep": json.loads(r["sleep"]), "online": online, "last_seen": r["last_seen"],
            "ip": r["ip"], "now_id": r["now_id"], "now_title": r["now_title"], "state": r["state"],
            "free_mb": r["free_mb"], "model": r["model"], "android": r["android"], "app": r["app"],
            "cmd": r["cmd"], "app_code": r["app_code"], "owner": bool(r["owner"]) if r["owner"] is not None else None,
            "upd_sha": r["upd_sha"], "lan_ip": r["lan_ip"],
        })
    contents = [row_content(r) for r in db().execute("SELECT * FROM contents ORDER BY sort, created")]
    return jsonify(screens=screens, contents=contents, me=session["email"], now=now(),
                   tz=TZ_NAME, max_upload_mb=MAX_UPLOAD_MB, release=get_release())


@app.post("/api/admin/upload")
@admin_required
def admin_upload():
    name = request.args.get("name", "archivo")
    ext = os.path.splitext(name)[1].lower()
    if ext in VIDEO_EXT:
        kind = "video"
    elif ext in IMAGE_EXT:
        kind = "image"
    else:
        return jsonify(error="Tipo de archivo no permitido"), 400
    fid = uuid.uuid4().hex
    fname = fid + ext
    path = os.path.join(MEDIA_DIR, fname)
    tmp = path + ".part"
    total = 0
    limit = MAX_UPLOAD_MB * 1024 * 1024
    with open(tmp, "wb") as f:
        while True:
            chunk = request.stream.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                f.close()
                os.remove(tmp)
                return jsonify(error=f"El archivo supera {MAX_UPLOAD_MB} MB"), 413
            f.write(chunk)
    if total == 0:
        os.remove(tmp)
        return jsonify(error="Archivo vacío"), 400
    os.replace(tmp, path)
    return jsonify(file=fname, size=total, kind=kind, orig_name=name)


def parse_content(body, existing=None):
    """Valida el cuerpo de crear/editar contenido. Devuelve (campos, error)."""
    ctype = (existing["type"] if existing else body.get("type"))
    if ctype not in ("video", "image", "web"):
        return None, "Tipo inválido"
    title = (body.get("title") or "").strip()
    if not title:
        return None, "Falta el título"
    f = {"title": title[:120], "type": ctype}
    start = body.get("start_at")
    end = body.get("end_at")
    f["start_at"] = int(start) if start else None
    f["end_at"] = int(end) if end else None
    if f["start_at"] and f["end_at"] and f["end_at"] <= f["start_at"]:
        return None, "La fecha final debe ser posterior a la inicial"
    f["fallback"] = 1 if body.get("fallback") else 0
    if f["fallback"]:
        f["start_at"] = f["end_at"] = None
    f["interactive"] = 1 if (ctype == "web" and body.get("interactive")) else 0
    if ctype in ("image", "web"):
        d = int(body.get("duration") or (10 if ctype == "image" else 30))
        f["duration"] = max(3, min(d, 3600))
    else:
        f["duration"] = None
    if ctype == "web":
        u = clean_url(body.get("url") or (existing["url"] if existing else None))
        if not u:
            return None, "La dirección web debe empezar por http:// o https://"
        f["url"] = u
    tg = body.get("targets")
    if tg is None:
        f["targets"] = None
    else:
        ids = {r["id"] for r in db().execute("SELECT id FROM screens")}
        f["targets"] = json.dumps([x for x in tg if x in ids])
    return f, None


@app.post("/api/admin/contents")
@admin_required
def content_create():
    body = request.get_json(force=True, silent=True) or {}
    f, err = parse_content(body)
    if err:
        return jsonify(error=err), 400
    if f["type"] in ("video", "image"):
        fname = os.path.basename(body.get("file") or "")
        path = os.path.join(MEDIA_DIR, fname)
        if not fname or not os.path.isfile(path):
            return jsonify(error="Primero sube el archivo"), 400
        ext = os.path.splitext(fname)[1].lower()
        if (f["type"] == "video") != (ext in VIDEO_EXT):
            return jsonify(error="El archivo no corresponde al tipo"), 400
        f.update(file=fname, orig_name=(body.get("orig_name") or fname)[:200],
                 size=os.path.getsize(path), mtime=int(os.path.getmtime(path)), url=None)
    else:
        f.update(file=None, orig_name=None, size=None, mtime=None)
    cid = uuid.uuid4().hex[:12]
    sort = (db().execute("SELECT COALESCE(MAX(sort),0)+1 FROM contents").fetchone()[0])
    db().execute(
        "INSERT INTO contents (id,title,type,file,orig_name,size,mtime,url,duration,start_at,end_at,fallback,"
        "interactive,targets,sort,created,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (cid, f["title"], f["type"], f["file"], f["orig_name"], f["size"], f["mtime"], f.get("url"), f["duration"],
         f["start_at"], f["end_at"], f["fallback"], f["interactive"], f["targets"], sort, time.time(),
         session["email"]))
    db().commit()
    audit(f"contenido creado: {f['title']}")
    return jsonify(id=cid)


@app.patch("/api/admin/contents/<cid>")
@admin_required
def content_update(cid):
    r = db().execute("SELECT * FROM contents WHERE id=?", (cid,)).fetchone()
    if not r:
        abort(404)
    body = request.get_json(force=True, silent=True) or {}
    f, err = parse_content(body, existing=r)
    if err:
        return jsonify(error=err), 400
    db().execute(
        "UPDATE contents SET title=?, url=COALESCE(?,url), duration=?, start_at=?, end_at=?, fallback=?, "
        "interactive=?, targets=? WHERE id=?",
        (f["title"], f.get("url"), f["duration"], f["start_at"], f["end_at"], f["fallback"],
         f["interactive"], f["targets"], cid))
    db().commit()
    audit(f"contenido editado: {f['title']}")
    return jsonify(ok=True)


def get_release():
    try:
        if os.path.isfile(APK_PATH) and os.path.isfile(RELEASE_PATH):
            with open(RELEASE_PATH) as f:
                return json.load(f)
    except Exception:
        pass
    return None


@app.post("/api/admin/app")
@admin_required
def app_upload():
    """Sube una nueva versión de la app de los TV (archivo .apk)."""
    name = (request.args.get("name") or "app.apk")[:120]
    tmp = APK_PATH + ".part"
    h = hashlib.sha256()
    total = 0
    limit = MAX_APK_MB * 1024 * 1024
    with open(tmp, "wb") as f:
        while True:
            chunk = request.stream.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                f.close()
                os.remove(tmp)
                return jsonify(error=f"El archivo supera {MAX_APK_MB} MB"), 413
            h.update(chunk)
            f.write(chunk)
    ok = False
    try:
        with zipfile.ZipFile(tmp) as z:
            ok = "AndroidManifest.xml" in z.namelist()
    except Exception:
        ok = False
    if not ok:
        try:
            os.remove(tmp)
        except OSError:
            pass
        return jsonify(error="Ese archivo no es una app de Android (.apk) válida"), 400
    os.replace(tmp, APK_PATH)
    rel = {"sha256": h.hexdigest(), "size": total, "orig_name": name, "uploaded": now(), "by": session["email"]}
    with open(RELEASE_PATH, "w") as f:
        json.dump(rel, f)
    audit(f"app subida: {name}")
    return jsonify(rel)


@app.delete("/api/admin/app")
@admin_required
def app_delete():
    for p in (APK_PATH, RELEASE_PATH):
        try:
            os.remove(p)
        except OSError:
            pass
    audit("app retirada")
    return jsonify(ok=True)


@app.get("/api/admin/contents/<cid>/file")
@admin_required
def content_file(cid):
    """Archivo original, solo para el panel (vista previa y miniaturas)."""
    r = db().execute("SELECT * FROM contents WHERE id=?", (cid,)).fetchone()
    if not r or not r["file"]:
        abort(404)
    path = os.path.join(MEDIA_DIR, os.path.basename(r["file"]))
    if not os.path.isfile(path):
        abort(404)
    return send_file(path, conditional=True, max_age=0)


@app.get("/api/admin/contents/<cid>/thumb")
@admin_required
def content_thumb_get(cid):
    p = thumb_path(cid)
    if not os.path.isfile(p):
        abort(404)
    return send_file(p, mimetype="image/jpeg", max_age=86400)


@app.post("/api/admin/contents/<cid>/thumb")
@admin_required
def content_thumb_set(cid):
    if not db().execute("SELECT 1 FROM contents WHERE id=?", (cid,)).fetchone():
        abort(404)
    data = request.get_data(cache=False)
    if not (0 < len(data) <= 600 * 1024) or data[:2] != b"\xff\xd8":
        return jsonify(error="Miniatura inválida"), 400
    tmp = thumb_path(cid) + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, thumb_path(cid))
    return jsonify(ok=True)


@app.post("/api/admin/contents/<cid>/move")
@admin_required
def content_move(cid):
    direction = (request.get_json(force=True, silent=True) or {}).get("dir")
    me = db().execute("SELECT fallback FROM contents WHERE id=?", (cid,)).fetchone()
    if not me:
        abort(404)
    # solo se reordena dentro de su propia lista (contenido normal o de respaldo)
    rows = [r["id"] for r in db().execute(
        "SELECT id FROM contents WHERE fallback=? ORDER BY sort, created", (me["fallback"],))]
    i = rows.index(cid)
    j = i - 1 if direction == "up" else i + 1
    if 0 <= j < len(rows):
        rows[i], rows[j] = rows[j], rows[i]
        for n, x in enumerate(rows):
            db().execute("UPDATE contents SET sort=? WHERE id=?", (n + 1, x))
        db().commit()
    return jsonify(ok=True)


@app.delete("/api/admin/contents/<cid>")
@admin_required
def content_delete(cid):
    r = db().execute("SELECT * FROM contents WHERE id=?", (cid,)).fetchone()
    if not r:
        abort(404)
    db().execute("DELETE FROM contents WHERE id=?", (cid,))
    db().commit()
    try:
        os.remove(thumb_path(cid))
    except OSError:
        pass
    if r["file"]:
        try:
            os.remove(os.path.join(MEDIA_DIR, os.path.basename(r["file"])))
        except OSError:
            pass
    audit(f"contenido eliminado: {r['title']}")
    return jsonify(ok=True)


@app.post("/api/admin/screens/<sid>/approve")
@admin_required
def screen_approve(sid):
    body = request.get_json(force=True, silent=True) or {}
    r = db().execute("SELECT * FROM screens WHERE id=?", (sid,)).fetchone()
    if not r:
        abort(404)
    name = (body.get("name") or r["name"]).strip()[:60]
    ori = int(body.get("orientation", r["orientation"]))
    if ori not in (0, 90, 180, 270):
        ori = 0
    db().execute("UPDATE screens SET status='approved', name=?, orientation=? WHERE id=?", (name, ori, sid))
    db().commit()
    audit(f"pantalla aprobada: {name}")
    return jsonify(ok=True)


@app.patch("/api/admin/screens/<sid>")
@admin_required
def screen_update(sid):
    r = db().execute("SELECT * FROM screens WHERE id=?", (sid,)).fetchone()
    if not r:
        abort(404)
    body = request.get_json(force=True, silent=True) or {}
    name = (body.get("name") or r["name"]).strip()[:60]
    ori = int(body.get("orientation", r["orientation"]))
    if ori not in (0, 90, 180, 270):
        return jsonify(error="Orientación inválida"), 400
    sleep = validate_sleep(body.get("sleep", json.loads(r["sleep"])))
    db().execute("UPDATE screens SET name=?, orientation=?, sleep=? WHERE id=?",
                 (name, ori, json.dumps(sleep), sid))
    db().commit()
    audit(f"pantalla editada: {name}")
    return jsonify(ok=True)


@app.post("/api/admin/screens/<sid>/cmd")
@admin_required
def screen_cmd(sid):
    c = (request.get_json(force=True, silent=True) or {}).get("cmd")
    if c not in ("sleep", "reload"):
        return jsonify(error="Comando inválido"), 400
    if not db().execute("SELECT 1 FROM screens WHERE id=?", (sid,)).fetchone():
        abort(404)
    if c == "reload":
        db().execute("UPDATE screens SET reload=reload+1 WHERE id=?", (sid,))
    else:
        db().execute("UPDATE screens SET cmd='sleep' WHERE id=?", (sid,))
    db().commit()
    audit(f"comando {c} a {sid}")
    return jsonify(ok=True)


@app.delete("/api/admin/screens/<sid>")
@admin_required
def screen_delete(sid):
    db().execute("DELETE FROM screens WHERE id=?", (sid,))
    db().commit()
    audit(f"pantalla eliminada: {sid}")
    return jsonify(ok=True)


@app.get("/api/admin/log")
@admin_required
def get_log():
    rows = db().execute("SELECT * FROM log ORDER BY ts DESC LIMIT 100").fetchall()
    return jsonify([dict(r) for r in rows])


# ---------------------------------------------------------------- API para los TV
SID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def get_screen(sid, create=True):
    if not SID_RE.match(sid):
        abort(400)
    r = db().execute("SELECT * FROM screens WHERE id=?", (sid,)).fetchone()
    if not r and create:
        n = db().execute("SELECT COUNT(*) FROM screens").fetchone()[0]
        db().execute("INSERT INTO screens (id,name,created) VALUES (?,?,?)",
                     (sid, f"Pantalla nueva {sid[:4].upper()}", time.time()))
        db().commit()
        r = db().execute("SELECT * FROM screens WHERE id=?", (sid,)).fetchone()
    return r


def touch(sid, info):
    db().execute(
        "UPDATE screens SET last_seen=?, ip=?, now_id=?, now_title=?, state=?, free_mb=?, model=?, android=?, app=?, "
        "app_code=?, owner=?, upd_sha=?, lan_ip=COALESCE(?, lan_ip) WHERE id=?",
        (time.time(), request.headers.get("X-Forwarded-For", request.remote_addr),
         info.get("now_id"), (info.get("now_title") or "")[:120], (info.get("state") or "")[:200],
         info.get("free_mb"), (info.get("model") or "")[:60], (info.get("android") or "")[:20],
         (info.get("app") or "")[:20],
         info.get("app_code") if isinstance(info.get("app_code"), int) else None,
         (1 if info.get("owner") else 0) if "owner" in info else None,
         (info.get("upd_sha") or "")[:64] or None, (info.get("lan_ip") or "")[:45] or None, sid))
    db().commit()


def take_cmd(sid):
    r = db().execute("SELECT cmd FROM screens WHERE id=?", (sid,)).fetchone()
    if r and r["cmd"]:
        db().execute("UPDATE screens SET cmd=NULL WHERE id=?", (sid,))
        db().commit()
        return r["cmd"]
    return None


@app.post("/tv/<sid>/ping")
def tv_ping(sid):
    scr = get_screen(sid)
    info = request.get_json(force=True, silent=True) or {}
    touch(sid, info)
    cmd = take_cmd(sid) if scr["status"] == "approved" else None
    return jsonify(status=scr["status"], cmd=cmd, server_time=int(time.time() * 1000))


@app.after_request
def _cors_tv(resp):
    """La app de webOS (LG) es una página web local: necesita CORS para hablar con /tv/*."""
    if request.path.startswith("/tv/"):
        resp.headers["Access-Control-Allow-Origin"] = "*"
        resp.headers["Access-Control-Allow-Headers"] = "Content-Type, Range"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        resp.headers["Access-Control-Expose-Headers"] = "Content-Range, Content-Length, Accept-Ranges, Content-Type"
    return resp


@app.route("/tv/<sid>/ping", methods=["OPTIONS"])
def tv_ping_options(sid):
    return ("", 204)


@app.get("/tv/<sid>/manifest")
def tv_manifest(sid):
    scr = get_screen(sid)
    if scr["status"] != "approved":
        return jsonify(status="pending", name=scr["name"], server_time=int(time.time() * 1000))
    t = now()
    items = []
    for r in db().execute("SELECT * FROM contents ORDER BY sort, created"):
        if not targets_screen(r, sid):
            continue
        if not r["fallback"]:
            if r["start_at"] and r["start_at"] > t + PRELOAD_SECONDS:
                continue
            if r["end_at"] and r["end_at"] < t - GRACE_SECONDS:
                continue
        it = {"id": r["id"], "type": r["type"], "title": r["title"], "fallback": bool(r["fallback"]),
              "start": r["start_at"], "end": r["end_at"], "duration": r["duration"],
              "interactive": bool(r["interactive"])}
        if r["type"] == "web":
            it["url"] = r["url"]
        else:
            it["url"] = f"{BASE_URL}/tv/{sid}/media/{r['id']}"
            it["size"] = r["size"]
            it["stamp"] = f"{r['size']}-{r['mtime']}"
            it["ext"] = os.path.splitext(r["file"])[1]
        items.append(it)
    rel = get_release()
    upd = None
    if rel:
        upd = {"sha256": rel["sha256"], "size": rel["size"], "url": f"{BASE_URL}/tv/{sid}/app.apk"}
    return jsonify(status="approved", name=scr["name"], orientation=scr["orientation"],
                   reload=scr["reload"], sleep=json.loads(scr["sleep"]), tz=TZ_NAME,
                   server_time=int(time.time() * 1000), items=items, app_update=upd)


@app.get("/tv/<sid>/app.apk")
def tv_app(sid):
    scr = get_screen(sid, create=False)
    if not scr or scr["status"] != "approved":
        abort(403)
    if not get_release():
        abort(404)
    return send_file(APK_PATH, conditional=True, max_age=0, mimetype="application/vnd.android.package-archive",
                     download_name="pantallas.apk")


@app.get("/tv/<sid>/media/<cid>")
def tv_media(sid, cid):
    scr = get_screen(sid, create=False)
    if not scr or scr["status"] != "approved":
        abort(403)
    r = db().execute("SELECT * FROM contents WHERE id=?", (cid,)).fetchone()
    if not r or not r["file"] or not targets_screen(r, sid):
        abort(404)
    path = os.path.join(MEDIA_DIR, os.path.basename(r["file"]))
    if not os.path.isfile(path):
        abort(404)
    return send_file(path, conditional=True, max_age=0, download_name=r["orig_name"] or r["file"])


PLAYER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "webos_player")


@app.get("/player")
def player_redirect():
    return redirect("/player/")


@app.get("/player/")
@app.get("/player/<path:name>")
def player(name="index.html"):
    """Reproductor web (el mismo de la app de webOS) para abrirlo desde el navegador de cualquier TV."""
    if name in ("appinfo.json", "README.md") or not os.path.isdir(PLAYER_DIR):
        abort(404)
    resp = send_from_directory(PLAYER_DIR, name, max_age=0)
    resp.headers["Cache-Control"] = "no-store"
    return resp


@app.get("/tv/<sid>/embed/<cid>")
def tv_embed(sid, cid):
    """Para la app de webOS: muestra una página web dentro de un marco aunque el sitio lo prohíba.
    Si el sitio permite el marco, redirige directo; si no, entrega una copia sin esa restricción."""
    scr = get_screen(sid, create=False)
    if not scr or scr["status"] != "approved":
        abort(403)
    r = db().execute("SELECT * FROM contents WHERE id=? AND type='web'", (cid,)).fetchone()
    if not r or not r["url"] or not targets_screen(r, sid):
        abort(404)
    url = r["url"]
    try:
        up = http.get(url, timeout=10, stream=True, headers={
            "User-Agent": "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml"})
    except Exception:
        return redirect(url)
    try:
        h = up.headers
        blocked = "x-frame-options" in h or "frame-ancestors" in h.get("content-security-policy", "").lower()
        ctype = h.get("content-type", "")
        if not blocked or "text/html" not in ctype.lower():
            return redirect(url)
        body = up.raw.read(6 * 1024 * 1024 + 1, decode_content=True)
        if len(body) > 6 * 1024 * 1024:
            return redirect(url)
        enc = up.encoding or "utf-8"
        text = body.decode(enc, errors="replace")
        base = '<base href="%s">' % up.url.replace('"', "%22")
        m = re.search(r"<head[^>]*>", text, re.I)
        text = (text[:m.end()] + base + text[m.end():]) if m else (base + text)
        text = re.sub(r"<meta[^>]+http-equiv=[\"']?content-security-policy[^>]*>", "", text, flags=re.I)
        return Response(text, mimetype="text/html")
    finally:
        up.close()


# ---------------------------------------------------------------- página de inicio de sesión
LOGIN_HTML = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Pantallas La Cole</title>
<link rel="icon" href="/static/icon.png"><link rel="stylesheet" href="/static/fonts.css">
<style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:28px;background:#800cab;
font-family:'Plus Jakarta Sans',system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#222}
.card{background:#fff;border-radius:18px;padding:36px 34px 32px;width:min(92vw,400px);text-align:center;
box-shadow:0 20px 60px rgba(0,0,0,.3)}
.logo{width:min(70vw,300px)}
.logo img{width:100%;display:block}
h1{font-size:22px;margin:0 0 6px;font-weight:800;letter-spacing:-.01em}p{color:#6d6877;margin:0 0 26px;font-size:14px}
a.btn{display:flex;align-items:center;justify-content:center;gap:10px;padding:12px 16px;border:1px solid #ddd;
border-radius:10px;text-decoration:none;color:#222;font-weight:600;font-size:15px}
a.btn:hover{background:#f6f0fa;border-color:#800cab}
.err{color:#b3261e;background:#fdecea;border-radius:8px;padding:10px;margin-bottom:16px;font-size:14px}
.dev{margin-top:14px;font-size:12px}
</style></head><body>
<div class="logo"><img src="/static/logo_white.png" alt="Colegiatura Antioqueña de Belleza"></div>
<div class="card">
<h1>Pantallas La Cole</h1><p>Panel de administración</p>
<div class="err" style="display:__MSGDISP__">__MSG__</div>
<a class="btn" href="/auth/google"><svg width="18" height="18" viewBox="0 0 48 48"><path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9.1 3.6l6.8-6.8C35.8 2.4 30.3 0 24 0 14.6 0 6.5 5.4 2.6 13.2l7.9 6.1C12.4 13.6 17.7 9.5 24 9.5z"/><path fill="#4285F4" d="M46.1 24.5c0-1.6-.1-3.1-.4-4.5H24v9h12.4c-.5 2.9-2.2 5.3-4.6 6.9l7.1 5.5c4.3-4 6.7-9.9 6.7-16.9z"/><path fill="#FBBC05" d="M10.5 28.7c-.5-1.4-.8-2.9-.8-4.7s.3-3.3.8-4.7l-7.9-6.1C.9 16.4 0 20.1 0 24s.9 7.6 2.6 10.8l7.9-6.1z"/><path fill="#34A853" d="M24 48c6.3 0 11.7-2.1 15.6-5.7l-7.1-5.5c-2 1.4-4.8 2.3-8.5 2.3-6.3 0-11.6-4.1-13.5-9.8l-7.9 6.1C6.5 42.6 14.6 48 24 48z"/></svg>Ingresar con Google</a>
<div class="dev" style="display:__DEVDISP__"><a href="/dev-login">Entrada de desarrollo</a></div>
</div></body></html>""".replace("__MSGDISP__", "__MD__").replace("__DEVDISP__", "__DD__")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8000")), threaded=True)
