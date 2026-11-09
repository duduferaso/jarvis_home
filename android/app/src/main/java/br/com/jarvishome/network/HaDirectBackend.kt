package br.com.jarvishome.network

import br.com.jarvishome.calendar.CalendarAssistant
import br.com.jarvishome.commands.LightCommands
import br.com.jarvishome.commands.Timers
import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.home.HomeApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Conexão direta com o Home Assistant, sem orquestrador.
 * Usa a mesma API dos satélites de voz oficiais (WebSocket + assist_pipeline/run), em duas etapas:
 *   1) fala → texto (Whisper do HA);
 *   2) o app corrige o texto com os nomes da casa ("zoquart" → "quarto") e remove a palavra de ativação;
 *   3) texto → comando → resposta falada (Assist + Piper).
 * O token é o de longa duração do usuário "jarvis" (sem administrador).
 */
class HaDirectBackend(
    baseUrl: String,
    internal val token: String,
    internal val pipelineId: String,
    internal val wakeNames: List<String> = emptyList(),
    internal val extraVocabulary: List<String> = emptyList(),
    internal val correctText: Boolean = true,
    internal val calendarEnabled: Boolean = true,
    internal val calendarWrite: String = "",
    /** Liga/desliga luzes no próprio app (frase ou exemplos treinados), antes do Home Assistant. */
    internal val localLights: Boolean = true,
    internal val trained: List<LightCommands.Trained> = emptyList()
) : Backend {

    internal val base = baseUrl.trimEnd('/')

    override fun health(): ConnectionState {
        if (base.isEmpty() || token.isEmpty()) return ConnectionState.NOT_CONFIGURED
        var conn: HttpURLConnection? = null
        val state = try {
            conn = URL("$base/api/").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            val code = conn.responseCode
            lastDetail = when (code) {
                200 -> "HTTP 200: token aceito"
                401 -> "HTTP 401: o Home Assistant não reconheceu este token (" + token.length +
                    " caracteres, termina em …" + token.takeLast(4) + ")"
                403 -> "HTTP 403: o Home Assistant BLOQUEOU o IP deste celular (arquivo ip_bans.yaml)"
                else -> "HTTP $code"
            }
            when (code) {
                200 -> ConnectionState.CONNECTED
                401, 403 -> ConnectionState.UNAUTHORIZED
                else -> ConnectionState.HA_DOWN
            }
        } catch (e: Exception) {
            lastDetail = e.javaClass.simpleName + ": " + (e.message ?: "")
            ConnectionState.DISCONNECTED
        } finally {
            conn?.disconnect()
        }
        // Aproveita a verificação em segundo plano para manter os nomes da casa atualizados.
        if (state == ConnectionState.CONNECTED && System.currentTimeMillis() - vocabularyAt > VOCAB_TTL_MS) {
            try { refreshVocabulary() } catch (e: Exception) { JLog.w("Não consegui ler os nomes da casa", e) }
        }
        return state
    }

    /** Lê nomes de dispositivos (REST) e de cômodos (WebSocket) do Home Assistant. */
    fun refreshVocabulary() {
        val names = LinkedHashSet<String>()
        val conn = URL("$base/api/states").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 3000
        conn.readTimeout = 10000
        try {
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                lights = LightCommands.lightsFrom(arr)
                for (i in 0 until arr.length()) {
                    val st = arr.optJSONObject(i) ?: continue
                    val domain = st.optString("entity_id").substringBefore('.')
                    if (domain in IGNORED_DOMAINS) continue
                    val name = st.optJSONObject("attributes")?.optString("friendly_name") ?: ""
                    if (domain == "automation" || domain == "script") {
                        // "Voz: consultar gastos (mês atual e próximos)" → "consultar gastos"
                        val m = VOICE_PREFIX.find(name) ?: continue
                        val phrase = name.substring(m.range.last + 1).substringBefore('(').trim()
                        if (phrase.length >= 4) names.add(phrase)
                        continue
                    }
                    if (name.length in 3..40) names.add(name)
                }
            }
        } finally {
            conn.disconnect()
        }
        try {
            val ws = MiniWebSocket.connect("$base/api/websocket", 4000)
            try {
                ws.readTimeoutMs = 5000
                ws.readText()
                ws.sendText(JSONObject().put("type", "auth").put("access_token", token).toString())
                if (JSONObject(ws.readText()).optString("type") == "auth_ok") {
                    ws.sendText(JSONObject().put("id", 1).put("type", "config/area_registry/list").toString())
                    val res = JSONObject(ws.readText())
                    val areas = res.optJSONArray("result")
                    if (res.optBoolean("success", false) && areas != null) {
                        for (i in 0 until areas.length()) {
                            val a = areas.optJSONObject(i) ?: continue
                            a.optString("name").takeIf { it.isNotEmpty() }?.let { names.add(it) }
                            val aliases = a.optJSONArray("aliases")
                            if (aliases != null) for (k in 0 until aliases.length()) {
                                aliases.optString(k).takeIf { it.isNotEmpty() }?.let { names.add(it) }
                            }
                        }
                    }
                }
            } finally {
                ws.close()
            }
        } catch (e: Exception) {
            JLog.w("Lista de cômodos indisponível (seguindo só com os dispositivos)", e)
        }
        if (names.isNotEmpty()) {
            vocabulary = names.toList()
            vocabularyAt = System.currentTimeMillis()
            JLog.i("Nomes da casa atualizados: " + names.size)
        }
    }

    internal fun fullVocabulary(): List<String> = (vocabulary + extraVocabulary).distinct()

    override fun newSession(): Session = HaSession(this)

    /** Só transcreve um áudio (PCM 16 kHz) com a pipeline do Home Assistant. Usado no treino de comandos. */
    fun transcribeAudio(pcm: ByteArray): String {
        val s = HaSession(this, sttOnly = true)
        var i = 0
        while (i < pcm.size) {
            s.push(pcm.copyOfRange(i, minOf(pcm.size, i + 3200)))
            i += 3200
        }
        s.finish()
        var heard: String? = null
        var err: String? = null
        s.run { ev ->
            when (ev.optString("type")) {
                "transcription" -> heard = ev.optString("heard")
                "error" -> err = ev.optString("message").ifEmpty { ev.optString("code") }
            }
        }
        return heard ?: throw IOException(err ?: "O Home Assistant não devolveu texto")
    }

    /**
     * Gera a fala de um texto com a voz do Home Assistant (Piper), sem passar pelo agente.
     * Usado para avisos e lembretes. Devolve a URL do áudio, ou null.
     */
    fun synthesize(text: String): String? {
        val ws = MiniWebSocket.connect("$base/api/websocket", 4000)
        try {
            ws.readTimeoutMs = 10000
            ws.readText()
            ws.sendText(JSONObject().put("type", "auth").put("access_token", token).toString())
            if (JSONObject(ws.readText()).optString("type") != "auth_ok") return null
            return speakOn(ws, 1, pipelineId, text)
        } finally {
            ws.close()
        }
    }

    /** O HA pode devolver URL relativa ou absoluta (com internal_url); usamos sempre o endereço configurado. */
    internal fun resolve(path: String): String {
        if (!path.startsWith("http://") && !path.startsWith("https://")) return base + path
        val u = URL(path)
        return base + u.file
    }

    override fun download(path: String, dest: File) {
        val conn = URL(resolve(path)).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 3000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP " + conn.responseCode + " ao baixar a resposta falada")
            conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Respostas de "não entendi" que valem uma segunda tentativa ("" = sem código). */
        internal val RETRY_CODES = setOf("no_intent_match", "no_valid_targets", "")
        /** Detalhe técnico da última verificação, para a tela de configurações. */
        @Volatile var lastDetail: String = ""

        /** Nomes da casa lidos do HA (compartilhados entre sessões). */
        @Volatile var vocabulary: List<String> = emptyList()
        @Volatile var vocabularyAt = 0L
        /** Luzes da casa (lidas junto com o vocabulário). */
        @Volatile var lights: List<LightCommands.Light> = emptyList()
        private const val VOCAB_TTL_MS = 30 * 60 * 1000L
        /** Automações/scripts de voz seguem o padrão "Voz: frase". */
        private val VOICE_PREFIX = Regex("^\\s*(voz|comando|jarvis)\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE)
        private val IGNORED_DOMAINS = setOf("person", "zone", "sun", "update", "device_tracker", "event",
            "conversation", "stt", "tts", "wake_word", "assist_satellite", "button", "image", "todo", "weather")

        /** Mantém o contexto da conversa por alguns minutos ("e a do quarto?"). */
        /** Roda só a etapa de voz (tts) da pipeline numa conexão já autenticada. */
        internal fun speakOn(ws: MiniWebSocket, id: Int, pipelineId: String, text: String): String? {
            val req = JSONObject().put("id", id).put("type", "assist_pipeline/run")
                .put("start_stage", "tts").put("end_stage", "tts").put("input", JSONObject().put("text", text))
            if (pipelineId.isNotEmpty()) req.put("pipeline", pipelineId)
            ws.sendText(req.toString())
            ws.readTimeoutMs = 30000
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                val msg = JSONObject(ws.readText())
                if (msg.optInt("id") != id) continue
                when (msg.optString("type")) {
                    "result" -> if (!msg.optBoolean("success", false)) return null
                    "event" -> {
                        val ev = msg.optJSONObject("event") ?: continue
                        when (ev.optString("type")) {
                            "tts-end" -> return ev.optJSONObject("data")?.optJSONObject("tts_output")
                                ?.optString("url")?.ifEmpty { null }
                            "error", "run-end" -> return null
                        }
                    }
                }
            }
            return null
        }

        @Volatile internal var conversationId: String? = null
        @Volatile internal var conversationExpires = 0L
        internal const val CONVERSATION_TTL_MS = 5 * 60 * 1000L
        internal const val RUN_TIMEOUT_MS = 60_000L
    }
}

