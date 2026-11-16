import br.com.jarvishome.commands.Learning
var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
fun main() {
    for (s in listOf("Tony, não era isso.", "não era isso", "Não é isso.", "errou", "errado", "esquece isso", "desfaz", "desfaz o aprendizado")) ok(Learning.isUndo(s), "desfazer: \"$s\"")
    for (s in listOf("liga a luz", "isso mesmo", "desliga a luz do quarto")) ok(!Learning.isUndo(s), "não é desfazer: \"$s\"")
    for (s in listOf("Obrigado.", "Obrigada!", "Obrigado por assistir.", "Legendas pela comunidade Amara.org", "Tchau.", "Thank you."))
        ok(Learning.isHallucination(s), "inventado pelo Whisper: \"$s\"")
    for (s in listOf("liga a luz", "desliga", "obrigado, desliga a luz", "liga a luz do quarto"))
        ok(!Learning.isHallucination(s), "comando de verdade: \"$s\"")
    val noFix = Learning.addFix(mutableListOf(), "Obrigado.", "liga a luz da sala", 1L)
    ok(noFix.isEmpty(), "nunca aprende \"Obrigado\" como comando")
    val wake = listOf("Hey Tony", "Tony")
    ok(br.com.jarvishome.network.TextCorrector.stripWakeWord("Hey Tony. Obrigado.", wake).let { Learning.isHallucination(it) }, "\"Hey Tony. Obrigado.\" → descartado")
    ok(br.com.jarvishome.network.TextCorrector.stripWakeWord("Ei, Tony, liga a luz.", wake).let { !Learning.isHallucination(it) && it.lowercase().contains("liga") }, "\"Ei, Tony, liga a luz\" → comando")
    var l = Learning.decode("")
    l = Learning.addFix(l, "consultar gastos da.", "consultar gastos", 1)
    l = Learning.addFix(l, "Consultar gastos da", "consultar gastos", 2)
    ok(l.size == 1 && l[0].hits == 2, "a mesma frase reforça (2×)")
    l = Learning.addFix(l, "consultar gastos da", "consultar quanto foi amortizado", 3)
    ok(l.size == 1 && l[0].sent == "consultar quanto foi amortizado" && l[0].hits == 1, "correção diferente substitui")
    l = Learning.addFix(l, "liga a luz", "liga a luz", 4)
    ok(l.size == 1, "frase igual à corrigida não vira correção")
    for (i in 0 until 70) l = Learning.addFix(l, "frase $i", "comando $i", 10L + i)
    ok(l.size == Learning.MAX_FIXES, "guarda no máximo ${Learning.MAX_FIXES}")
    val m = Learning.asMap(Learning.decode(Learning.encode(l)))
    ok(Learning.lookup(m, "Frase 69.") == "comando 69", "salva, lê e acha (ignora maiúsculas e pontuação)")
    println(if (fl == 0) "TODOS PASSARAM" else "$fl FALHARAM"); if (fl > 0) System.exit(1)
}
