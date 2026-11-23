package br.com.jarvishome.wakeword

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detector pessoal: compara o "desenho" do som de cada fala com exemplos gravados pelas pessoas
 * da casa (DTW sobre log-mel). Não depende de idioma nem de sotaque.
 *
 * Validado com a gravação real ("Hey Tony" pela casa, 2 vozes, de -34 a -51 dBFS):
 *  - modelo em inglês: 2/13 chamadas;  detector pessoal: 13/13, 0 disparos em 172 trechos negativos;
 *  - falando o comando emendado: 12/13 com DTW de prefixo, 0 disparos em 105 trechos.
 * Lógica pura (sem Android), idêntica ao protótipo em Python; testável na JVM.
 */
object VoiceFeatures {
    const val SAMPLE_RATE = 16000
    const val N_MELS = 40
    private const val N_FFT = 512
    private const val WIN = 400   // 25 ms
    const val HOP = 160           // 10 ms

    private val window = FloatArray(WIN) { (0.54 - 0.46 * cos(2 * PI * it / (WIN - 1))).toFloat() }
    private val melBank: Array<FloatArray> = buildMelBank()

    private fun buildMelBank(): Array<FloatArray> {
        fun mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun hz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
        val lo = mel(60.0); val hi = mel(7600.0)
        val bins = IntArray(N_MELS + 2) { i ->
            val p = hz(lo + (hi - lo) * i / (N_MELS + 1))
            floor((N_FFT + 1) * p / SAMPLE_RATE).toInt()
        }
        return Array(N_MELS) { m ->
            val row = FloatArray(N_FFT / 2 + 1)
            val l = bins[m]; val c = bins[m + 1]; val r = bins[m + 2]
            for (k in l until c) row[k] = ((k - l).toFloat() / max(1, c - l))
            for (k in c until r) row[k] = ((r - k).toFloat() / max(1, r - c))
            row
        }
    }

    /** FFT radix-2 in-place (re, im). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    class Result(val frames: Array<FloatArray>, val frameIndex: IntArray)

    /**
     * Log-mel de 40 bandas (25 ms / 10 ms), só dos quadros com voz (até 6 dB abaixo do mais forte),
     * com a média do trecho subtraída e cada quadro normalizado (independe de volume e microfone).
     */
    fun compute(audio: FloatArray): Result {
        if (audio.isEmpty()) return Result(emptyArray(), IntArray(0))
        val a = FloatArray(audio.size)
        a[0] = audio[0]
        for (i in 1 until audio.size) a[i] = audio[i] - 0.97f * audio[i - 1]
        val n = 1 + max(0, (a.size - WIN) / HOP)
        val logMel = Array(n) { FloatArray(N_MELS) }
        val energy = FloatArray(n)
        val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
        val power = DoubleArray(N_FFT / 2 + 1)
        for (f in 0 until n) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            val off = f * HOP
            for (k in 0 until WIN) {
                val idx = off + k
                re[k] = if (idx < a.size) (a[idx] * window[k]).toDouble() else 0.0
            }
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            var sum = 0.0
            for (m in 0 until N_MELS) {
                var e = 0.0
                val row = melBank[m]
                for (k in row.indices) if (row[k] != 0f) e += power[k] * row[k]
                val v = ln(e + 1e-6).toFloat()
                logMel[f][m] = v
                sum += v
            }
            energy[f] = (sum / N_MELS).toFloat()
        }
        val maxE = energy.maxOrNull() ?: 0f
        var keep = (0 until n).filter { energy[it] > maxE - 6f }
        if (keep.size <= 10) keep = (0 until n).toList()
        val out = Array(keep.size) { logMel[keep[it]].copyOf() }
        val mean = FloatArray(N_MELS)
        for (row in out) for (m in 0 until N_MELS) mean[m] += row[m]
        for (m in 0 until N_MELS) mean[m] = mean[m] / max(1, out.size)
        for (row in out) {
            var norm = 0.0
            for (m in 0 until N_MELS) { row[m] -= mean[m]; norm += (row[m] * row[m]).toDouble() }
            val inv = (1.0 / (sqrt(norm) + 1e-6)).toFloat()
            for (m in 0 until N_MELS) row[m] *= inv
        }
        return Result(out, keep.toIntArray())
    }
}

object Dtw {
    private fun dist(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return 1f - d
    }

    /** DTW completa, normalizada pelo tamanho do caminho (n + m). */
    fun full(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        return prefix(a, b, openEnd = false).distance
    }

    class Match(val distance: Float, val endFrame: Int)

