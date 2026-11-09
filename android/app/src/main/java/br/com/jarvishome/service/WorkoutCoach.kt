package br.com.jarvishome.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import br.com.jarvishome.commands.Workout
import br.com.jarvishome.commands.Workout.Phase
import br.com.jarvishome.domain.JLog
import java.util.Locale

/**
 * Avisos sonoros do treino: bip nos 3 últimos segundos de cada fase, bip longo + nome do
 * exercício na execução, "Descanso. Próximo: …" no descanso, metade e fim do treino.
 * Usa a voz do próprio Android (instantânea, sem rede).
 */
class WorkoutCoach(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastKey = ""
    private var lastTick = -1L
    private var doneAt = 0L
    private var running = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale("pt", "BR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    tts?.setSpeechRate(1.1f)
                }
            }
        } catch (e: Exception) { JLog.w("Treino: sem voz local", e) }
    }

    private val loop = object : Runnable {
        override fun run() {
            try { step() } catch (e: Exception) { JLog.w("Treino", e) }
            if (Workout.active) main.postDelayed(this, 200) else { running = false; lastKey = "" }
        }
    }

    /** Chamado quando o treino muda (começou, pausou…). */
    fun kick() {
        if (Workout.active && !running) { running = true; lastKey = ""; lastTick = -1; doneAt = 0; main.post(loop) }
    }

    private fun beep(kind: Int, ms: Int) {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            tone?.startTone(kind, ms)
        } catch (_: Exception) {}
    }

    private fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "treino")
    }

    private fun step() {
        val s = Workout.state() ?: return
        if (s.paused) return
        val key = s.phase.name + s.round
        if (key != lastKey) {
            lastKey = key
            lastTick = -1
            when (s.phase) {
                Phase.PREP -> {}
                Phase.WORK -> {
                    beep(ToneGenerator.TONE_PROP_BEEP2, 500)
                    // começo de série: "Série 2 de 5" (o exercício aparece na tela)
                    val newSeries = s.exerciseCount > 0 && s.exerciseIndex == 1
                    // só por voz: diz em que exercício e série você está
                    val ex = when {
                        s.exercise.isNotEmpty() -> s.exercise + "."
                        s.exerciseCount > 0 -> "Exercício ${s.exerciseIndex}."
                        else -> "Rodada ${s.round}."
                    }
                    val msg = when {
                        newSeries && s.lap == s.laps && s.laps > 1 -> "Última série! $ex"
                        newSeries -> "Série ${s.lap} de ${s.laps}. $ex"
                        s.round == s.rounds -> "Último exercício!"
                        else -> ex
                    }
                    if (msg.isNotEmpty()) main.postDelayed({ say(msg) }, 450)
                }
                Phase.REST -> {
                    beep(ToneGenerator.TONE_PROP_ACK, 300)
                    val endSeries = s.exerciseCount > 0 && s.exerciseIndex == s.exerciseCount
                    main.postDelayed({ say(if (endSeries) "Fim da série ${s.lap}. Descanso." else "Descanso.") }, 350)
                }
                Phase.DONE -> {
                    doneAt = System.currentTimeMillis()
                    beep(ToneGenerator.TONE_CDMA_CONFIRM, 900)
                    main.postDelayed({ say("Treino concluído! ${s.rounds} rodadas. Mandou bem!") }, 900)
                    JLog.i("Treino concluído (${s.rounds} rodadas)")
                }
            }
        }
        // 3, 2, 1
        if (s.phase != Phase.DONE) {
            val left = (s.phaseLeftMs + 999) / 1000
            if (left in 1..3 && left != lastTick && s.phaseLenMs >= 8000) {
                lastTick = left
                beep(ToneGenerator.TONE_PROP_BEEP, 120)
            }
        } else if (System.currentTimeMillis() - doneAt > 120_000) {
            Workout.stop() // some da tela 2 min depois do fim
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        try { tts?.shutdown() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
    }
}
