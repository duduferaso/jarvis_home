package br.com.jarvishome.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * VAD por energia com piso de ruído adaptativo. Simples, barato e suficiente para
 * decidir o fim do comando; o Home Assistant também aplica seu próprio VAD no servidor.
 */
class VoiceActivityDetector(var silenceMs: Int, private val frameMs: Int = 100) {

    enum class Result { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_LENGTH }

    private var noiseFloor = 250.0
    private var speechStarted = false
    private var silenceAcc = 0
    private var elapsed = 0
    private var speechFrames = 0

    var maxUtteranceMs = 12000
    var noSpeechTimeoutMs = 5000

    /** Atualiza o piso de ruído; chamar só enquanto ninguém está falando com o Jarvis. */
    fun learnNoise(rms: Double) {
        noiseFloor = (0.97 * noiseFloor + 0.03 * rms).coerceIn(60.0, 4000.0)
    }

    /** Para quando o usuário já falou o comando (frase única): termina no próximo silêncio. */
    fun startAfterSpeech() {
        start()
        speechStarted = true
    }

    fun start() {
        speechStarted = false
        silenceAcc = 0
        elapsed = 0
        speechFrames = 0
    }

    fun process(rms: Double): Result {
        elapsed += frameMs
        val threshold = max(noiseFloor * 2.8, 350.0)
        if (rms > threshold) {
            speechFrames++
            if (speechFrames >= 2) speechStarted = true
            silenceAcc = 0
        } else {
            speechFrames = 0
            if (speechStarted) silenceAcc += frameMs
        }
        return when {
            speechStarted && silenceAcc >= silenceMs -> Result.END_OF_SPEECH
            !speechStarted && elapsed >= noSpeechTimeoutMs -> Result.NO_SPEECH
            elapsed >= maxUtteranceMs -> Result.MAX_LENGTH
            else -> Result.CONTINUE
        }
    }

    val heardSpeech: Boolean get() = speechStarted

    companion object {
        fun rms(buf: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val s = buf[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / n)
        }

        /** 0..1 para o medidor da interface (≈ -60 dBFS .. 0 dBFS). */
        fun level(rms: Double): Float {
            if (rms < 1.0) return 0f
            val db = 20.0 * log10(rms / 32768.0)
            return ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
