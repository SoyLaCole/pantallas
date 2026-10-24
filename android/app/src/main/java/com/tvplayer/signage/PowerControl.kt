package com.tvplayer.signage

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object PowerControl {
    fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(component(ctx))
    }

    /** Apaga la pantalla del TV. Devuelve "ok" o el motivo por el que no se pudo. */
    fun sleepNow(ctx: Context): String {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isAdminActive(component(ctx))) {
                "sin permiso de apagado"
            } else {
                dpm.lockNow()
                "ok"
            }
        } catch (e: Exception) {
            "error: " + (e.message ?: e.javaClass.simpleName)
        }
    }
}