private class HaSession(private val backend: HaDirectBackend, private val sttOnly: Boolean = false) : Session {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var ws: MiniWebSocket? = null
    @Volatile override var cancelled = false; private set

    override fun push(pcm: ByteArray) { if (!cancelled) queue.offer(pcm) }
    override fun finish() { queue.offer(EOF) }

    override fun cancel() {
        cancelled = true
        queue.clear()
        queue.offer(EOF)
        ws?.close()
    }

    private fun error(code: String, message: String) =
        JSONObject().put("type", "error").put("code", code).put("message", message)

    private fun runRequest(id: Int, start: String, end: String, input: JSONObject, withConversation: Boolean): JSONObject {
        val req = JSONObject()
            .put("id", id)
            .put("type", "assist_pipeline/run")
            .put("start_stage", start)
            .put("end_stage", end)
            .put("input", input)
        if (backend.pipelineId.isNotEmpty()) req.put("pipeline", backend.pipelineId)
        val conv = HaDirectBackend.conversationId
        if (withConversation && conv != null && System.currentTimeMillis() < HaDirectBackend.conversationExpires) {
            req.put("conversation_id", conv)
        }
        return req
    }

    override fun run(onEvent: (JSONObject) -> Unit) {
        val socket = MiniWebSocket.connect(backend.base + "/api/websocket", 4000)
        ws = socket
        try {
            socket.readTimeoutMs = 10000
            if (JSONObject(socket.readText()).optString("type") != "auth_required") {
                throw IOException("Resposta inesperada do Home Assistant")
            }
            socket.sendText(JSONObject().put("type", "auth").put("access_token", backend.token).toString())
            if (JSONObject(socket.readText()).optString("type") != "auth_ok") {
                onEvent(error("unauthorized", "Home Assistant recusou o token"))
                return
            }

            // ---- Etapa 1: fala → texto
            val heard = transcribe(socket, onEvent) ?: return
            if (cancelled) return
            if (sttOnly) {
                onEvent(JSONObject().put("type", "transcription").put("text", heard).put("heard", heard))
                return
            }

            // ---- Etapa 2: limpar e corrigir com os nomes da casa
            var text = TextCorrector.stripWakeWord(heard, backend.wakeNames)

            // Timers e alarmes: no próprio app (funcionam sem internet).
            if (text.isNotBlank()) {
                val reply = try { Timers.handle(text) } catch (e: Exception) { JLog.w("Timer", e); null }
                if (reply != null) {
                    JLog.i("Timer: “$text” → “${reply.speech}”")
                    onEvent(JSONObject().put("type", "transcription").put("text", text).put("heard", heard).put("local", "timer"))
                    onEvent(JSONObject().put("type", "response").put("text", reply.speech)
                        .put("response_type", if (reply.done) "action_done" else "query_answer")
                        .put("continue_conversation", reply.continueConversation))
                    val url = try { HaDirectBackend.speakOn(socket, 5, backend.pipelineId, reply.speech) } catch (e: Exception) { null }
                    if (url != null) onEvent(JSONObject().put("type", "audio").put("url", url))
                    return
                }
            }

            // Agenda: respondida pelo próprio app (antes da correção, para manter o título como foi falado).
            if (backend.calendarEnabled && text.isNotBlank()) {
                val answer = try {
                    CalendarAssistant(HomeApi(backend.base, backend.token), backend.calendarWrite)
                        .handle(text, java.time.LocalDateTime.now())
                } catch (e: Exception) {
                    JLog.w("Agenda indisponível", e)
                    CalendarAssistant.Answer("Não consegui acessar a agenda agora.", CalendarAssistant.Kind.ERROR)
                }
                if (answer != null) {
                    JLog.i("Agenda: “$text” → “${answer.speech}”")
                    onEvent(JSONObject().put("type", "transcription").put("text", text).put("heard", heard))
                    onEvent(JSONObject().put("type", "response").put("text", answer.speech)
                        .put("response_type", when (answer.kind) {
                            CalendarAssistant.Kind.DONE -> "action_done"
                            CalendarAssistant.Kind.INFO -> "query_answer"
                            CalendarAssistant.Kind.ERROR -> "error"
                        })
                        .put("continue_conversation", answer.continueConversation))
                    if (answer.changed) CalendarAssistant.onChange?.invoke()
                    val url = try { HaDirectBackend.speakOn(socket, 3, backend.pipelineId, answer.speech) } catch (e: Exception) { null }
                    if (url != null) onEvent(JSONObject().put("type", "audio").put("url", url))
                    return
                }
            }

            val spoken = text
            if (backend.correctText) {
                val fixed = TextCorrector.correct(text, backend.fullVocabulary())
                if (fixed != text) JLog.i("Correção: “$text” → “$fixed”")
                text = fixed
            }
            if (text.isBlank()) {
                onEvent(error(if (heard.isBlank()) "stt-no-text-recognized" else "only-wake-word",
                    "Não ouvi um comando"))
                return
            }
            // Luzes: resolvidas no próprio app (treino primeiro, depois a frase); o resto vai para a casa.
            if (backend.localLights && handleLight(socket, spoken, text, heard, onEvent)) return

            onEvent(JSONObject().put("type", "transcription").put("text", text).put("heard", heard))

            // ---- Etapa 3: texto → comando → resposta falada
            // Se a casa não entender, tenta de novo com o comando conhecido mais parecido.
            val retry = if (backend.correctText) CommandMatcher.suggest(text, backend.fullVocabulary()) else null
            if (runIntent(socket, 2, text, retry != null, onEvent) && retry != null && !cancelled) {
                JLog.i("2ª tentativa: “$text” → “$retry”")
                onEvent(JSONObject().put("type", "transcription").put("text", retry).put("heard", heard).put("retry", true))
                runIntent(socket, 3, retry, false, onEvent)
            }
        } catch (e: Exception) {
            if (cancelled) return
            throw e
        } finally {
            socket.close()
        }
    }

