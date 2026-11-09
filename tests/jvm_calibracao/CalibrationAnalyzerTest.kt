import br.com.jarvishome.wakeword.CalibrationAnalyzer
fun ok(c: Boolean, m: String) { println((if (c) "PASSOU " else "FALHOU ") + m); if (!c) System.exit(1) }
fun thr(kw: String) = Regex("#([0-9.]+)").find(kw)!!.groupValues[1].toFloat()
fun main() {
    val cands = listOf("▁A @A", "▁B @B", "▁C @C", "▁D @D", "▁E @E")
    val pos = List(8) { i -> FloatArray(1) { i.toFloat() } }
    val neg = listOf(FloatArray(1) { 100f })
    // Modelo de mundo: limiar menor = mais sensível.
    // A: acerta 0-3 com limiar <= 0.29 (sens >= 4). Nunca dispara na fala.
    // B: acerta 4-5 com sens >= 8, mas dispara na fala com sens >= 10.
    // C: acerta 4-7 sozinha, mas JUNTO com A compete e só acerta 6-7 (simula competição).
    // D: acerta 0-7 mas dispara na fala sempre (deve ser descartada).
    // E: nunca acerta.
    val det = { kw: String, a: FloatArray ->
        val i = a[0].toInt()
        val parts = kw.split("/").map { CalibrationAnalyzer.labelOf(it) to thr(it) }
        val labels = parts.map { it.first }.toSet()
        parts.any { (l, t) ->
            when (l) {
                "A" -> i in 0..3 && t <= 0.30f
                "B" -> (i in 4..5 && t <= 0.16f) || (i == 100 && t <= 0.09f)
                "C" -> if ("A" in labels) i in 6..7 else i in 4..7
                "D" -> true
                else -> false
            }
        }
    }
    var calls = 0
    val r = CalibrationAnalyzer.analyze(pos, neg, cands, { kw, a -> calls++; det(kw, a) })!!
    println("   escolhidas: " + r.keywordLines + "  cobertura " + r.coverage + "/8, chamadas ao detector: " + calls)
    ok(r.keywordLines.none { it.endsWith("@D") } && r.keywordLines.none { it.endsWith("@E") }, "descarta a que dispara na fala e a que nunca acerta")
    ok(r.keywordLines.first().endsWith("@A") || r.keywordLines.first().endsWith("@C"), "começa pela variação que mais acerta")
    val b = r.keywordLines.firstOrNull { it.endsWith("@B") }
    ok(b == null || thr(b) > 0.09f, "B nunca recebe a sensibilidade que dispara na fala")
    ok(r.coverage == 8, "a verificação EM CONJUNTO chega a 8/8 combinando variações")
    ok(r.keywordLines.size <= 3, "no máximo 3 variações")
    val a = r.keywordLines.first { it.endsWith("@A") }
    ok(Math.abs(thr(a) - 0.40f + 3 * 0.32f / 9f) < 0.002f, "A usa a sensibilidade mais conservadora que basta (4): ${thr(a)}")
    ok(CalibrationAnalyzer.analyze(pos, neg, cands, { _, _ -> false }) == null, "sem acertos devolve null")
    println("TODOS OS TESTES PASSARAM")
}
