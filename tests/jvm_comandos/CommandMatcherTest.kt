import br.com.jarvishome.network.CommandMatcher
import br.com.jarvishome.network.TextCorrector

var falhas = 0
fun t(input: String, expected: String?, vocab: List<String>) {
    val r = CommandMatcher.suggest(input, vocab)
    val ok = r == expected
    if (!ok) falhas++
    println((if (ok) "PASSOU " else "FALHOU ") + "\"" + input + "\" -> " + (r?.let { "\"$it\"" } ?: "(nada)") +
        (if (ok) "" else "   (esperado " + (expected ?: "nada") + ")"))
}

fun main() {
    val v = listOf("Sala", "Quarto", "Cozinha", "Sala de Jantar", "Quarto de Visita",
        "Luz da Sala", "Luz do Quarto", "Luz da Cozinha", "Luz do Jantar", "Luz da Visita",
        "consultar gastos", "consultar quanto foi amortizado", "temperatura do servidor", "inscritos do canal")
    println("-- segunda tentativa encontra o comando")
    t("liga a luz do porto", "liga a luz do quarto", v)
    t("Ligar luz do quatro.", "ligar luz do quarto", v)
    t("acende a luz da cozinhas", "acende a luz da cozinha", v)
    t("apaga a luz da sala de jantá", "apaga a luz da sala de jantar", v)
    t("desliga a luz da vizitas", "desliga a luz da visita", v)
    t("liga o quartu", "liga o quarto", v)
    t("consulta os gasto", "consultar gastos", v)
    t("consultar quanto foi amortizada", "consultar quanto foi amortizado", v)
    t("liga a luz do quarto por favor", null, v) // já é exato: nada a tentar
    println("-- nunca inventa nem troca o sentido")
    t("que horas são", null, v)
    t("toca uma música", null, v)
    t("liga a televisão", null, v)
    t("liga tudo", null, v)
    t("desligar gastos", null, v)
    t("liga a luz da sa", "liga a luz da sala", v) // a casa já recusou; o mais provável é a sala
    t("", null, v)
    println(if (falhas == 0) "TODOS PASSARAM" else "$falhas FALHARAM")
    if (falhas > 0) System.exit(1)
    TextCorrector.normalize("x")
}
