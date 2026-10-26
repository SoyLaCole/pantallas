package com.tvplayer.signage

import org.json.JSONObject

/** Hora del servidor: asi los horarios no dependen de que el reloj del TV este bien. */
object Clock {
    @Volatile var offset: Long = 0L
    fun now(): Long = System.currentTimeMillis() + offset
    fun setServer(serverMs: Long) {
        offset = serverMs - System.currentTimeMillis()
    }
}

class SleepRule(val days: List<Int>, val time: String)

class Item(
    val id: String,
    val type: String,          // video | image | web
    val title: String,
    val url: String,
    val fallback: Boolean,
    val start: Long?,          // segundos epoch
    val end: Long?,
    val duration: Int,         // segundos (imagen / web)
    val interactive: Boolean,
    val size: Long,
    val stamp: String,
    val ext: String
) {
    val isMedia: Boolean get() = type == "video" || type == "image"
    val fileName: String get() = id + ext
    val partName: String get() = "$fileName.$stamp.part"
    fun activeAt(nowSec: Long): Boolean =
        (start == null || start <= nowSec) && (end == null || end > nowSec)
}

/** Version de la app publicada en el panel. */
class AppUpdate(val sha256: String, val size: Long, val url: String)

class Manifest(
    val status: String,
    val name: String,
    val orientation: Int,
    val reload: Int,
    val tz: String,
    val serverTime: Long,
    val sleep: List<SleepRule>,
    val items: List<Item>,
    val appUpdate: AppUpdate? = null
) {
    companion object {
        fun parse(text: String): Manifest {
            val j = JSONObject(text)
            val items = ArrayList<Item>()
            val arr = j.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(
                        Item(
                            id = o.getString("id"),
                            type = o.getString("type"),
                            title = o.optString("title", ""),
                            url = o.optString("url", ""),
                            fallback = o.optBoolean("fallback", false),
                            start = if (o.isNull("start")) null else o.optLong("start"),
                            end = if (o.isNull("end")) null else o.optLong("end"),
                            duration = if (o.isNull("duration")) 10 else o.optInt("duration", 10),
                            interactive = o.optBoolean("interactive", false),
                            size = o.optLong("size", 0L),
                            stamp = o.optString("stamp", "0"),
                            ext = o.optString("ext", "")
                        )
                    )
                }
            }
            val sleep = ArrayList<SleepRule>()
            val sa = j.optJSONArray("sleep")
            if (sa != null) {
                for (i in 0 until sa.length()) {
                    val o = sa.getJSONObject(i)
                    val da = o.optJSONArray("days")
                    val days = ArrayList<Int>()
                    if (da != null) for (k in 0 until da.length()) days.add(da.getInt(k))
                    sleep.add(SleepRule(days, o.optString("time", "")))
                }
            }
            val au = j.optJSONObject("app_update")
            val appUpdate = if (au != null && au.optString("sha256").isNotEmpty() && au.optString("url").isNotEmpty())
                AppUpdate(au.getString("sha256"), au.optLong("size", 0L), au.getString("url")) else null
            return Manifest(
                status = j.optString("status", "pending"),
                name = j.optString("name", ""),
                orientation = j.optInt("orientation", 0),
                reload = j.optInt("reload", 0),
                tz = j.optString("tz", "America/Bogota"),
                serverTime = j.optLong("server_time", 0L),
                sleep = sleep,
                items = items,
                appUpdate = appUpdate
            )
        }
    }
}
