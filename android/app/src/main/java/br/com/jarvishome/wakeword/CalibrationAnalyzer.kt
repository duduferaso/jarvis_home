package br.com.jarvishome.wakeword

import br.com.jarvishome.settings.Prefs
import java.util.Locale

/**
 * Escolhe as variações (grafias) e a sensibilidade que reconhecem a voz do usuário sem disparar
 * na fala normal. Lógica pura (sem Android), testável na JVM; o detector real é injetado.
 *
 * Método (v1.3.1). Testar todas as variações juntas fazia elas competirem no detector:
 * Alexa caía de 7/8 (cada uma sozinha) para 3/8 (todas juntas). Agora:
 *  1) cada variação é testada SOZINHA;
 *  2) cada uma recebe a sensibilidade mais conservadora que ainda reconhece o máximo de gravações
 *     sem nenhum disparo na fala normal (limiar próprio por variação);
 *  3) junta no máximo [MAX_KEYWORDS], aceitando uma nova só se, rodando EM CONJUNTO, aumentar os
 *     acertos sem disparo falso. O resultado informado é o da verificação conjunta (uso real).
 */
object CalibrationAnalyzer {

    data class Result(
        val keywordLines: List<String>,
        val coverage: Int,
        val total: Int,
        val testedVariants: Int,
        val usefulVariants: Int
    )

    /** Sensibilidades testadas, da mais conservadora para a mais sensível. */
    val SENSITIVITIES = listOf(4, 6, 8, 10)
    const val MAX_KEYWORDS = 3
    const val BOOST = 1.5f

    fun labelOf(line: String): String = line.substringAfterLast('@').trim()

    /** Linha no formato do sherpa-onnx com limiar próprio: "TOKENS :1.5 #0.15 @RÓTULO". */
    fun lineWith(candidate: String, sensitivity: Int): String {
        val tokens = candidate.substringBeforeLast('@').trim()
            .split(' ').filter { it.isNotEmpty() && !it.startsWith(":") && !it.startsWith("#") }
            .joinToString(" ")
        return tokens + " :" + String.format(Locale.US, "%.1f", BOOST) +
            " #" + String.format(Locale.US, "%.3f", Prefs.thresholdFor(sensitivity)) + " @" + labelOf(candidate)
    }

    /**
     * @param detect (palavras-chave separadas por "/", áudio) -> true se alguma foi detectada.
     *               O app carrega o modelo uma vez e só troca as palavras de cada teste.
     */
    fun analyze(
        positives: List<FloatArray>,
        negatives: List<FloatArray>,
        candidates: List<String>,
        detect: (String, FloatArray) -> Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result? {
        class Option(val candidate: String, val sensitivity: Int, val hits: Set<Int>)

        fun hitsOf(keywords: String): Set<Int> = positives.indices.filter { detect(keywords, positives[it]) }.toSet()
        fun firesOnSpeech(keywords: String): Boolean = negatives.any { detect(keywords, it) }

        val options = ArrayList<Option>()
        val total = candidates.size + 1
        candidates.forEachIndexed { idx, cand ->
            onProgress(idx, total)
            // Passo 1: na sensibilidade máxima, reconhece alguma gravação? Se não, descarta já.
            if (hitsOf(lineWith(cand, SENSITIVITIES.last())).isEmpty()) return@forEachIndexed
            // Passo 2: da mais conservadora para a mais sensível; para quando começar a disparar na fala.
            var best: Option? = null
            for (s in SENSITIVITIES) {
                val line = lineWith(cand, s)
                val hits = hitsOf(line)
                if (hits.size <= (best?.hits?.size ?: 0)) continue
                if (firesOnSpeech(line)) break
                best = Option(cand, s, hits)
            }
            best?.let { options.add(it) }
        }
        onProgress(candidates.size, total)
        if (options.isEmpty()) return null

        // Passo 3: monta o conjunto final verificando em conjunto.
        var chosen = emptyList<String>()
        var covered = 0
        for (opt in options.sortedWith(compareByDescending<Option> { it.hits.size }.thenBy { it.sensitivity })) {
            if (chosen.size >= MAX_KEYWORDS) break
            val trial = chosen + lineWith(opt.candidate, opt.sensitivity)
            val joined = trial.joinToString("/")
            val cov = hitsOf(joined).size
            if (cov > covered && !firesOnSpeech(joined)) {
                chosen = trial
                covered = cov
            }
        }
        onProgress(total, total)
        if (chosen.isEmpty()) return null
        return Result(chosen, covered, positives.size, candidates.size, options.size)
    }
}
