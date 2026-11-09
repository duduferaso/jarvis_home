package br.com.jarvishome.settings

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val secure = SecureStore(context.applicationContext)

    /** true = fala direto com o Home Assistant; false = passa pelo orquestrador opcional. */
    var directMode: Boolean
        get() = sp.getBoolean("direct_mode", true)
        set(v) = sp.edit().putBoolean("direct_mode", v).apply()

    var serverUrl: String
        get() = sp.getString("server_url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString("server_url", normalizeUrl(v, if (directMode) 8123 else 8765)).apply()

    /** Id da pipeline do HA; vazio = pipeline preferida. */
    var pipelineId: String
        get() = sp.getString("pipeline_id", "") ?: ""
        set(v) = sp.edit().putString("pipeline_id", v.trim()).apply()

    var terminalId: String
        get() = sp.getString("terminal_id", "redmi10") ?: "redmi10"
        set(v) = sp.edit().putString("terminal_id", v.trim().ifEmpty { "redmi10" }).apply()

    /** Token (do HA no modo direto, do terminal no orquestrador): cifrado com o Android Keystore. */
    var token: String
        get() = secure.get("terminal_token")
        set(v) = secure.put("terminal_token", cleanToken(v))

    var assistantEnabled: Boolean
        get() = sp.getBoolean("assistant_enabled", true)
        set(v) = sp.edit().putBoolean("assistant_enabled", v).apply()

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake_enabled", true)
        set(v) = sp.edit().putBoolean("wake_enabled", v).apply()

    /** 1 (menos falsos positivos) … 10 (aciona com mais facilidade). */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 8)
        set(v) = sp.edit().putInt("sensitivity", v.coerceIn(1, 10)).apply()

    /** Detector pessoal (comparação com exemplos de voz da casa) em vez do modelo em inglês. */
    var personalDetector: Boolean
        get() = sp.getBoolean("personal_detector", true)
        set(v) = sp.edit().putBoolean("personal_detector", v).apply()

    /** Palavra de ativação escolhida (ver WakeWords). */
    var wakeWordId: String
        get() = sp.getString("wake_word_id", "jarvis") ?: "jarvis"
        set(v) = sp.edit().putString("wake_word_id", v).apply()

    /** Palavra para a qual a última calibração foi salva ("" = não calibrado). */
    var calibratedWord: String
        get() = sp.getString("calibrated_word", "") ?: ""
        set(v) = sp.edit().putString("calibrated_word", v).apply()

    var keywords: String
        get() = sp.getString("keywords", DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(v) = sp.edit().putString("keywords", v.trim().ifEmpty { DEFAULT_KEYWORDS }).apply()

    /** Reforço das palavras-chave no detector (1,5 padrão; a calibração pode subir para 3,0). */
    var keywordBoost: Float
        get() = sp.getFloat("keyword_boost", 1.5f)
        set(v) = sp.edit().putFloat("keyword_boost", v.coerceIn(0.5f, 5f)).apply()

    /** Corrige a transcrição com os nomes da casa ("zoquart" → "quarto"). */
    var correctText: Boolean
        get() = sp.getBoolean("correct_text", true)
        set(v) = sp.edit().putBoolean("correct_text", v).apply()

    /** Palavras e frases extras da casa, uma por linha (somadas aos nomes lidos do HA). */
    var extraVocabulary: String
        get() = sp.getString("extra_vocabulary", DEFAULT_EXTRA_VOCABULARY) ?: DEFAULT_EXTRA_VOCABULARY
        set(v) = sp.edit().putString("extra_vocabulary", v.trim()).apply()

    /** Ligar/desligar luzes no próprio app, antes do Home Assistant. */
    var localLights: Boolean
        get() = sp.getBoolean("local_lights", true)
        set(v) = sp.edit().putBoolean("local_lights", v).apply()

    /** Exemplos gravados de comandos de luz (JSON: como o Whisper transcreveu cada um). */
    var trainedCommands: String
        get() = sp.getString("trained_commands", "[]") ?: "[]"
        set(v) = sp.edit().putString("trained_commands", v).apply()

    /** Repositório do GitHub com as versões (Releases). */
    var updateRepo: String
        get() = sp.getString("update_repo", "duduferaso/jarvis_home") ?: "duduferaso/jarvis_home"
        set(v) = sp.edit().putString("update_repo", v.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')).apply()

    /** Chave só de leitura do GitHub (só se o repositório for privado). Cifrada. */
    var updateToken: String
        get() = secure.get("update_token")
        set(v) = secure.put("update_token", v.trim())

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** Timers e alarmes ligados (para sobreviver a um reinício do app). */
    var timers: String
        get() = sp.getString("timers", "[]") ?: "[]"
        set(v) = sp.edit().putString("timers", v).apply()

    // Treino intervalado
    var workoutMin: Int
        get() = sp.getInt("workout_min", 30)
        set(v) = sp.edit().putInt("workout_min", v).apply()
    var workoutWork: Int
        get() = sp.getInt("workout_work", 30)
        set(v) = sp.edit().putInt("workout_work", v).apply()
    var workoutRest: Int
        get() = sp.getInt("workout_rest", 30)
        set(v) = sp.edit().putInt("workout_rest", v).apply()
    var workoutSeries: Int
        get() = sp.getInt("workout_series", 5)
        set(v) = sp.edit().putInt("workout_series", v).apply()
    var workoutCount: Int
        get() = sp.getInt("workout_count", 5)
        set(v) = sp.edit().putInt("workout_count", v).apply()
    /** Nomes dos exercícios (opcional), um por linha. */
    var workoutExercises: String
        get() = sp.getString("workout_exercises", "") ?: ""
        set(v) = sp.edit().putString("workout_exercises", v).apply()

    fun workoutPlan() = br.com.jarvishome.commands.Workout.Plan(workoutMin, workoutWork, workoutRest,
        workoutExercises.lines().map { it.trim() }.filter { it.isNotEmpty() }, workoutCount, workoutSeries)

    /** Planta da casa na tela inicial (em espera). */
    var showFloorPlan: Boolean
        get() = sp.getBoolean("show_floor_plan", true)
        set(v) = sp.edit().putBoolean("show_floor_plan", v).apply()
    /** Luzes escolhidas para cada cômodo da planta (JSON {cômodo: [entity_id]}). */
    var floorMap: String
        get() = sp.getString("floor_map", "{}") ?: "{}"
        set(v) = sp.edit().putString("floor_map", v).apply()

    var silenceMs: Int
        get() = sp.getInt("silence_ms", 900)
        set(v) = sp.edit().putInt("silence_ms", v.coerceIn(400, 2500)).apply()

    var beep: Boolean
        get() = sp.getBoolean("beep", true)
        set(v) = sp.edit().putBoolean("beep", v).apply()

    var volume: Int
        get() = sp.getInt("volume", 90)
        set(v) = sp.edit().putInt("volume", v.coerceIn(0, 100)).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    var dimScreen: Boolean
        get() = sp.getBoolean("dim_screen", false)
        set(v) = sp.edit().putBoolean("dim_screen", v).apply()

    var reduceMotion: Boolean
        get() = sp.getBoolean("reduce_motion", false)
        set(v) = sp.edit().putBoolean("reduce_motion", v).apply()

    var showClock: Boolean
        get() = sp.getBoolean("show_clock", true)
        set(v) = sp.edit().putBoolean("show_clock", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    // ---- tela informativa
    var showLights: Boolean
        get() = sp.getBoolean("show_lights", true)
        set(v) = sp.edit().putBoolean("show_lights", v).apply()

    var showAgenda: Boolean
        get() = sp.getBoolean("show_agenda", true)
        set(v) = sp.edit().putBoolean("show_agenda", v).apply()

    var showWeather: Boolean
        get() = sp.getBoolean("show_weather", true)
        set(v) = sp.edit().putBoolean("show_weather", v).apply()

    /** Alerta quando a temperatura do servidor passar disto (°C). */
    var serverTempLimit: Int
        get() = sp.getInt("server_temp_limit", 75)
        set(v) = sp.edit().putInt("server_temp_limit", v.coerceIn(50, 100)).apply()

    /** Sensores do servidor escolhidos à mão (vazio = detectar sozinho). Separados por vírgula. */
    var serverSensors: String
        get() = sp.getString("server_sensors", "") ?: ""
        set(v) = sp.edit().putString("server_sensors", v.trim()).apply()

    fun serverSensorIds(): Set<String> =
        serverSensors.split(',', ' ', '\n').map { it.trim() }.filter { it.contains('.') }.toSet()

    // ---- modo noite
    var nightMode: Boolean
        get() = sp.getBoolean("night_mode", true)
        set(v) = sp.edit().putBoolean("night_mode", v).apply()

    var nightStart: Int
        get() = sp.getInt("night_start", 23)
        set(v) = sp.edit().putInt("night_start", v.coerceIn(0, 23)).apply()

    var nightEnd: Int
        get() = sp.getInt("night_end", 6)
        set(v) = sp.edit().putInt("night_end", v.coerceIn(0, 23)).apply()

    /** Falar avisos e lembretes durante o modo noite (padrão: só mostrar). */
    var speakAtNight: Boolean
        get() = sp.getBoolean("speak_at_night", false)
        set(v) = sp.edit().putBoolean("speak_at_night", v).apply()

    // ---- agenda
    var calendarEnabled: Boolean
        get() = sp.getBoolean("calendar_enabled", true)
        set(v) = sp.edit().putBoolean("calendar_enabled", v).apply()

    /** Agenda que recebe os compromissos novos ("" = a primeira que aceitar). */
    var calendarWrite: String
        get() = sp.getString("calendar_write", "") ?: ""
        set(v) = sp.edit().putString("calendar_write", v.trim()).apply()

    /** Falar os compromissos na hora em que começam (lembretes). */
    var agendaReminders: Boolean
        get() = sp.getBoolean("agenda_reminders", true)
        set(v) = sp.edit().putBoolean("agenda_reminders", v).apply()

    /** Avisar quantos minutos antes (0 = na hora). */
    var reminderLeadMin: Int
        get() = sp.getInt("reminder_lead", 0)
        set(v) = sp.edit().putInt("reminder_lead", v.coerceIn(0, 60)).apply()

    val isConfigured: Boolean get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    /** Sensibilidade 1..10 → limiar do detector 0,40..0,08 (menor = mais sensível). */
    val wakeThreshold: Float get() = thresholdFor(sensitivity)

    companion object {
        /**
         * "Jarvis" em tokens BPE do modelo gigaspeech (gerados com sentencepiece a partir do
         * bpe.model do próprio modelo). O modelo é treinado em inglês, então a pronúncia
         * brasileira precisa de variações: calibre com a SUA voz usando
         * tools/calibrar_wakeword.py. Formato sherpa-onnx, uma por linha:  TOKENS @RÓTULO
         */
        const val DEFAULT_KEYWORDS = "▁JA R VI S @JARVIS\n" +
            "▁JA VI S @JAVIS\n" +
            "▁JO VI S @JOVIS\n" +
            "▁JA R VE S @JARVES\n" +
            "▁JA R ▁VI S @JAR_VIS\n" +
            "▁SHA R VI S @SHARVIS"

        fun thresholdFor(sensitivity: Int): Float = 0.40f - (sensitivity - 1) * (0.32f / 9f)

        const val DEFAULT_URL = "http://192.168.18.108:8123"

        /** Vocabulário dos comandos já existentes na casa (levantado pelo Claude Code via API). */
        const val DEFAULT_EXTRA_VOCABULARY = "sala de jantar\nquarto de visita\ntemperatura do servidor\n" +
            "armazenamento do servidor\nstatus do servidor\nserviço de vídeos\nerros nos vídeos de hoje\n" +
            "inscritos do canal\nvisualizações do canal\nresumo do canal\nlançar no financeiro\n" +
            "desfazer último lançamento\nconsultar gastos\nmês atual\nconsultar quanto foi amortizado\namortizado"

        /**
         * Tokens (JWT do HA ou hex do orquestrador) só têm letras, números, ponto, hífen e sublinhado.
         * Remove espaços, quebras de linha, aspas e caracteres invisíveis que vêm ao copiar de chats.
         */
        fun cleanToken(raw: String): String =
            raw.filter { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '.' || it == '_' || it == '-' }

        fun normalizeUrl(raw: String, defaultPort: Int): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            val hostPart = u.substringAfter("://")
            if (!hostPart.contains(":")) u = "$u:$defaultPort"
            return u
        }
    }
}