    private fun handleLight(socket: MiniWebSocket, spoken: String, text: String, heard: String,
                            onEvent: (JSONObject) -> Unit): Boolean {
        if (HaDirectBackend.lights.isEmpty()) {
            try { backend.refreshVocabulary() } catch (e: Exception) { JLog.w("Não li as luzes", e) }
        }
        val lights = HaDirectBackend.lights
        val action = LightCommands.resolve(spoken, lights, backend.trained)
            ?: (if (text != spoken) LightCommands.resolve(text, lights, backend.trained) else null)
            ?: return false
        val ids = JSONArray()
        for (l in action.lights) ids.put(l.entityId)
        val ok = try {
            HomeApi(backend.base, backend.token).callService("homeassistant",
                if (action.on) "turn_on" else "turn_off", JSONObject().put("entity_id", ids))
            true
        } catch (e: Exception) {
            JLog.w("Falha ao acionar a luz", e)
            false
        }
        val label = if (action.all) LightCommands.label(action.on, null)
            else action.lights.joinToString(" + ") { LightCommands.label(action.on, it) }
        JLog.i("Luz pelo app (${action.how}): “$spoken” → $label")
        val speech = if (ok) LightCommands.speech(action) else "Não consegui acionar a luz agora."
        onEvent(JSONObject().put("type", "transcription").put("text", text).put("heard", heard)
            .put("local", label + if (action.how == "treino") " (treino)" else ""))
        onEvent(JSONObject().put("type", "response").put("text", speech)
            .put("response_type", if (ok) "action_done" else "error").put("continue_conversation", false))
        if (ok) LightCommands.onChange?.invoke()
        val url = try { HaDirectBackend.speakOn(socket, 4, backend.pipelineId, speech) } catch (e: Exception) { null }
        if (url != null) onEvent(JSONObject().put("type", "audio").put("url", url))
        return true
    }

