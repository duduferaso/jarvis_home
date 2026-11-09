package br.com.jarvishome.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import br.com.jarvishome.domain.JLog
import java.io.File

/**
 * Instala o APK baixado. O Android sempre mostra a tela "Instalar?" para a pessoa confirmar.
 * Usa o PackageInstaller; se a MIUI recusar, cai para o instalador comum (ACTION_INSTALL_PACKAGE).
 */
object ApkInstaller {

    const val ACTION_STATUS = "br.com.jarvishome.INSTALL_STATUS"

    fun file(context: Context) = File(File(context.cacheDir, "update").apply { mkdirs() }, "JarvisHome-update.apk")

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Abre a tela "Permitir desta fonte" do Jarvis. */
    fun askPermission(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
    }

    fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun install(context: Context, apk: File) {
        try {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("jarvis.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    s.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
            JLog.i("Atualização: sessão de instalação $id enviada")
        } catch (e: Exception) {
            JLog.w("Atualização: PackageInstaller falhou, usando o instalador comum", e)
            installWithViewer(context)
        }
    }

    /** Plano B: o instalador do sistema abre o arquivo pelo nosso provedor. */
    fun installWithViewer(context: Context) {
        val uri = Uri.parse("content://" + context.packageName + ".apk/update.apk")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> JLog.i("Atualização instalada")
            PackageInstaller.STATUS_FAILURE_ABORTED -> JLog.i("Atualização cancelada pela pessoa")
            else -> {
                JLog.w("Atualização: instalação falhou ($status) $msg")
                if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE || status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    Toast.makeText(context, "Não deu para atualizar: assinatura diferente. Reinstale pelo arquivo.", Toast.LENGTH_LONG).show()
                } else {
                    // MIUI às vezes recusa a sessão: tenta pelo instalador comum
                    try { ApkInstaller.installWithViewer(context) } catch (e: Exception) {
                        Toast.makeText(context, "Não deu para atualizar: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

/** Entrega o APK baixado ao instalador do sistema (só leitura, só este arquivo). */
class ApkProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val f = ApkInstaller.file(context!!)
        if (uri.lastPathSegment != "update.apk" || !f.exists()) throw java.io.FileNotFoundException()
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun insert(u: Uri, v: android.content.ContentValues?) = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: android.content.ContentValues?, s: String?, a: Array<String>?) = 0
}
