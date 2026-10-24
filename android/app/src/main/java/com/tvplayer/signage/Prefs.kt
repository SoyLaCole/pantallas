package com.tvplayer.signage

import android.content.Context
import java.util.UUID

/** Datos que la app recuerda en el TV. */
object Prefs {
    const val DEFAULT_SERVER = "https://pantallas.lacole.edu.co"
    const val APP_VERSION = "2.0"

    private fun sp(c: Context) = c.getSharedPreferences("tvplayer", Context.MODE_PRIVATE)

    /** Direccion del servidor (por defecto la de La Cole). */
    fun serverUrl(c: Context): String = sp(c).getString("base", null) ?: DEFAULT_SERVER

    fun setServerUrl(c: Context, value: String?) {
        val e = sp(c).edit()
        if (value == null) e.remove("base") else e.putString("base", value)
        e.apply()
    }

    /** Identificador de este TV (se conserva entre versiones de la app). */
    fun tvId(c: Context): String {
        val existing = sp(c).getString("tv_id", null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        sp(c).edit().putString("tv_id", id).apply()
        return id
    }

    fun lastReload(c: Context): Int = sp(c).getInt("last_reload", -1)
    fun setLastReload(c: Context, value: Int) = sp(c).edit().putInt("last_reload", value).apply()

    fun emptySince(c: Context): Long = sp(c).getLong("empty_since", 0L)
    fun setEmptySince(c: Context, value: Long) = sp(c).edit().putLong("empty_since", value).apply()

    fun clockOffset(c: Context): Long = sp(c).getLong("clock_offset", 0L)
    fun setClockOffset(c: Context, value: Long) = sp(c).edit().putLong("clock_offset", value).apply()

    fun lastSleepKey(c: Context): String = sp(c).getString("last_sleep", "") ?: ""
    fun setLastSleepKey(c: Context, value: String) = sp(c).edit().putString("last_sleep", value).apply()
}
