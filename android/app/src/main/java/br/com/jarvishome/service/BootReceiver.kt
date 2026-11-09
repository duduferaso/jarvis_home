package br.com.jarvishome.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.settings.Prefs

/**
 * Religa o Jarvis depois de reiniciar o aparelho ou atualizar o app.
 * Observação: no Android 11+ um serviço iniciado pelo boot pode não receber áudio do
 * microfone até a tela do app aparecer. Por isso o modo terminal (Jarvis como tela inicial)
 * é o caminho recomendado: o sistema abre a Activity sozinho no boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.startOnBoot) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            JarvisForegroundService.send(context, JarvisForegroundService.ACTION_START)
            JLog.i("Iniciado automaticamente ($action)")
        } catch (e: Exception) {
            JLog.w("Não foi possível iniciar no boot", e)
        }
    }
}
