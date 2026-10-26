package com.tvplayer.signage

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Pantalla unica de la app. Muestra, a pantalla completa y sin controles, el contenido que el
 * servidor programo para este TV (videos, imagenes y paginas web), en el orden y las fechas
 * indicadas, girado si la pantalla esta en vertical.
 *
 * Control remoto: OK = informacion; MENU o mantener ATRAS 3 s = menu de mantenimiento.
 * En una pagina web "interactiva" las flechas y OK actuan sobre la pagina.
 */
class MainActivity : Activity(), SyncManager.Listener {

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var waitingView: LinearLayout
    private lateinit var waitingText: TextView
    private lateinit var infoView: TextView
    private var playerView: PlayerView? = null
    private var webView: WebView? = null
    private var player: ExoPlayer? = null
    private var sync: SyncManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private var manifest: Manifest? = null
    private var files: Map<String, File> = emptyMap()
    private var syncStatus = ""

    private var current: Item? = null
    private var currentView: View? = null
    private var currentSince = 0L
    private var token = 0
    private var videoReady = false
    private var listSize = 0
    private val failedUntil = HashMap<String, Long>()
    private var lastInteraction = 0L

    private var rotationDeg = 0
    private var textureMode = false
    private var geoW = -1
    private var geoH = -1
    private var geoRot = -1

