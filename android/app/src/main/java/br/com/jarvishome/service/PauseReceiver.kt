package br.com.jarvishome.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.settings.Prefs

/**
 * O Home Control (app novo) pede para este app pausar quando volta a ser usado no Redmi.
 * Os dois convivem no mesmo celular, um ativo por vez (o microfone é de um só).
 */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != JarvisForegroundService.BROADCAST_PAUSE_ME) return
        val prefs = Prefs(context)
        if (prefs.pausedForOther) return
        JLog.i("Home Control pediu a pausa total")
        try {
            JarvisForegroundService.send(context, JarvisForegroundService.ACTION_PAUSE_TOTAL)
        } catch (t: Throwable) {
            // serviço parado: basta marcar; ele já inicia em pausa
            prefs.pausedForOther = true
            prefs.assistantEnabled = false
        }
    }
}