    /**
     * Roda intent→tts. Com [allowRetry], um "não entendi" da casa é guardado em silêncio e a função
     * devolve true para o chamador tentar outra frase; senão repassa tudo e devolve false.
     */
    private fun runIntent(socket: MiniWebSocket, id: Int, text: String, allowRetry: Boolean,
                          onEvent: (JSONObject) -> Unit): Boolean {
        socket.readTimeoutMs = (HaDirectBackend.RUN_TIMEOUT_MS + 5000).toInt()
        socket.sendText(runRequest(id, "intent", "tts", JSONObject().put("text", text), true).toString())
        val deadline = System.currentTimeMillis() + HaDirectBackend.RUN_TIMEOUT_MS
        var notUnderstood = false
        val held = ArrayList<JSONObject>()
        val emit: (JSONObject) -> Unit = { if (notUnderstood) held.add(it) else onEvent(it) }
        while (!cancelled) {
            if (System.currentTimeMillis() > deadline) {
                onEvent(error("timeout", "A casa demorou demais para responder"))
                return false
            }
            val msg = JSONObject(socket.readText())
            when (msg.optString("type")) {
                "result" -> if (msg.optInt("id") == id && !msg.optBoolean("success", false)) {
                    val err = msg.optJSONObject("error")
                    onEvent(error(err?.optString("code") ?: "ha-error", err?.optString("message") ?: "Falha na pipeline"))
                    return false
                }
                "event" -> {
                    if (msg.optInt("id") != id) continue
                    val ev = msg.optJSONObject("event") ?: continue
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    when (ev.optString("type")) {
                        "intent-end" -> {
                            val out = data.optJSONObject("intent_output") ?: JSONObject()
                            val response = out.optJSONObject("response") ?: JSONObject()
                            val speech = response.optJSONObject("speech")?.optJSONObject("plain")
                                ?.optString("speech") ?: ""
                            val rtype = response.optString("response_type")
                            val code = response.optJSONObject("data")?.optString("code") ?: ""
                            if (allowRetry && rtype == "error" && code in HaDirectBackend.RETRY_CODES) notUnderstood = true
                            val cid = out.optString("conversation_id", "")
                            if (cid.isNotEmpty()) {
                                HaDirectBackend.conversationId = cid
                                HaDirectBackend.conversationExpires =
                                    System.currentTimeMillis() + HaDirectBackend.CONVERSATION_TTL_MS
                            }
                            emit(JSONObject().put("type", "response").put("text", speech)
                                .put("response_type", rtype)
                                .put("continue_conversation", out.optBoolean("continue_conversation", false)))
                        }
                        "tts-end" -> {
                            val url = data.optJSONObject("tts_output")?.optString("url") ?: ""
                            if (url.isNotEmpty()) emit(JSONObject().put("type", "audio").put("url", url))
                        }
                        "error" -> {
                            onEvent(error(data.optString("code", "pipeline-error"), data.optString("message")))
                            return false
                        }
                        "run-end" -> {
                            if (notUnderstood) JLog.i("A casa não entendeu “$text”")
                            return notUnderstood
                        }
                    }
                }
            }
        }
        return false
    }

