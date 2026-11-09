package br.com.jarvishome.settings

import android.content.Context
import android.util.Base64
import br.com.jarvishome.domain.JLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cópia de segurança dos treinos e configurações, para levar para outro celular:
 * vozes do "Hey Tony" (detector pessoal), comandos de luz treinados, luzes da planta, vocabulário,
 * treino físico, agenda, tela etc. O token do Home Assistant só vai se a pessoa pedir.
 */
object Backup {

    const val FORMAT = 1
    /** Arquivos de treino (dentro de filesDir) que vão na cópia. */
    private val FILES = listOf("personal/vozes.tpl")
    /** Preferências que não devem passar de um celular para outro. */
    private val SKIP = setOf("provision_id", "last_update_check", "timers")

    class Summary(val prefs: Int, val files: Int, val token: Boolean, val from: String)

    fun fileName(): String = "jarvis-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"

    fun export(context: Context, includeToken: Boolean): String {
        val app = context.applicationContext
        val sp = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val prefs = JSONObject()
        for ((k, v) in sp.all) {
            if (k in SKIP || v == null) continue
            val o = JSONObject()
            when (v) {
                is String -> o.put("t", "s").put("v", v)
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Set<*> -> o.put("t", "set").put("v", JSONArray().apply { v.forEach { put(it.toString()) } })
                else -> continue
            }
            prefs.put(k, o)
        }
        val files = JSONObject()
        for (name in FILES) {
            val f = File(app.filesDir, name)
            if (f.exists()) files.put(name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "?" }
        val root = JSONObject()
            .put("app", "JarvisHome").put("format", FORMAT).put("version", version)
            .put("created", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .put("prefs", prefs).put("files", files)
        if (includeToken) {
            val p = Prefs(app)
            root.put("token", p.token)
            if (p.updateToken.isNotEmpty()) root.put("updateToken", p.updateToken)
        }
        return root.toString(1)
    }

    /** Restaura a cópia. Lança exceção com mensagem clara se o arquivo não for uma cópia do Jarvis. */
    fun import(context: Context, text: String): Summary {
        val app = context.applicationContext
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("o arquivo não é uma cópia do Jarvis") }
        if (root.optString("app") != "JarvisHome") throw IllegalArgumentException("o arquivo não é uma cópia do Jarvis")
        if (root.optInt("format", 0) > FORMAT) throw IllegalArgumentException("cópia feita por uma versão mais nova do app; atualize antes")
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        val ed = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
        var n = 0
        val keys = prefs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in SKIP) continue
            val o = prefs.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "s" -> ed.putString(k, o.optString("v"))
                "b" -> ed.putBoolean(k, o.optBoolean("v"))
                "i" -> ed.putInt(k, o.optInt("v"))
                "l" -> ed.putLong(k, o.optLong("v"))
                "f" -> ed.putFloat(k, o.optDouble("v").toFloat())
                "set" -> {
                    val a = o.optJSONArray("v") ?: JSONArray()
                    ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                }
                else -> continue
            }
            n++
        }
        ed.commit()
        var files = 0
        val fo = root.optJSONObject("files") ?: JSONObject()
        for (name in FILES) {
            if (!fo.has(name)) continue
            val f = File(app.filesDir, name)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(Base64.decode(fo.optString(name), Base64.DEFAULT))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            files++
        }
        var token = false
        val p = Prefs(app)
        if (root.optString("token").isNotEmpty()) { p.token = root.optString("token"); token = true }
        if (root.optString("updateToken").isNotEmpty()) p.updateToken = root.optString("updateToken")
        JLog.i("Cópia restaurada: $n configurações, $files arquivos de treino" + if (token) ", com token" else "")
        return Summary(n, files, token, root.optString("version") + " · " + root.optString("created"))
    }
}
