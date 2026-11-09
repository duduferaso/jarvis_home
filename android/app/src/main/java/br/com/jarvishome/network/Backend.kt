package br.com.jarvishome.network

import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.settings.Prefs
import br.com.jarvishome.wakeword.CalibrationAnalyzer
import br.com.jarvishome.wakeword.WakeWords
import org.json.JSONObject
import java.io.File

/**
 * Para onde o terminal manda a voz. Duas implementações:
 *  - HaDirectBackend: fala direto com o WebSocket do Home Assistant (padrão);
 *  - JarvisApi: passa pelo orquestrador opcional no servidor.
 * Ambas entregam os mesmos eventos ao serviço: transcription, response, audio, error.
 */
interface Backend {
    fun health(): ConnectionState
    fun newSession(): Session
    fun download(path: String, dest: File)
}

interface Session {
    val cancelled: Boolean
    fun push(pcm: ByteArray)
    fun finish()
    fun cancel()
    /** Bloqueante. Lança exceção se não alcançar o servidor. */
    fun run(onEvent: (JSONObject) -> Unit)
}

object Backends {
    fun create(prefs: Prefs): Backend =
        if (prefs.directMode) HaDirectBackend(
            prefs.serverUrl, prefs.token, prefs.pipelineId,
            wakeNames = (if (prefs.personalDetector) WakeWords.TONY else WakeWords.current(prefs)).let { p ->
                listOf(p.display, p.display.removePrefix("Hey ")).distinct() + p.defaultLines.map { CalibrationAnalyzer.labelOf(it).replace('_', ' ') }
            },
            extraVocabulary = prefs.extraVocabulary.lines().map { it.trim() }.filter { it.length >= 3 },
            correctText = prefs.correctText,
            calendarEnabled = prefs.calendarEnabled,
            calendarWrite = prefs.calendarWrite,
            localLights = prefs.localLights,
            trained = br.com.jarvishome.commands.LightCommands.decode(prefs.trainedCommands),
            workoutPlan = prefs.workoutPlan()
        )
        else JarvisApi(prefs)
}