    private var infoUntil = 0L
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
                // nunca debe detenerse la pantalla
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val menuRunnable = Runnable { showMenu() }

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        stage = FrameLayout(this)
        stage.setBackgroundColor(Color.BLACK)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))

        imageView = ImageView(this)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setBackgroundColor(Color.BLACK)
        imageView.visibility = View.GONE
        stage.addView(imageView, FrameLayout.LayoutParams(-1, -1))

        // Pantalla de espera (logo sobre morado)
        waitingView = LinearLayout(this)
        waitingView.orientation = LinearLayout.VERTICAL
        waitingView.gravity = Gravity.CENTER
        waitingView.setBackgroundColor(Color.parseColor("#800cab"))
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        waitingView.addView(logo, LinearLayout.LayoutParams(520, ViewGroup.LayoutParams.WRAP_CONTENT))
        waitingText = TextView(this)
        waitingText.setTextColor(Color.WHITE)
        waitingText.textSize = 24f
        waitingText.gravity = Gravity.CENTER
        waitingText.setPadding(60, 50, 60, 0)
        waitingView.addView(waitingText, LinearLayout.LayoutParams(-1, -2))
        stage.addView(waitingView, FrameLayout.LayoutParams(-1, -1))

        infoView = TextView(this)
        infoView.setTextColor(Color.WHITE)
        infoView.textSize = 22f
        infoView.setPadding(50, 30, 50, 30)
        infoView.setBackgroundColor(Color.argb(200, 0, 0, 0))
        infoView.visibility = View.GONE
        stage.addView(infoView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        hideSystemUi()

        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w != geoW || h != geoH) applyGeometry()
        }
    }

    override fun onStart() {
        super.onStart()
        val s = SyncManager(applicationContext, this)
        sync = s
        s.start()
        handler.removeCallbacks(tick)
        handler.post(tick)
        updateWaiting()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        sync?.stop()
        sync = null
        releasePlayer()
        webView?.loadUrl("about:blank")
        webView?.onPause()
        current = null
        currentView = null
        hideAllMedia()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // ------------------------------------------------------------ geometria (pantallas verticales)

    private fun applyGeometry() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        if (w == geoW && h == geoH && rotationDeg == geoRot) return
        geoW = w
        geoH = h
        geoRot = rotationDeg
        val lp = if (rotationDeg == 90 || rotationDeg == 270)
            FrameLayout.LayoutParams(h, w, Gravity.CENTER)
        else
            FrameLayout.LayoutParams(-1, -1)
        stage.layoutParams = lp
        stage.rotation = rotationDeg.toFloat()
        // Con giro hace falta TextureView (SurfaceView no gira bien en Android viejo).
        if (playerView != null && textureMode != (rotationDeg != 0)) {
            if (current?.type == "video") current = null
            buildPlayer()
        }
    }

    // ------------------------------------------------------------ reproductor de video

    private fun buildPlayer() {
        releasePlayer()
        playerView?.let { stage.removeView(it) }
        val tex = rotationDeg != 0
        val layout = if (tex) R.layout.player_texture else R.layout.player_surface
        val pv = layoutInflater.inflate(layout, stage, false) as PlayerView
        pv.visibility = View.GONE
        stage.addView(pv, 0)
        playerView = pv
        textureMode = tex
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) videoReady = true
                if (state == Player.STATE_ENDED) {
                    val cur = current
                    if (cur != null && cur.type == "video") advance(playlist())
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = current
                if (cur != null) fail(cur)
            }
        })
        pv.player = p
        player = p
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
    }

    // ------------------------------------------------------------ lista de reproduccion

    private fun playlist(): List<Item> {
        val m = manifest ?: return emptyList()
        if (m.status != "approved") return emptyList()
        val nowSec = Clock.now() / 1000
        val t = SystemClock.elapsedRealtime()
        fun ok(i: Item): Boolean =
            (!i.isMedia || files.containsKey(i.id)) && (failedUntil[i.id] ?: 0L) <= t
        val regular = m.items.filter { !it.fallback && it.activeAt(nowSec) && ok(it) }
        return if (regular.isNotEmpty()) regular else m.items.filter { it.fallback && ok(it) }
    }

    private fun onTick() {
        tickCount++
        val list = playlist()
        listSize = list.size
        if (list.isEmpty()) {
            if (current != null) {
                current = null
                reveal(null)
            }
            updateWaiting()
            return
        }
        val cur = current
        if (cur == null || list.none { it.id == cur.id }) {
            showItem(nextAfter(list, cur))
        } else {
            val elapsed = SystemClock.elapsedRealtime() - currentSince
            if (cur.type == "video") {
                if (!videoReady && elapsed > 25000) fail(cur)
            } else {
                val interacting = cur.interactive &&
                        (SystemClock.elapsedRealtime() - lastInteraction) < 60000
                if (list.size > 1 && elapsed >= cur.duration * 1000L && !interacting) {
                    advance(list)
                } else if (list.size == 1 && cur.type == "web" && elapsed >= 30L * 60 * 1000) {
                    currentSince = SystemClock.elapsedRealtime()
                    webView?.reload()
                }
            }
        }
        if (tickCount % 15 == 0) checkSleep()
        refreshInfo()
    }

    private fun nextAfter(list: List<Item>, cur: Item?): Item {
        if (cur == null) return list[0]
        val idx = list.indexOfFirst { it.id == cur.id }
        return list[(idx + 1) % list.size]
    }

    private fun advance(list: List<Item>) {
        if (list.isEmpty()) return
        showItem(nextAfter(list, current))
    }

    private fun fail(item: Item) {
        failedUntil[item.id] = SystemClock.elapsedRealtime() + 60000
        if (current?.id == item.id) current = null
    }

    private fun showItem(item: Item) {
        current = item
        currentSince = SystemClock.elapsedRealtime()
        token++
        videoReady = false
        sync?.nowId = item.id
        sync?.nowTitle = item.title
        val myToken = token
        when (item.type) {
            "video" -> {
                val f = files[item.id]
                if (f == null) {
                    fail(item)
                    return
                }
                if (playerView == null || player == null || textureMode != (rotationDeg != 0)) buildPlayer()
                val p = player ?: return
                p.repeatMode = if (listSize <= 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
                p.prepare()
                p.playWhenReady = true
                reveal(playerView)
            }
            "image" -> {
                val f = files[item.id]
                if (f == null) {
                    fail(item)
                    return
                }
                Thread {
                    val bmp = decodeImage(f)
                    handler.post {
                        if (myToken != token) {
                            bmp?.recycle()
                        } else if (bmp == null) {
                            fail(item)
                        } else {
                            imageView.setImageBitmap(bmp)
                            reveal(imageView)
                        }
                    }
                }.start()
            }
            "web" -> {
                try {
                    val w = ensureWeb()
                    w.onResume()
                    w.loadUrl(item.url)
                    reveal(w)
                } catch (e: Exception) {
                    fail(item)
                }
            }
            else -> fail(item)
        }
    }

    private fun decodeImage(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(f.path, o2)
        } catch (e: Throwable) {
            null
        }
    }

    private fun ensureWeb(): WebView {
        webView?.let { return it }
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failCurrentWeb()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl != null && failingUrl == current?.url) failCurrentWeb()
            }
        }
        w.visibility = View.GONE
        stage.addView(w, FrameLayout.LayoutParams(-1, -1))
        webView = w
        return w
    }

    private fun failCurrentWeb() {
        val cur = current
        if (cur != null && cur.type == "web") fail(cur)
    }

    // ------------------------------------------------------------ cambio de capa visible

    private fun hideAllMedia() {
        playerView?.visibility = View.GONE
        imageView.visibility = View.GONE
        imageView.setImageDrawable(null)
        webView?.visibility = View.GONE
    }

    /** Muestra la capa indicada (con fundido para imagen y web) y esconde las demas. */
    private fun reveal(target: View?) {
        val layers = listOfNotNull(playerView, imageView, webView)
        currentView = target
        for (v in layers) {
            if (v === target) continue
            if (v.visibility == View.VISIBLE) {
                v.postDelayed({
                    if (currentView !== v) {
                        v.visibility = View.GONE
                        if (v === playerView) {
                            player?.stop()
                            player?.clearMediaItems()
                        }
                        if (v === imageView) imageView.setImageDrawable(null)
                        if (v === webView) webView?.loadUrl("about:blank")
                    }
                }, 450)
            }
        }
        if (target == null) {
            updateWaiting()
            waitingView.visibility = View.VISIBLE
            waitingView.bringToFront()
        } else {
            if (target === playerView) {
                target.alpha = 1f
                target.visibility = View.VISIBLE
            } else {
                target.alpha = 0f
                target.visibility = View.VISIBLE
                target.bringToFront()
                target.animate().alpha(1f).setDuration(400).start()
            }
            waitingView.visibility = View.GONE
        }
        infoView.bringToFront()
    }

    // ------------------------------------------------------------ textos en pantalla

    private fun code(): String = Prefs.tvId(this).take(8).uppercase()

    private fun updateWaiting() {
        val m = manifest
        val t = when {
            m != null && m.status != "approved" ->
                "Pantalla pendiente de aprobación\nCódigo: " + code()
            m == null && syncStatus.isNotEmpty() -> syncStatus + "\nCódigo: " + code()
            m == null -> "Conectando…\nCódigo: " + code()
            syncStatus.isNotEmpty() -> syncStatus
            else -> "Sin contenido programado"
        }
        waitingText.text = t
    }

    private fun refreshInfo() {
        if (System.currentTimeMillis() < infoUntil) {
            val m = manifest
            val sb = StringBuilder()
            sb.append("Pantalla: ").append(m?.name ?: "?").append("  (").append(code()).append(")\n")
            sb.append("Servidor: ").append(Prefs.serverUrl(this)).append("\n")
            sb.append("Reproduciendo: ").append(current?.title ?: "—")
            if (syncStatus.isNotEmpty()) sb.append("\n").append(syncStatus)
            infoView.text = sb.toString()
            infoView.visibility = View.VISIBLE
            infoView.bringToFront()
        } else {
            infoView.visibility = View.GONE
        }
    }

    private fun showInfo() {
        infoUntil = System.currentTimeMillis() + 6000
        refreshInfo()
    }

    // ------------------------------------------------------------ SyncManager.Listener

    override fun onUpdate(m: Manifest?, files: Map<String, File>) {
        manifest = m
        this.files = files
        if (m != null && m.status == "approved" && m.orientation != rotationDeg) {
            rotationDeg = m.orientation
            applyGeometry()
        }
        updateWaiting()
    }

    override fun onStatus(text: String) {
        syncStatus = text
        updateWaiting()
    }

    override fun onCommand(cmd: String) {
        if (cmd == "sleep") doSleep()
    }

    override fun onReload() {
        restartContent()
    }

    private fun restartContent() {
        current = null
        failedUntil.clear()
        releasePlayer()
        playerView?.let { stage.removeView(it) }
        playerView = null
        reveal(null)
    }

    // ------------------------------------------------------------ apagado

    private fun doSleep() {
        val r = PowerControl.sleepNow(this)
        sync?.note = if (r == "ok") "" else "Apagado: $r"
    }

    /** Apaga el TV a la hora programada (zona horaria que indica el servidor). */
    private fun checkSleep() {
        val m = manifest ?: return
        if (m.status != "approved" || m.sleep.isEmpty()) return
        val cal = Calendar.getInstance(TimeZone.getTimeZone(m.tz))
        cal.timeInMillis = Clock.now()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val iso = if (dow == Calendar.SUNDAY) 7 else dow - 1
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (rule in m.sleep) {
            if (iso !in rule.days) continue
            val parts = rule.time.split(":")
            if (parts.size != 2) continue
            val rm = (parts[0].toIntOrNull() ?: continue) * 60 + (parts[1].toIntOrNull() ?: continue)
            if (minutes >= rm && minutes < rm + 3) {
                val key = "" + cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.DAY_OF_YEAR) + "-" + rule.time
                if (key != Prefs.lastSleepKey(this)) {
                    Prefs.setLastSleepKey(this, key)
                    doSleep()
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------ control remoto

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) handler.postDelayed(menuRunnable, 3000)
            } else if (e.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(menuRunnable)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        val cur = current
        val w = webView
        if (cur != null && cur.type == "web" && cur.interactive && w != null && w.visibility == View.VISIBLE &&
            (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                    (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9))
        ) {
            lastInteraction = SystemClock.elapsedRealtime()
            w.requestFocus()
            return w.dispatchKeyEvent(e)
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (e.action == KeyEvent.ACTION_UP) showInfo()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Modo quiosco: el boton Atras no cierra la app.
    }

    // ------------------------------------------------------------ menu de mantenimiento

    private fun showMenu() {
        val items = arrayOf(
            "Configurar servidor",
            "Reiniciar contenido",
            "Probar apagado de pantalla",
            "Activar permiso de apagado",
            "Abrir ajustes del TV",
            "Quitar actualización automática",
            "Salir de la app"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pantallas La Cole " + Prefs.APP_VERSION + "  ·  " + code())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showServerDialog()
                    1 -> restartContent()
                    2 -> testSleep()
                    3 -> requestAdmin()
                    4 -> openTvSettings()
                    5 -> releaseOwner()
                    6 -> finish()
                    else -> {}
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun testSleep() {
        val r = PowerControl.sleepNow(this)
        if (r != "ok") Toast.makeText(this, r, Toast.LENGTH_LONG).show()
    }

    private fun requestAdmin() {
        try {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, PowerControl.component(this))
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Este TV no permite activarlo desde aquí", Toast.LENGTH_LONG).show()
        }
    }

    /** Deja de ser "propietaria del dispositivo" (solo hace falta antes de desinstalar la app). */
    @Suppress("DEPRECATION")
    private fun releaseOwner() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.clearDeviceOwnerApp(packageName)
                Toast.makeText(this, "Listo: ya no se actualiza sola en silencio", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Este TV no tiene activada la actualización automática", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun openTvSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir los ajustes", Toast.LENGTH_LONG).show()
        }
    }

    private fun showServerDialog() {
        val input = EditText(this)
        input.setText(Prefs.serverUrl(this))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setSingleLine(true)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Dirección del servidor")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                var v = input.text.toString().trim()
                if (v.isEmpty()) {
                    Prefs.setServerUrl(this, null)
                } else {
                    if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
                    Prefs.setServerUrl(this, v.trimEnd('/'))
                }
                restartSync()
            }
            .setNeutralButton("Valor original") { _, _ ->
                Prefs.setServerUrl(this, null)
                restartSync()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun restartSync() {
        sync?.stop()
        val s = SyncManager(applicationContext, this)
        sync = s
        s.start()
    }
}
