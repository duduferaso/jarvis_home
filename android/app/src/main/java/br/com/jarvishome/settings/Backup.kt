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
    private val FILES = listOf("personal/vozes.tpl", "personal/aprendidos.tpl")
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

    // ------------------------------------------------------------------ pacote para o app novo

    fun pacoteFileName(): String = "home-control-pacote-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"

    /**
     * Pacote de configuração no formato do Home Control (app novo, ha_local_assistant): planta convertida,
     * chaves de cada função, voz, comandos treinados e ajustes. O token só vai se a pessoa pedir.
     */
    fun exportPacote(context: Context, includeToken: Boolean): String {
        val app = context.applicationContext
        val p = Prefs(app)
        val ajustes = mapOf<String, Any?>(
            "server_url" to p.serverUrl, "pipeline_id" to p.pipelineId,
            "local_lights" to p.localLights, "show_lights" to p.showLights, "trained_commands" to p.trainedCommands,
            "correct_text" to p.correctText, "extra_vocabulary" to p.extraVocabulary, "show_floor_plan" to p.showFloorPlan,
            "calendar_enabled" to p.calendarEnabled, "show_agenda" to p.showAgenda, "calendar_write" to p.calendarWrite,
            "agenda_reminders" to p.agendaReminders, "reminder_lead" to p.reminderLeadMin,
            "lists_enabled" to p.listsEnabled, "shopping_list" to p.shoppingList, "tasks_list" to p.tasksList,
            "show_weather" to p.showWeather,
            "workout_min" to p.workoutMin, "workout_work" to p.workoutWork, "workout_rest" to p.workoutRest,
            "workout_series" to p.workoutSeries, "workout_count" to p.workoutCount, "workout_exercises" to p.workoutExercises,
            "learn_from_success" to p.learnFromSuccess, "beep" to p.beep, "volume" to p.volume, "silence_ms" to p.silenceMs,
            "night_mode" to p.nightMode, "night_start" to p.nightStart, "night_end" to p.nightEnd,
            "quiet_at_night" to p.quietAtNight, "night_volume" to p.nightVolume, "speak_at_night" to p.speakAtNight,
            "keep_screen_on" to p.keepScreenOn, "dim_screen" to p.dimScreen, "show_clock" to p.showClock,
            "reduce_motion" to p.reduceMotion, "start_on_boot" to p.startOnBoot, "auto_update" to p.autoUpdate,
            "learned_fixes" to p.learnedFixes, "server_temp_limit" to p.serverTempLimit, "server_sensors" to p.serverSensors
        )
        val arquivos = HashMap<String, ByteArray>()
        for (name in FILES) {
            val f = File(app.filesDir, name)
            if (f.exists()) arquivos[name] = f.readBytes()
        }
        // nunca treinou neste celular: leva os exemplos de fábrica, que são os que estão em uso
        if ("personal/vozes.tpl" !in arquivos) try {
            arquivos["personal/vozes.tpl"] = app.assets.open("personal/hey_tony.tpl").use { it.readBytes() }
        } catch (_: Exception) {}
        val planta = br.com.jarvishome.home.Planta.daPlantaAtual(br.com.jarvishome.home.FloorPlan.decode(p.floorMap))
        val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "?" }
        val criado = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val o = PacoteConfig.montar(PacoteConfig.Fonte(ajustes, arquivos, planta, version ?: "?", criado,
            if (includeToken) p.token else null))
        JLog.i("Pacote para o Home Control exportado" + if (includeToken) " (com token)" else "")
        return o.toString(1)
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
