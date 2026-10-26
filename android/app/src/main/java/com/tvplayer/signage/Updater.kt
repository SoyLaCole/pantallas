package com.tvplayer.signage

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Actualizacion automatica de la propia app: descarga el APK publicado en el panel, comprueba que
 * llego completo y que es una version mas nueva, y lo instala. Si la app es "propietaria del
 * dispositivo" (se activa una vez con adb) la instalacion es silenciosa; si no, Android pide
 * confirmarla en pantalla con el control remoto.
 */
object Updater {
    private const val RETRY_MS = 30L * 60 * 1000

    fun isOwner(ctx: Context): Boolean = try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(ctx.packageName)
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    /** Se llama desde el hilo de sincronizacion. No lanza excepciones. */
    fun check(ctx: Context, u: AppUpdate?, running: () -> Boolean, onStatus: (String) -> Unit) {
        if (u == null) return
        try {
            doCheck(ctx, u, running, onStatus)
        } catch (e: IOException) {
            // sin red o descarga cortada: se reanuda en la siguiente vuelta
        } catch (e: Exception) {
            Prefs.setUpdNote(ctx, "Actualización: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    @Suppress("DEPRECATION")
    private fun doCheck(ctx: Context, u: AppUpdate, running: () -> Boolean, onStatus: (String) -> Unit) {
        val installed = installedCode(ctx)

        // Ya se instalo la version que quedo pendiente (la app acaba de reiniciarse con ella).
        if (Prefs.pendingSha(ctx) == u.sha256 && installed >= Prefs.pendingCode(ctx)) {
            Prefs.setUpdSha(ctx, u.sha256)
            Prefs.setPending(ctx, "", 0)
            Prefs.setUpdNote(ctx, "")
            File(ctx.filesDir, "update").deleteRecursively()
            return
        }
        if (Prefs.updSha(ctx) == u.sha256) return   // esta version ya se reviso

        val dir = File(ctx.filesDir, "update").apply { mkdirs() }
        val apk = File(dir, "pantallas-" + u.sha256.take(12) + ".apk")
        val part = File(dir, apk.name + ".part")
        dir.listFiles()?.forEach { if (it.name != apk.name && it.name != part.name) it.delete() }

        if (!apk.isFile || sha256(apk) != u.sha256) {
            apk.delete()
            if (dir.usableSpace < u.size * 2 + 50L * 1024 * 1024) {
                Prefs.setUpdNote(ctx, "Sin espacio en el TV para actualizar la app")
                return
            }
            if (!download(ctx, u, part, running, onStatus)) return
            if (sha256(part) != u.sha256) {
                part.delete()
                Prefs.setUpdNote(ctx, "La actualización llegó dañada; se reintentará")
                return
            }
            part.renameTo(apk)
        }

        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        when {
            info == null -> return handled(ctx, u, apk, "La versión publicada no es un APK válido")
            info.packageName != ctx.packageName ->
                return handled(ctx, u, apk, "La versión publicada no es la app de pantallas")
            info.versionCode <= installed ->
                return handled(ctx, u, apk, if (info.versionCode < installed)
                    "La versión publicada es más antigua que la instalada" else "")
        }

        // Evita reintentar sin parar si algo falla
        val now = System.currentTimeMillis()
        if (Prefs.attemptSha(ctx) == u.sha256 && now - Prefs.attemptAt(ctx) < RETRY_MS) return
        Prefs.setAttempt(ctx, u.sha256, now)
        Prefs.setPending(ctx, sha = u.sha256, info!!.versionCode)

        onStatus("Instalando actualización…")
        val err = install(ctx, apk)
        if (err != null) {
            Prefs.setUpdNote(ctx, err)
            onStatus("")
        }
    }

    private fun handled(ctx: Context, u: AppUpdate, apk: File, note: String) {
        Prefs.setUpdSha(ctx, u.sha256)
        Prefs.setUpdNote(ctx, note)
        apk.delete()
    }

    private fun download(ctx: Context, u: AppUpdate, part: File, running: () -> Boolean, onStatus: (String) -> Unit): Boolean {
        var have = if (part.exists()) part.length() else 0L
        if (have > u.size) {
            part.delete()
            have = 0L
        }
        val c = Net.open(ctx, u.url)
        c.connectTimeout = 8000
        c.readTimeout = 20000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            if (!append) have = 0L
            FileOutputStream(part, append).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    var done = have
                    var last = 0L
                    while (running()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val t = System.currentTimeMillis()
                        if (t - last > 1000) {
                            last = t
                            val pct = if (u.size > 0) (done * 100 / u.size).toInt() else 0
                            onStatus("Descargando actualización de la app $pct%")
                        }
                    }
                }
            }
            onStatus("")
            return part.length() == u.size
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Devuelve null si se envio la instalacion, o el motivo del fallo. */
    private fun install(ctx: Context, apk: File): String? {
        return try {
            val pi = ctx.packageManager.packageInstaller
            try {
                if (Build.VERSION.SDK_INT >= 24) pi.mySessions.forEach { pi.abandonSession(it.sessionId) }
            } catch (e: Exception) {
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val id = pi.createSession(params)
            val s = pi.openSession(id)
            try {
                FileInputStream(apk).use { input ->
                    s.openWrite("pantallas.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }
                val i = Intent(ctx, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pend = PendingIntent.getBroadcast(ctx, id, i, flags)
                s.commit(pend.intentSender)
            } finally {
                s.close()
            }
            null
        } catch (e: Exception) {
            "No se pudo instalar la actualización: " + (e.message ?: e.javaClass.simpleName)
        }
    }
}
