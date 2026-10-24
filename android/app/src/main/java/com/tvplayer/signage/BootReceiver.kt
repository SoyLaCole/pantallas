package com.tvplayer.signage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Abre la app sola cuando el TV termina de encender. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launch = Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
