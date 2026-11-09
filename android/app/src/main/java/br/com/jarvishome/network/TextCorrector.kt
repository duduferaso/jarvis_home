package br.com.jarvishome.network

import java.text.Normalizer

/**
 * "Afunila" a transcrição do Whisper para o vocabulário da casa.
 * Ex.: "ligar luz do zoquart" → "ligar luz do quarto".
 *
 * Só corrige trechos que contêm uma palavra desconhecida (fora do vocabulário e das palavras
 * comuns de comando) e que ficam a poucas letras de um nome conhecido. Assim um comando
 * correto nunca é alterado. Lógica pura, testável na JVM.
 */
object TextCorrector {

    /** Palavras comuns em comandos; nunca são tratadas como "desconhecidas". */
    private val COMMON = setOf(
        "a", "o", "as", "os", "e", "de", "do", "da", "dos", "das", "no", "na", "nos", "nas", "em", "um", "uma",
        "que", "qual", "quais", "quanto", "quanta", "quantos", "quantas", "como", "por", "para", "pra", "pro", "com",
        "me", "meu", "minha", "seu", "sua", "isso", "esse", "essa", "este", "esta", "ta", "tem", "ha", "e", "ai",
        "liga", "ligar", "ligue", "acende", "acender", "acenda", "desliga", "desligar", "desligue", "apaga", "apagar",
        "apague", "abre", "abrir", "abra", "fecha", "fechar", "feche", "aumenta", "aumentar", "diminui", "diminuir",
        "coloca", "colocar", "poe", "bota", "muda", "mudar", "ativa", "ativar", "desativa", "desativar", "liga", "toca",
        "luz", "luzes", "lampada", "tudo", "todas", "todos", "agora", "hoje", "amanha", "ontem", "horas", "hora",
        "quero", "pode", "por favor", "favor", "obrigado", "obrigada", "sim", "nao", "ok", "certo", "status",
        "temperatura", "graus", "porcento", "por cento", "minutos", "segundos", "volume", "brilho", "cor", "modo"
    )

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Remove a palavra de ativação do começo ("Jarvis, liga…" → "liga…"), tolerando erros como "Jarbas". */
    fun stripWakeWord(text: String, names: Collection<String>): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return text.trim()
        val wake = names.map { normalize(it).replace(" ", "") }.filter { it.length >= 3 }.toSet()
        fun isWake(w: String) = w.isNotEmpty() && wake.any { n ->
            w == n || (n.length >= 5 && levenshtein(w, n) <= 2) || (n.length == 4 && levenshtein(w, n) <= 1) ||
                // pedaço do começo da chamada ("tom" de "tony", "jarv" de "jarvis"), nunca palavra maior que ela
                (n.length >= 4 && w.length >= 3 && w.length < n.length && levenshtein(w, n.substring(0, w.length)) <= 1)
        }
        // Só saem junto com a chamada (podem começar um comando: "o ventilador…").
        val interjections = setOf("o", "oh", "ok", "okay", "ola", "alo")
        // Restos da chamada que o Whisper transforma em palavras ("ei Tony" → "e então"):
        // saem sempre, desde que sobre um comando de pelo menos 2 palavras.
        val fillers = setOf("e", "ei", "hey", "hei", "entao", "oi")
        var i = 0
        var lastWake = -1
        while (i < tokens.size && i < 5) {
            val t = normalize(tokens[i]).replace(" ", "")
            val next = if (i + 1 < tokens.size) normalize(tokens[i + 1]).replace(" ", "") else ""
            when {
                // primeiro a chamada partida em duas ("Jar vis"), depois inteira
                next.isNotEmpty() && isWake(t + next) && !isWake(next) -> { i += 2; lastWake = i }
                isWake(t) -> { i++; lastWake = i }
                (t in fillers || t in interjections) && tokens.size - i > 2 -> i++
                else -> break
            }
        }
        // A frase inteira vai para a transcrição; restos antes da chamada ("E aí Toni,", "Eitoni,",
        // "Ah, Tony,") também saem quando a chamada aparece entre as 4 primeiras palavras.
        if (lastWake < 0) {
            for (k in 0 until minOf(4, tokens.size - 2)) {
                val t = normalize(tokens[k]).replace(Regex("[^a-z]"), "")
                val glued = wake.any { n -> n.length >= 4 && t.length in (n.length + 1)..(n.length + 3) && t.endsWith(n) }
                if (isWake(t) || glued) lastWake = k + 1
            }
            if (lastWake >= 0) i = lastWake
        }
        // Sem chamada reconhecida, só os fillers "seguros" saem (nunca "o", "ok"...).
        val removed = if (lastWake >= 0) i else {
            var k = 0
            while (k < tokens.size - 2 && normalize(tokens[k]) in fillers) k++
            k
        }
        if (removed == 0) return text.trim()
        return tokens.drop(removed).joinToString(" ").trimStart(',', '.', '!', '?', ';', ':', '-', ' ').trim()
    }

    private class Candidate(val start: Int, val len: Int, val replacement: String, val score: Double)

    /**
     * @param vocabulary nomes de áreas, dispositivos e frases do usuário (ex.: "Luz do Quarto", "sala de jantar").
     * @return texto corrigido (ou o original, se nada for seguro de corrigir).
     */
    fun correct(text: String, vocabulary: Collection<String>): String {
        val original = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (original.isEmpty() || vocabulary.isEmpty()) return text.trim()
        val norm = original.map { normalize(it) }

        val phrases = vocabulary.map { it.trim() }.filter { normalize(it).length >= 4 }
            .distinctBy { normalize(it) }
            .map { Pair(it, normalize(it).split(" ")) }
        val known = phrases.flatMap { it.second }.toSet() + COMMON
        fun unknown(i: Int) = norm[i].isNotEmpty() && norm[i] !in known && !norm[i].all { it.isDigit() }

        val candidates = ArrayList<Candidate>()
        for ((display, ptoks) in phrases) {
            val pjoined = ptoks.joinToString(" ")
            for (w in maxOf(1, ptoks.size - 1)..(ptoks.size + 1)) {
                for (start in 0..(norm.size - w)) {
                    val window = (start until start + w)
                    if (window.none { unknown(it) }) continue
                    val wjoined = window.joinToString(" ") { norm[it] }.trim()
                    if (wjoined.isEmpty() || wjoined == pjoined) continue
                    val dist = levenshtein(wjoined, pjoined)
                    val allowed = if (pjoined.length < 6) 1 else maxOf(1, pjoined.length / 4)
                    if (dist > allowed) continue
                    // Cada palavra trocada precisa parecer, sozinha, com a palavra que a substitui.
                    // Impede que "mercado no financeiro" vire "lançar no financeiro" só porque o resto bate.
                    val wordsOk = if (w == ptoks.size) {
                        window.withIndex().all { (k, idx) ->
                            val a = norm[idx]; val b = ptoks[k]
                            val d = levenshtein(a, b)
                            when {
                                a == b -> true
                                // palavra que o Whisper inventou: pode estar bem deformada ("zoquart" ~ "quarto")
                                unknown(idx) -> d <= maxOf(2, b.length / 2)
                                // palavra conhecida diferente: só pequenas variações
                                else -> d <= (if (b.length <= 3) 1 else maxOf(1, b.length / 3))
                            }
                        }
                    } else {
                        // Palavras juntadas ou separadas ("zoquart" ~ "do quarto"): cada palavra desconhecida
                        // precisa estar perto de alguma palavra do nome.
                        window.filter { unknown(it) }.all { idx ->
                            ptoks.any { b -> levenshtein(norm[idx], b) <= maxOf(2, b.length / 2) }
                        }
                    }
                    if (wordsOk) {
                        candidates.add(Candidate(start, w, display.lowercase(), dist.toDouble() / pjoined.length - w * 0.001))
                    }
                }
            }
        }
        if (candidates.isEmpty()) return original.joinToString(" ")

        // Escolha gulosa: menor distância relativa primeiro, sem sobreposição.
        val used = BooleanArray(norm.size)
        val chosen = ArrayList<Candidate>()
        for (c in candidates.sortedBy { it.score }) {
            if ((c.start until c.start + c.len).any { used[it] }) continue
            chosen.add(c)
            for (k in c.start until c.start + c.len) used[k] = true
        }
        val byStart = chosen.associateBy { it.start }
        val out = ArrayList<String>()
        var i = 0
        while (i < original.size) {
            val c = byStart[i]
            if (c != null) {
                // mantém pontuação final do último token substituído
                val tail = original[i + c.len - 1].takeLastWhile { it in ",.!?;:" }
                out.add(c.replacement + tail)
                i += c.len
            } else {
                out.add(original[i])
                i++
            }
        }
        return out.joinToString(" ")
    }
}
