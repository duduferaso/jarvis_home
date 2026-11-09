package br.com.jarvishome.domain

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Se o app fechar por erro, guarda o motivo num arquivo. Na próxima abertura ele aparece nos
 * logs técnicos e em ⚙ > Sistema, para dar para corrigir sem cabo nem computador.
 */
object CrashLog {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, "ultima_falha.txt")
        // falha da última vez: vai para o log desta vez
        try {
            if (file.exists()) JLog.e("Última falha do app:\n" + file.readText().take(3000))
        } catch (_: Exception) {}
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
                file.writeText("$stamp · thread ${thread.name}\n$sw")
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String? = try {
        File(context.applicationContext.filesDir, "ultima_falha.txt").takeIf { it.exists() }?.readText()
    } catch (_: Exception) { null }

    fun clear(context: Context) {
        try { File(context.applicationContext.filesDir, "ultima_falha.txt").delete() } catch (_: Exception) {}
    }
}