    /**
     * Encaixa o modelo [t] no começo do trecho [s]; o fim do encaixe é livre (de n/2 até 2n quadros),
     * para reconhecer "Hey Tony, liga a luz" falado de uma vez.
     */
    fun prefix(t: Array<FloatArray>, s: Array<FloatArray>, openEnd: Boolean = true): Match {
        val n = t.size; val m = s.size
        if (n == 0 || m == 0) return Match(Float.MAX_VALUE, 0)
        var prev = FloatArray(m + 1) { Float.POSITIVE_INFINITY }
        var cur = FloatArray(m + 1)
        prev[0] = 0f
        for (i in 1..n) {
            cur[0] = Float.POSITIVE_INFINITY
            val ti = t[i - 1]
            for (j in 1..m) {
                val best = min(prev[j], min(cur[j - 1], prev[j - 1]))
                cur[j] = dist(ti, s[j - 1]) + best
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        if (!openEnd) return Match(prev[m] / (n + m), m)
        var bestD = Float.MAX_VALUE; var bestJ = m
        for (j in max(1, n / 2)..min(m, 2 * n)) {
            val d = prev[j] / (n + j)
            if (d < bestD) { bestD = d; bestJ = j }
        }
        return Match(bestD, bestJ)
    }
}

/**
 * Chamada no FIM de um trecho de fala ("…[TV/conversa] Hey Tony" sem pausa antes): compara os últimos
 * 1,9 s com os exemplos de trás para frente (o fim do exemplo encaixa no fim do trecho).
 */
object SegmentEnd {
    const val WINDOW = 30400          // 1,9 s
    const val STRICT = 0.95f          // um pouco mais exigente que a chamada no começo

    fun score(set: TemplateSet, trainedCount: Int, samples: FloatArray): TemplateSet.Score {
        val win = samples.copyOfRange(maxOf(0, samples.size - WINDOW), samples.size) + FloatArray(2400)
        val rf = VoiceFeatures.compute(win).frames.reversedArray()
        var best = TemplateSet.Score(Float.MAX_VALUE, 0)
        for (i in 0 until minOf(trainedCount, set.templates.size)) {
            val m = Dtw.prefix(set.templates[i].reversedArray(), rf)
            if (m.distance < best.distance) best = TemplateSet.Score(m.distance, m.endFrame, i)
        }
        return best
    }
}

/**
 * Recorte de uma gravação do treino igual ao que o detector vê ao vivo: 0,15 s antes + só o trecho de fala
 * (o maior que o Silero achou), até 1,9 s. Antes o exemplo era a gravação inteira de 2,4 s: com TV ou barulho,
 * o exemplo carregava o som de fundo e quase não servia (medido: 0,15–0,21 recortado vs 0,27–0,34 inteiro).
 */
object TakeTrim {
    const val PRE = 2400

    fun cut(take: FloatArray, segments: List<Pair<Int, FloatArray>>): FloatArray {
        val best = segments.filter { it.second.size >= 4000 }.maxByOrNull { it.second.size } ?: return take
        val start = best.first.coerceIn(0, take.size)
        val pre = take.copyOfRange(maxOf(0, start - PRE), start)
        val pad = if (pre.size < PRE) FloatArray(PRE - pre.size) else FloatArray(0)
        return pad + pre + best.second.copyOf(minOf(best.second.size, SegmentEnd.WINDOW))
    }
}

/** Exemplos de voz + limiar. Formato binário simples, versionado. */
class TemplateSet(val templates: MutableList<Array<FloatArray>>, var threshold: Float) {

    class Score(val distance: Float, val endFrame: Int, val index: Int = -1)

    /** Menor distância de [features] a qualquer exemplo (encaixe no começo do trecho). */
    fun score(features: Array<FloatArray>, exclude: Int = -1, from: Int = 0, to: Int = templates.size): Score {
        var best = Score(Float.MAX_VALUE, 0)
        for (i in from until minOf(to, templates.size)) {
            if (i == exclude) continue
            val m = Dtw.prefix(templates[i], features)
            if (m.distance < best.distance) best = Score(m.distance, m.endFrame, i)
        }
        return best
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC); d.writeInt(1); d.writeFloat(threshold); d.writeInt(templates.size)
        for (t in templates) {
            d.writeInt(t.size); d.writeInt(VoiceFeatures.N_MELS)
            for (row in t) for (v in row) d.writeFloat(v)
        }
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x4A525650 // "JRVP"
        const val DEFAULT_THRESHOLD = 0.22f

        fun read(input: InputStream): TemplateSet {
            val d = DataInputStream(input)
            require(d.readInt() == MAGIC) { "arquivo de voz inválido" }
            d.readInt()
            val thr = d.readFloat()
            val count = d.readInt()
            val list = ArrayList<Array<FloatArray>>()
            repeat(count) {
                val frames = d.readInt(); val dims = d.readInt()
                list.add(Array(frames) { FloatArray(dims) { d.readFloat() } })
            }
            return TemplateSet(list, thr)
        }
    }
}

/**
 * Escolhe o limiar a partir das próprias gravações: distância de cada exemplo aos outros
 * ("deixa um de fora") contra a distância da fala normal da casa.
 */
object PersonalTrainer {
    const val MARGIN = 0.03f
    /** Limiar máximo escolhido automaticamente (nas gravações da casa a fala/TV mais parecida fica em ~0,22–0,25). */
    const val MAX_AUTO = 0.20f

    class Report(val threshold: Float, val recognized: Int, val total: Int, val falseHits: Int, val worstPositive: Float, val bestNegative: Float)

    fun choose(set: TemplateSet, newIndices: List<Int>, negatives: List<Array<FloatArray>>): Report {
        val pos = (if (newIndices.isEmpty()) set.templates.indices.toList() else newIndices)
            .map { set.score(set.templates[it], exclude = it).distance }
            .filter { it < Float.MAX_VALUE }
        val neg = negatives.map { set.score(it).distance }
        val worstPos = pos.maxOrNull() ?: TemplateSet.DEFAULT_THRESHOLD
        val bestNeg = neg.minOrNull() ?: 1f
        // Folga de 0,03 abaixo da fala/TV mais parecida e no máximo 0,20. Antes: meio-termo até 0,26 ou
        // 0,01 abaixo da TV — treinando longe com TV o limiar ia a ~0,22 e passou a ativar sozinho.
        val safe = bestNeg - MARGIN
        val thr = (if (worstPos < safe) minOf((worstPos + bestNeg) / 2f, safe) else safe).coerceIn(0.14f, MAX_AUTO)
        return Report(thr, pos.count { it <= thr }, pos.size, neg.count { it <= thr }, worstPos, bestNeg)
    }
}
