package br.com.jarvishome.network

import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.settings.Prefs
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Modo opcional: protocolo v1 com o orquestrador no Debian (só rede local, HTTP simples):
 *   GET  /v1/health
 *   POST /v1/voice?rate=16000   corpo PCM s16le mono em streaming → resposta NDJSON
 *   GET  /v1/audio/{id}
 */
class JarvisApi(private val prefs: Prefs) : Backend {

    private fun open(path: String): HttpURLConnection {
        val conn = URL(prefs.serverUrl + path).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer " + prefs.token)
        conn.setRequestProperty("X-Terminal-Id", prefs.terminalId)
        conn.setRequestProperty("X-Jarvis-Protocol", "1")
        conn.useCaches = false
        return conn
    }

    override fun health(): ConnectionState {
        if (!prefs.isConfigured) return ConnectionState.NOT_CONFIGURED
        var conn: HttpURLConnection? = null
        return try {
            conn = open("/v1/health")
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    if (JSONObject(body).optBoolean("home_assistant", false)) ConnectionState.CONNECTED
                    else ConnectionState.HA_DOWN
                }
                401 -> ConnectionState.UNAUTHORIZED
                else -> ConnectionState.DISCONNECTED
            }
        } catch (e: Exception) {
            ConnectionState.DISCONNECTED
        } finally {
            conn?.disconnect()
        }
    }

    override fun newSession(): Session = VoiceSession(this)

    internal fun openVoice(): HttpURLConnection {
        val conn = open("/v1/voice?rate=16000")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setChunkedStreamingMode(3200)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.connectTimeout = 3000
        conn.readTimeout = 45000
        return conn
    }

    override fun download(path: String, dest: File) {
        val conn = open(path)
        conn.connectTimeout = 3000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP " + conn.responseCode + " ao baixar áudio")
            conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Uma interação. A thread de áudio chama push() a cada quadro; a thread da sessão
 * envia em streaming e, ao fim do upload, lê os eventos do servidor linha a linha.
 */
class VoiceSession(private val api: JarvisApi) : Session {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile override var cancelled = false; private set
    @Volatile var uploadedBytes = 0L; private set

    override fun push(pcm: ByteArray) { if (!cancelled) queue.offer(pcm) }
    override fun finish() { queue.offer(EOF) }

    override fun cancel() {
        cancelled = true
        queue.clear()
        queue.offer(EOF)
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    /** Bloqueante. Lança IOException se não alcançar o servidor. */
    override fun run(onEvent: (JSONObject) -> Unit) {
        val c = api.openVoice()
        conn = c
        try {
            c.outputStream.use { out ->
                while (true) {
                    val chunk = queue.poll(20, TimeUnit.SECONDS) ?: break
                    if (chunk === EOF) break
                    out.write(chunk)
                    out.flush()
                    uploadedBytes += chunk.size
                }
            }
            if (cancelled) return
            val code = c.responseCode
            if (code == 401) {
                onEvent(JSONObject().put("type", "error").put("code", "unauthorized")
                    .put("message", "Token do terminal recusado"))
                return
            }
            if (code != 200) throw IOException("HTTP $code")
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (!cancelled) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        onEvent(JSONObject(line))
                    } catch (e: Exception) {
                        JLog.w("Evento inválido ignorado: " + line.take(80))
                    }
                }
            }
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        private val EOF = ByteArray(0)
    }
}