    /** Envia o áudio e devolve o texto reconhecido (null se houve erro já comunicado). */
    private fun transcribe(socket: MiniWebSocket, onEvent: (JSONObject) -> Unit): String? {
        socket.sendText(runRequest(1, "stt", "stt", JSONObject().put("sample_rate", 16000), false).toString())

        var handlerId = -1
        while (handlerId < 0) {
            val msg = JSONObject(socket.readText())
            when (msg.optString("type")) {
                "result" -> if (!msg.optBoolean("success", false)) {
                    val err = msg.optJSONObject("error")
                    onEvent(error(err?.optString("code") ?: "ha-error", err?.optString("message") ?: "Falha ao iniciar a pipeline"))
                    return null
                }
                "event" -> {
                    val ev = msg.optJSONObject("event") ?: continue
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    when (ev.optString("type")) {
                        "run-start" -> {
                            handlerId = data.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
                            if (handlerId < 0) {
                                onEvent(error("ha-error", "Pipeline sem etapa de fala"))
                                return null
                            }
                        }
                        "error" -> {
                            onEvent(error(data.optString("code", "pipeline-error"), data.optString("message")))
                            return null
                        }
                    }
                }
            }
        }

        socket.readTimeoutMs = 65000
        val sttDone = AtomicBoolean(false)
        val heard = AtomicReference<String?>(null)
        val failure = AtomicReference<JSONObject?>(null)
        val readerError = AtomicReference<Exception?>(null)
        val reader = Thread({
            try {
                loop@ while (!cancelled) {
                    val msg = JSONObject(socket.readText())
                    if (msg.optString("type") != "event") continue
                    val ev = msg.optJSONObject("event") ?: continue
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    when (ev.optString("type")) {
                        "stt-vad-end" -> sttDone.set(true)
                        "stt-end" -> {
                            sttDone.set(true)
                            heard.set(data.optJSONObject("stt_output")?.optString("text")?.trim() ?: "")
                        }
                        "error" -> {
                            sttDone.set(true)
                            failure.set(error(data.optString("code", "pipeline-error"), data.optString("message")))
                            break@loop
                        }
                        "run-end" -> break@loop
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) readerError.set(e)
            }
        }, "jarvis-ha-reader")
        reader.start()

        // Áudio: [id do canal] + PCM, até o app ou o VAD do HA encerrar a fala.
        val prefix = byteArrayOf(handlerId.toByte())
        var idleMs = 0L
        while (!cancelled && !sttDone.get() && reader.isAlive) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                idleMs += 200
                if (idleMs > 20000) break
                continue
            }
            if (chunk === EOF) break
            idleMs = 0
            socket.sendBinary(prefix + chunk)
        }
        if (!cancelled && reader.isAlive) {
            try { socket.sendBinary(prefix) } catch (_: Exception) {}
        }
        reader.join(HaDirectBackend.RUN_TIMEOUT_MS)
        if (cancelled) return null
        if (reader.isAlive) {
            onEvent(error("timeout", "A casa demorou demais para responder"))
            return null
        }
        failure.get()?.let { onEvent(it); return null }
        readerError.get()?.let {
            JLog.w("Conexão com o Home Assistant caiu durante a interação", it)
            onEvent(error("ha-disconnected", "Conexão com o Home Assistant caiu"))
            return null
        }
        return heard.get() ?: ""
    }

    companion object {
        private val EOF = ByteArray(0)
    }
}
