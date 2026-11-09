package br.com.jarvishome.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.domain.StateBus
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Reprodução da resposta do servidor (Piper) e da voz local de contingência.
 * Alimenta StateBus.mouthLevel com a energia real do áudio, para a boca do personagem.
 * Todos os métodos devem ser chamados na thread principal.
 */
class AudioPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var synthetic = false
    private var syntheticPhase = 0.0

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale("pt", "BR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    JLog.i("Voz local de contingência: " + if (ttsReady) "pronta" else "sem voz pt-BR instalada")
                }
            }
        } catch (e: Exception) {
            JLog.w("TextToSpeech indisponível", e)
        }
    }

    fun beep(enabled: Boolean) {
        if (!enabled) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
        } catch (e: Exception) {
            JLog.w("Falha no bipe", e)
        }
    }

    /** Toca o arquivo de resposta. onDone(true) ao terminar, onDone(false) se falhar. */
    fun play(file: File, volume: Float, onStart: () -> Unit, onDone: (Boolean) -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        var finished = false
        val finish = { ok: Boolean ->
            if (!finished) {
                finished = true
                releasePlayer()
                onDone(ok)
            }
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener {
                attachVisualizer(it.audioSessionId)
                it.start()
                onStart()
                startLevelLoop()
            }
            mp.setOnCompletionListener { finish(true) }
            mp.setOnErrorListener { _, what, extra ->
                JLog.e("MediaPlayer erro what=$what extra=$extra")
                finish(false)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            JLog.e("Não foi possível tocar a resposta", e)
            finish(false)
        }
    }

    /** Fala um texto com a voz do próprio Android (sem rede). Usado quando a casa não responde. */
    fun speakLocal(text: String, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ttsReady || text.isBlank()) {
            main.postDelayed({ onDone() }, 1800)
            return
        }
        synthetic = true
        startLevelLoop()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post {
                    synthetic = false
                    StateBus.mouthLevel = 0f
                    onDone()
                }
            }
            @Deprecated("API antiga")
            override fun onError(utteranceId: String?) {
                main.post {
                    synthetic = false
                    StateBus.mouthLevel = 0f
                    onDone()
                }
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-local")
    }

    private fun attachVisualizer(sessionId: Int) {
        try {
            val v = Visualizer(sessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform == null || waveform.isEmpty()) return
                    var sum = 0.0
                    for (b in waveform) {
                        val s = ((b.toInt() and 0xFF) - 128).toDouble()
                        sum += s * s
                    }
                    val rms = sqrt(sum / waveform.size) / 128.0
                    StateBus.mouthLevel = (rms * 3.2).toFloat().coerceIn(0f, 1f)
                }
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            v.enabled = true
            visualizer = v
            synthetic = false
        } catch (e: Exception) {
            // Sem Visualizer (alguns aparelhos): anima a boca de forma aproximada só enquanto há áudio.
            JLog.w("Visualizer indisponível, usando animação aproximada", e)
            synthetic = true
        }
    }

    private val levelLoop = object : Runnable {
        override fun run() {
            val playing = player?.isPlaying == true || synthetic
            if (!playing) {
                StateBus.mouthLevel = 0f
                return
            }
            if (synthetic) {
                syntheticPhase += 0.55
                val v = 0.35 + 0.3 * Math.sin(syntheticPhase) + 0.2 * Math.sin(syntheticPhase * 2.7)
                StateBus.mouthLevel = v.toFloat().coerceIn(0f, 1f)
            }
            main.postDelayed(this, 50)
        }
    }

    private fun startLevelLoop() {
        main.removeCallbacks(levelLoop)
        main.post(levelLoop)
    }

    private fun releasePlayer() {
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        try { player?.release() } catch (_: Exception) {}
        player = null
        synthetic = false
        StateBus.mouthLevel = 0f
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        try { tts?.stop() } catch (_: Exception) {}
        releasePlayer()
    }

    fun release() {
        stop()
        try { tone?.release() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
    }
}
