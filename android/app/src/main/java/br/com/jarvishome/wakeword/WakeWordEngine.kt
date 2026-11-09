package br.com.jarvishome.wakeword

import android.content.Context
import br.com.jarvishome.domain.JLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.Locale

/** Interface desacoplada: dá para trocar o motor sem mexer no serviço. */
interface WakeWordEngine {
    /** Recebe áudio 16 kHz normalizado (-1..1). Devolve o rótulo detectado ou null. */
    fun accept(samples: FloatArray): String?
    fun reset()
    fun release()
}

/**
 * Detecção local de "Jarvis" com sherpa-onnx (keyword spotting, zipformer gigaspeech 3.3M).
 * Todo o processamento é no aparelho; nenhum áudio sai do Redmi neste estágio.
 */
class SherpaWakeWord(context: Context, keywords: String, threshold: Float, boost: Float = 1.5f) : WakeWordEngine {

    private val spotter: KeywordSpotter
    private var stream: OnlineStream

    init {
        val lines = keywords.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        spotter = createSpotter(context, lines, threshold, boost, "keywords.txt")
        stream = spotter.createStream("")
        JLog.i("Wake word pronto: " + lines.size + " variações, limiar=" +
            String.format(Locale.US, "%.2f", threshold) + ", reforço=" + boost)
    }

    companion object {
        fun createSpotter(context: Context, lines: List<String>, threshold: Float, boost: Float, fileName: String): KeywordSpotter {
            val dir = ModelInstaller.ensure(context)
            val kwFile = File(dir, fileName)
            kwFile.writeText(lines.joinToString("\n") + "\n")
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0.0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.onnx").absolutePath,
                        decoder = File(dir, "decoder.onnx").absolutePath,
                        joiner = File(dir, "joiner.onnx").absolutePath
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 1,
                    provider = "cpu"
                ),
                maxActivePaths = 4,
                keywordsFile = kwFile.absolutePath,
                keywordsScore = boost,
                keywordsThreshold = threshold,
                numTrailingBlanks = 1
            )
            return KeywordSpotter(config = config)
        }

        /**
         * Testa palavras-chave específicas (separadas por "/", com limiar próprio "#0.15") num trecho
         * gravado, sem recarregar o modelo. Para no primeiro acerto.
         */
        fun detectAny(spotter: KeywordSpotter, keywords: String, audio: FloatArray): Boolean {
            val s = spotter.createStream(keywords)
            try {
                val padded = audio + FloatArray(16000)
                var i = 0
                while (i < padded.size) {
                    val end = minOf(i + 1600, padded.size)
                    s.acceptWaveform(padded.copyOfRange(i, end), 16000)
                    while (spotter.isReady(s)) {
                        spotter.decode(s)
                        if (spotter.getResult(s).keyword.isNotEmpty()) return true
                    }
                    i = end
                }
                return false
            } finally {
                s.release()
            }
        }

        /** Roda o detector sobre um trecho gravado (com silêncio no fim) e devolve os rótulos encontrados. */
        fun detectAll(spotter: KeywordSpotter, audio: FloatArray): Set<String> {
            val s = spotter.createStream("")
            val hits = HashSet<String>()
            val padded = audio + FloatArray(16000)
            var i = 0
            while (i < padded.size) {
                val end = minOf(i + 1600, padded.size)
                s.acceptWaveform(padded.copyOfRange(i, end), 16000)
                while (spotter.isReady(s)) {
                    spotter.decode(s)
                    val kw = spotter.getResult(s).keyword
                    if (kw.isNotEmpty()) {
                        hits.add(kw)
                        spotter.reset(s)
                    }
                }
                i = end
            }
            s.release()
            return hits
        }
    }

    override fun accept(samples: FloatArray): String? {
        stream.acceptWaveform(samples, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val kw = spotter.getResult(stream).keyword
            if (kw.isNotEmpty()) {
                spotter.reset(stream)
                return kw
            }
        }
        return null
    }

    override fun reset() {
        stream.release()
        stream = spotter.createStream("")
    }

    override fun release() {
        try { stream.release() } catch (_: Exception) {}
        try { spotter.release() } catch (_: Exception) {}
    }
}

/** Copia o modelo dos assets para o armazenamento interno (uma vez por versão). */
object ModelInstaller {
    private const val VERSION = "kws-gigaspeech-3.3M-v1"
    private val FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    fun ensure(context: Context): File {
        val dir = File(context.filesDir, VERSION)
        val marker = File(dir, ".ok")
        if (marker.exists()) return dir
        dir.mkdirs()
        for (name in FILES) {
            context.assets.open("kws/$name").use { input ->
                File(dir, name).outputStream().use { out -> input.copyTo(out) }
            }
        }
        marker.writeText("ok")
        JLog.i("Modelo de wake word instalado em " + dir.absolutePath)
        return dir
    }
}
