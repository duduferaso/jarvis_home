package br.com.jarvishome.network

/**
 * Segunda tentativa: quando o Home Assistant não entende a frase, procura o comando conhecido
 * mais parecido (nomes da casa, áreas, frases das automações "Voz:" e o vocabulário extra).
 *
 * - Com verbo no começo ("liga a luz do porto"): o verbo e o artigo ficam como foram falados
 *   (nunca troca ligar por desligar) e só o alvo é comparado com os nomes da casa.
 * - Sem verbo ("consulta os gasto"): a frase inteira é comparada com as frases conhecidas.
 * Só devolve algo quando o mais parecido é claramente melhor que o segundo. Lógica pura.
 */
object CommandMatcher {

    private val VERBS = setOf(
        "liga", "ligar", "ligue", "acende", "acender", "acenda", "desliga", "desligar", "desligue",
        "apaga", "apagar", "apague", "abre", "abrir", "abra", "fecha", "fechar", "feche",
        "ativa", "ativar", "ative", "desativa", "desativar", "desative", "aumenta", "aumentar",
        "diminui", "diminuir", "alterna", "alternar"
    )
    private val ARTICLES = setOf("a", "o", "as", "os")
    private val POLITE = setOf("por", "favor", "pfv", "ai", "agora")

    private fun words(s: String): List<String> =
        TextCorrector.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }

    private fun ratio(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - TextCorrector.levenshtein(a, b).toDouble() / m
    }

    /** Nomes que podem ser alvo: o próprio nome e, para áreas/cômodos, "luz do/da X". */
    private fun targets(vocabulary: Collection<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (v in vocabulary) {
            val w = words(v)
            if (w.isEmpty() || w.joinToString("").length < 3) continue
            val key = w.joinToString(" ")
            out.putIfAbsent(key, v.trim().lowercase())
            if (w.size <= 3 && w[0] != "luz" && w[0] !in VERBS) {
                for (p in listOf("luz do", "luz da", "luzes do", "luzes da")) {
                    out.putIfAbsent("$p $key", "$p ${v.trim().lowercase()}")
                }
            }
        }
        return out
    }

    private fun best(query: String, cands: Map<String, String>, minRatio: Double): String? {
        var best: Pair<String, Double>? = null
        for ((k, v) in cands) {
            val r = ratio(query, k)
            if (best == null || r > best.second) best = v to r
        }
        val b = best ?: return null
        if (b.second < minRatio || b.second >= 0.999) return null
        // Variações do mesmo nome ("luz do quarto" e "luz da quarto") não contam como concorrentes.
        val rivals = cands.entries.filter { ratio(query, it.key) >= b.second - 0.08 }
            .map { words(it.value).filter { w -> w !in setOf("luz", "luzes", "do", "da") } }.distinct()
        if (rivals.size > 1) return null
        return b.first
    }

    /** Devolve a frase a tentar de novo, ou null quando não há um candidato seguro. */
    fun suggest(text: String, vocabulary: Collection<String>): String? {
        var w = words(text).filter { it !in POLITE }
        if (w.isEmpty()) return null
        if (w[0] in VERBS) {
            val verb = w[0]
            w = w.drop(1)
            val article = if (w.isNotEmpty() && w[0] in ARTICLES) w[0] else null
            if (article != null) w = w.drop(1)
            if (w.isEmpty()) return null
            val target = best(w.joinToString(" "), targets(vocabulary), 0.7) ?: return null
            return listOfNotNull(verb, article, target).joinToString(" ")
        }
        val phrases = vocabulary.filter { words(it).size >= 2 }
            .associateBy({ words(it).joinToString(" ") }, { it.trim().lowercase() })
        val found = best(w.joinToString(" "), phrases, 0.75) ?: return null
        // Nunca transforma em "desligar…" algo que não pedia para desligar (e vice-versa).
        val off = setOf("desliga", "desligar", "desligue", "apaga", "apagar", "apague", "desativa", "desativar", "desative")
        if ((words(found).firstOrNull() in off) != (w.first() in off || w.first().startsWith("desl"))) return null
        return found
    }
}
