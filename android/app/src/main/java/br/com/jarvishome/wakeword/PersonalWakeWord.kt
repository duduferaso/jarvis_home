package br.com.jarvishome.wakeword

import android.content.Context
import br.com.jarvishome.domain.JLog
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Detector pessoal em tempo real:
 *  - Silero VAD recorta cada fala;
 *  - os primeiros 2,2 s da fala são comparados com os exemplos da casa (TemplateSet);
 *  - se bater, devolve o áudio DEPOIS da chamada (comando falado de uma vez) e o que veio
 *    depois do fim da fala (comando falado após uma pausa).
 * Tudo no aparelho; nenhum áudio sai antes de uma detecção.
 */
class PersonalWakeWord(context: Context, private val set: TemplateSet) {

    class Trigger(
        val distance: Float,
        val command: FloatArray,
        val afterSegment: FloatArray,
        /** A frase inteira, com a chamada (é o que vai para a transcrição: cortar no meio comia o "des" de "desliga"). */
        val whole: FloatArray,
        /** A pessoa já começou o comando depois de uma pausa curta (não tocar o bipe por cima). */
        val speechAfter: Boolean
    ) {
        /** "Hey Tony, liga a luz" numa frase só: o comando já está no áudio. */
        val oneBreath: Boolean get() = command.size > ONE_BREATH_MIN
    }

    private val vad: Vad = Vad(
        context.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "personal/silero_vad.onnx",
                threshold = 0.4f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 8f
            ),
            sampleRate = VoiceFeatures.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu"
        )
    )

    private val ring = FloatArray(RING)
    private var total = 0L       // amostras recebidas desde a criação
    private var vadOffset = 0L   // posição de "total" no último reset do VAD

    /** Distância da última fala avaliada (para o diagnóstico). */
    @Volatile var lastDistance = Float.NaN; private set
    private var lastNote = 0L
    val threshold: Float get() = set.threshold
    val size: Int get() = set.templates.size

    private fun write(f: FloatArray) {
        for (v in f) {
            ring[(total % RING).toInt()] = v
            total++
        }
    }

    private fun range(from: Long, to: Long): FloatArray {
        val a = max(from, max(0L, total - RING))
        val b = min(to, total)
        if (b <= a) return FloatArray(0)
        return FloatArray((b - a).toInt()) { ring[((a + it) % RING).toInt()] }
    }

    fun accept(frame: FloatArray, n: Int): Trigger? {
        val f = if (n == frame.size) frame.copyOf() else frame.copyOf(n)
        write(f)
        vad.acceptWaveform(f)
        var trigger: Trigger? = null
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            if (trigger != null) continue
            val samples = seg.samples
            if (samples.size < MIN_SEGMENT) continue
            val segStart = vadOffset + seg.start
            val pre = range(segStart - PREROLL, segStart)
            val crop = pre + samples.copyOf(min(samples.size, CROP))
            val feats = VoiceFeatures.compute(crop)
            val score = set.score(feats.frames)
            lastDistance = score.distance
            // registro para entender quando a chamada não pega (ex.: TV falando junto)
            val secs = samples.size / 16000f
            val now = System.currentTimeMillis()
            if (score.distance > set.threshold && now - lastNote > 8000 &&
                (score.distance <= set.threshold + 0.06f || secs > 3f)) {
                lastNote = now
                JLog.i(String.format(java.util.Locale.US, "Chamada não reconhecida: distância %.3f (limiar %.3f), fala de %.1f s%s",
                    score.distance, set.threshold, secs, if (secs > 3f) " — fala longa: barulho/TV junto?" else ""))
            }
            if (score.distance <= set.threshold) {
                val endFrame = if (score.endFrame in 1..feats.frameIndex.size) feats.frameIndex[score.endFrame - 1] else 0
                val wakeEndInSeg = max(0, endFrame * VoiceFeatures.HOP + 400 - pre.size)
                val command = if (wakeEndInSeg < samples.size) samples.copyOfRange(wakeEndInSeg, samples.size) else FloatArray(0)
                val after = range(segStart + samples.size, total)
                trigger = Trigger(score.distance, command, after, pre.copyOfRange(maxOf(0, pre.size - 1600), pre.size) + samples,
                    peakDb(after) > peakDb(samples) - 20.0)
            }
        }
        return trigger
    }

    private fun peakDb(a: FloatArray): Double {
        var best = -120.0
        var i = 0
        while (i + 800 <= a.size) {
            var s = 0.0
            for (k in i until i + 800) s += (a[k] * a[k]).toDouble()
            best = max(best, 10 * kotlin.math.log10(s / 800 + 1e-12))
            i += 800
        }
        return best
    }

    /** Chamar ao voltar à espera (o áudio no meio da conversa não foi analisado). */
    fun reset() {
        vad.reset()
        vadOffset = total
    }

    fun release() {
        try { vad.release() } catch (_: Exception) {}
    }

    companion object {
        private const val RING = 16000 * 6
        private const val PREROLL = 2400            // 0,15 s antes da fala (igual ao treino)
        private const val CROP = 35200              // 2,2 s analisados
        private const val MIN_SEGMENT = 5600        // 0,35 s
        private const val ONE_BREATH_MIN = 8000     // 0,5 s de fala depois da chamada
    }
}

/** Onde ficam os exemplos de voz: os treinados no aparelho ou os de fábrica (gravação da casa). */
object PersonalStore {
    private const val FILE = "personal/vozes.tpl"
    private const val FACTORY = "personal/hey_tony.tpl"
    const val MAX_TEMPLATES = 40
    const val PHRASE = "Hey Tony"

    fun hasCustom(context: Context) = File(context.filesDir, FILE).exists()

    fun load(context: Context): TemplateSet {
        val f = File(context.filesDir, FILE)
        return try {
            if (f.exists()) f.inputStream().use { TemplateSet.read(it) }
            else context.assets.open(FACTORY).use { TemplateSet.read(it) }
        } catch (e: Exception) {
            JLog.e("Exemplos de voz corrompidos; usando os de fábrica", e)
            context.assets.open(FACTORY).use { TemplateSet.read(it) }
        }
    }

    fun save(context: Context, set: TemplateSet) {
        while (set.templates.size > MAX_TEMPLATES) set.templates.removeAt(0) // descarta os mais antigos
        val f = File(context.filesDir, FILE)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "vozes.tmp")
        tmp.outputStream().use { set.write(it) }
        tmp.renameTo(f)
    }

    fun resetToFactory(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
