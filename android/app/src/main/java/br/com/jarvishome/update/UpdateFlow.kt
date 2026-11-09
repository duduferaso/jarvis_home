package br.com.jarvishome.update

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.settings.Prefs
import br.com.jarvishome.ui.SettingsActivity

/** Procurar, baixar e instalar atualizações (tela de configurações e verificação automática). */
object UpdateFlow {

    private const val CHANNEL = "jarvis_updates"
    private const val NOTIF_ID = 4711
    const val EXTRA_UPDATE = "jarvis_update"
    @Volatile private var checking = false

    /** Verificação automática: no máximo a cada 12 h; avisa por notificação se houver versão nova. */
    fun autoCheck(context: Context) {
        val app = context.applicationContext
        val p = Prefs(app)
        if (!p.autoUpdate || p.updateRepo.isBlank() || checking) return
        if (System.currentTimeMillis() - p.lastUpdateCheck < 12 * 3600_000L) return
        checking = true
        p.lastUpdateCheck = System.currentTimeMillis()
        Thread {
            try {
                val r = Updater.latest(p.updateRepo, p.updateToken)
                if (r != null && r.versionCode > ApkInstaller.versionCode(app)) notify(app, r)
            } catch (e: Exception) {
                JLog.w("Atualização: verificação automática falhou", e)
            } finally {
                checking = false
            }
        }.start()
    }

    private fun notify(context: Context, r: Updater.Release) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atualizações do Jarvis", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, SettingsActivity::class.java).putExtra(EXTRA_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Atualização do Jarvis: ${r.versionName}")
            .setContentText("Toque para instalar")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
        JLog.i("Atualização disponível: ${r.versionName} (${r.versionCode})")
    }

    /** Fluxo completo com diálogos: procurar → mostrar novidades → baixar → instalar. */
    fun run(activity: Activity) {
        val p = Prefs(activity)
        val main = Handler(Looper.getMainLooper())
        val dlg = AlertDialog.Builder(activity).setTitle("Atualização").setMessage("Procurando versão nova…")
            .setNegativeButton("Fechar", null).show()
        Thread {
            val current = ApkInstaller.versionCode(activity)
            val r = try { Updater.latest(p.updateRepo, p.updateToken) } catch (e: Exception) {
                JLog.w("Atualização: falha ao procurar", e)
                main.post { if (!activity.isFinishing) dlg.setMessage("Não consegui ver as versões: " + (e.message ?: e.javaClass.simpleName)) }
                return@Thread
            }
            p.lastUpdateCheck = System.currentTimeMillis()
            main.post {
                if (activity.isFinishing) return@post
                if (r == null || r.versionCode <= current) {
                    dlg.setMessage("Você já está na versão mais nova." + (r?.let { "\n(publicada: ${it.versionName})" } ?: ""))
                    return@post
                }
                dlg.dismiss()
                AlertDialog.Builder(activity).setTitle("Versão ${r.versionName} disponível")
                    .setMessage(r.notes.ifEmpty { "Sem notas desta versão." } +
                        "\n\n" + String.format("%.1f MB. As configurações e o treino de voz continuam.", r.size / 1e6))
                    .setPositiveButton("Instalar") { _, _ -> download(activity, r) }
                    .setNegativeButton("Agora não", null).show()
            }
        }.start()
    }

    private fun download(activity: Activity, r: Updater.Release) {
        if (!ApkInstaller.canInstall(activity)) {
            AlertDialog.Builder(activity).setTitle("Uma permissão, só uma vez")
                .setMessage("O Android precisa que você permita o Jarvis instalar atualizações.\n\n" +
                    "Na próxima tela, ligue “Permitir desta fonte”, volte aqui e toque em Instalar de novo.")
                .setPositiveButton("Abrir") { _, _ -> ApkInstaller.askPermission(activity) }
                .setNegativeButton("Cancelar", null).show()
            return
        }
        val p = Prefs(activity)
        val main = Handler(Looper.getMainLooper())
        val dlg = AlertDialog.Builder(activity).setTitle("Baixando ${r.versionName}").setMessage("0%")
            .setCancelable(false).show()
        Thread {
            try {
                val f = ApkInstaller.file(activity)
                Updater.download(r, p.updateToken, f) { pct -> main.post { dlg.setMessage("$pct%") } }
                main.post {
                    dlg.dismiss()
                    ApkInstaller.install(activity, f)
                }
            } catch (e: Exception) {
                JLog.w("Atualização: download falhou", e)
                main.post { dlg.setCancelable(true); dlg.setMessage("O download falhou: " + (e.message ?: e.javaClass.simpleName)) }
            }
        }.start()
    }
}
