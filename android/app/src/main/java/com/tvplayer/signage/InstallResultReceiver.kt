package com.tvplayer.signage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Recibe el resultado de la instalacion de una actualizacion. */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.tvplayer.signage.INSTALL_RESULT"
    }

    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // El TV no es "propietario del dispositivo": Android pide confirmar la instalacion.
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                Prefs.setUpdNote(ctx, "Falta confirmar la instalación en el TV (con el control remoto)")
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirm)
                    } catch (e: Exception) {
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Prefs.setUpdNote(ctx, "")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error desconocido"
                Prefs.setUpdNote(ctx, "Falló la actualización: $msg")
            }
        }
    }
}
