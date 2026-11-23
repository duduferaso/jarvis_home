import br.com.jarvishome.wakeword.*
fun main() {
    var fl = 0
    fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val take = FloatArray(38400) { (it % 97) / 97f }
    val seg = take.copyOfRange(8000, 24000)
    val cut = TakeTrim.cut(take, listOf(8000 to seg, 30000 to take.copyOfRange(30000, 34000)))
    ok(cut.size == 2400 + 16000, "recorta 0,15 s antes + o maior trecho de fala (${cut.size})")
    ok(cut[0] == take[8000 - 2400] && cut[2400] == take[8000], "começa 0,15 s antes da fala")
    ok(TakeTrim.cut(take, emptyList()).size == take.size, "sem trecho achado: usa a gravação inteira")
    val early = TakeTrim.cut(take, listOf(1000 to take.copyOfRange(1000, 20000)))
    ok(early.size == 2400 + 19000 && early.copyOf(1400).all { it == 0f }, "fala no começo: completa com silêncio antes")
    val long = TakeTrim.cut(take, listOf(3000 to take.copyOfRange(3000, 38400)))
    ok(long.size == 2400 + SegmentEnd.WINDOW, "trecho longo: limita a 1,9 s, como o detector")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
}
