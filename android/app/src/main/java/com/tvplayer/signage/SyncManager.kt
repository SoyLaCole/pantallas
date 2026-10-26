package com.tvplayer.signage

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Habla con el servidor: pide la programacion de este TV, descarga lo que falta (reanudando si
 * se corta), borra lo que ya no va y avisa a la pantalla. Lo descargado queda guardado en el TV:
 * si se cae la red se sigue reproduciendo lo ultimo recibido.
 */
class SyncManager(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onUpdate(m: Manifest?, files: Map<String, File>)
        fun onStatus(text: String)
        fun onCommand(cmd: String)
        fun onReload()
    }

    private val main = Handler(Looper.getMainLooper())
    private val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
    private val stateFile = File(ctx.filesDir, "state2.json")
    private val manifestFile = File(ctx.filesDir, "manifest.json")

    @Volatile private var running = false
    @Volatile var nowId: String = ""
    @Volatile var nowTitle: String = ""
    @Volatile var note: String = ""
    @Volatile private var statusText: String = ""
    @Volatile private var manifest: Manifest? = null
    @Volatile private var stamps: Map<String, String> = emptyMap()
    private var syncThread: Thread? = null
    private var beatThread: Thread? = null
    private var lastKey: String? = null

    fun start() {
        if (running) return
        running = true
        Clock.offset = Prefs.clockOffset(ctx)
        cleanupLegacy()
        loadCached()
        syncThread = Thread { syncLoop() }.apply { isDaemon = true; start() }
        beatThread = Thread { beatLoop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        syncThread?.interrupt()
        beatThread?.interrupt()
    }

    // ------------------------------------------------------------ estado guardado

    /** La version 1 guardaba los videos en otra carpeta: se libera ese espacio. */
    private fun cleanupLegacy() {
        try {
            File(ctx.filesDir, "videos").deleteRecursively()
            File(ctx.filesDir, "state.json").delete()
        } catch (e: Exception) {
        }
    }

    private fun loadCached() {
        try {
            if (manifestFile.isFile) manifest = Manifest.parse(manifestFile.readText())
            val o = JSONObject(if (stateFile.isFile) stateFile.readText() else "{}")
            val m = HashMap<String, String>()
            for (k in o.keys()) m[k] = o.getString(k)
            stamps = m
        } catch (e: Exception) {
            manifest = null
            stamps = emptyMap()
        }
        publish()
    }

    private fun writeAtomic(dest: File, text: String) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(text)
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    private fun saveStamps(m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        writeAtomic(stateFile, o.toString())
        stamps = HashMap(m)
    }

    private fun readyFiles(): Map<String, File> {
        val m = manifest ?: return emptyMap()
        val out = HashMap<String, File>()
        for (it in m.items) {
            if (!it.isMedia) continue
            val f = File(mediaDir, it.fileName)
            if (f.isFile && stamps[it.id] == it.stamp) out[it.id] = f
        }
        return out
    }

    private fun publish() {
        val files = readyFiles()
        val m = manifest
        val key = (m?.status ?: "-") + "|" + (m?.name ?: "") + "|" + (m?.orientation ?: 0) + "|" +
                (m?.items?.joinToString(",") { it.id + ":" + it.stamp + ":" + it.start + ":" + it.end + ":" + it.duration + ":" + it.url + ":" + it.fallback + ":" + it.interactive } ?: "") +
                "|" + (m?.sleep?.joinToString(",") { it.days.joinToString("") + it.time } ?: "") +
                "|" + files.keys.sorted().joinToString(",")
        if (key == lastKey) return
        lastKey = key
        main.post { listener.onUpdate(m, files) }
    }

    private fun setStatus(t: String) {
        statusText = t
        main.post { listener.onStatus(t) }
    }

    // ------------------------------------------------------------ bucles

    private fun syncLoop() {
        while (running) {
            try {
                syncOnce(Prefs.serverUrl(ctx))
                Thread.sleep(20000)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                setStatus("Sin conexión con el servidor.")
                try {
                    Thread.sleep(10000)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun freeMb(): Long = mediaDir.usableSpace / (1024 * 1024)

    /** Avisa al servidor cada 15 s (aunque haya una descarga larga en curso). */
    private fun beatLoop() {
        while (running) {
            try {
                Thread.sleep(15000)
                ping()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // sin red: se intentara de nuevo
            }
        }
    }

    private fun ping() {
        val j = JSONObject()
        j.put("model", Build.MODEL ?: "TV")
        j.put("android", Build.VERSION.RELEASE ?: "")
        j.put("app", Prefs.APP_VERSION)
        j.put("app_code", Updater.installedCode(ctx))
        j.put("owner", Updater.isOwner(ctx))
        j.put("upd_sha", Prefs.updSha(ctx))
        j.put("free_mb", freeMb())
        j.put("now_id", nowId)
        j.put("now_title", nowTitle)
        j.put("state", if (note.isNotEmpty()) note else if (statusText.isNotEmpty()) statusText else Prefs.updNote(ctx))
        val c = Net.open(ctx, Prefs.serverUrl(ctx) + "/tv/" + Prefs.tvId(ctx) + "/ping")
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            val r = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (r.has("server_time")) updateClock(r.getLong("server_time"))
            val cmd = r.optString("cmd", "")
            if (cmd.isNotEmpty() && cmd != "null") main.post { listener.onCommand(cmd) }
        } finally {
            c.disconnect()
        }
    }

    private fun updateClock(serverMs: Long) {
        if (serverMs > 0) {
            Clock.setServer(serverMs)
            Prefs.setClockOffset(ctx, Clock.offset)
        }
    }

    // ------------------------------------------------------------ sincronizacion

    private fun syncOnce(base: String) {
        val id = Prefs.tvId(ctx)
        val body = httpGet("$base/tv/$id/manifest")
        val m = Manifest.parse(body)
        updateClock(m.serverTime)

        if (m.status != "approved") {
            manifest = m
            setStatus("Pendiente de aprobación")
            publish()
            return
        }
        writeAtomic(manifestFile, body)
        manifest = m
        setStatus("")

        // Orden de "reiniciar" desde el panel
        val lastReload = Prefs.lastReload(ctx)
        Prefs.setLastReload(ctx, m.reload)
        if (lastReload != -1 && lastReload != m.reload) main.post { listener.onReload() }

        val st = HashMap(stamps)
        val keep = HashSet<String>()
        for (it in m.items) {
            if (it.isMedia) {
                keep.add(it.fileName)
                keep.add(it.partName)
            }
        }

        // Borra lo que ya no esta en la programacion. Proteccion: si la lista llega vacia, no borra
        // nada hasta que lleve 6 horas vacia (evita perderlo todo por un error del servidor).
        val local = mediaDir.listFiles()
        var allowDelete = true
        if (m.items.isEmpty() && local != null && local.isNotEmpty()) {
            val since = Prefs.emptySince(ctx)
            if (since == 0L) {
                Prefs.setEmptySince(ctx, System.currentTimeMillis())
                allowDelete = false
            } else if (System.currentTimeMillis() - since < 6L * 3600 * 1000) {
                allowDelete = false
            }
        } else {
            Prefs.setEmptySince(ctx, 0L)
        }
        if (allowDelete) {
            local?.forEach { f -> if (f.name !in keep) f.delete() }
            st.keys.retainAll(m.items.map { it.id }.toSet())
            saveStamps(st)
        }
        publish()

        // Descarga lo que falta o cambio (primero lo que esta activo ahora)
        val nowSec = Clock.now() / 1000
        val pending = m.items.filter { it.isMedia && !(File(mediaDir, it.fileName).isFile && st[it.id] == it.stamp) }
            .sortedBy { if (it.activeAt(nowSec)) 0 else 1 }
        for (item in pending) {
            if (!running) return
            val dest = File(mediaDir, item.fileName)
            if (download(item, dest)) {
                st[item.id] = item.stamp
                saveStamps(st)
                publish()
            }
        }
        publish()

        // Actualizacion de la propia app (si hay una version nueva publicada en el panel)
        Updater.check(ctx, m.appUpdate, { running }) { setStatus(it) }
    }

    private fun download(item: Item, dest: File): Boolean {
        val part = File(mediaDir, item.partName)
        var have = if (part.exists()) part.length() else 0L
        if (have > item.size) {
            part.delete()
            have = 0L
        }
        if (mediaDir.usableSpace < (item.size - have) + 50L * 1024 * 1024) {
            setStatus("Sin espacio en el TV para " + item.title)
            return false
        }
        val conn = Net.open(ctx, item.url)
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            if (!append) have = 0L
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    var lastReport = 0L
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 1000) {
                            lastReport = now
                            val pct = if (item.size > 0) (done * 100 / item.size).toInt() else 0
                            setStatus("Descargando " + item.title + " " + pct + "%")
                        }
                    }
                }
            }
            if (part.length() != item.size) return false
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) return false
            setStatus("")
            return true
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ red

    private fun httpGet(url: String): String {
        val c = Net.open(ctx, url)
        c.connectTimeout = 6000
        c.readTimeout = 12000
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
