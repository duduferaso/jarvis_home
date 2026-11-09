import br.com.jarvishome.network.TextCorrector
var falhas = 0
fun t(input: String, expected: String, vocab: List<String>) {
    val r = TextCorrector.correct(input, vocab)
    val ok = r == expected
    if (!ok) falhas++
    println((if (ok) "PASSOU " else "FALHOU ") + "\"" + input + "\" -> \"" + r + "\"" + (if (ok) "" else "   (esperado \"" + expected + "\")"))
}
fun w(input: String, expected: String, names: List<String> = listOf("Jarvis")) {
    val r = TextCorrector.stripWakeWord(input, names)
    val ok = r == expected; if (!ok) falhas++
    println((if (ok) "PASSOU " else "FALHOU ") + "[wake] \"" + input + "\" -> \"" + r + "\"")
}
fun main() {
    val v = listOf("sala", "quarto", "cozinha", "sala de jantar", "quarto de visita",
        "Luz da Sala", "Luz do Quarto", "Luz da Cozinha", "Luz do Jantar", "Luz da Visita",
        "temperatura do servidor", "armazenamento do servidor", "serviço de vídeos", "financeiro", "inscritos do canal")
    println("-- corrige o que o Whisper errou")
    t("ligar luz do zoquart", "ligar luz do quarto", v)
    t("Ligar luz do zoquart.", "Ligar luz do quarto.", v)
    t("apaga a luz da sela", "apaga a luz da sala", v)
    t("liga a luz da cosinha", "liga a luz da cozinha", v)
    t("acende a luz do jamtar", "acende a luz do jantar", v)
    t("qual a temperatura do cervidor", "qual a temperatura do servidor", v)
    t("liga a luz do quarto de vizita", "liga a luz do quarto de visita", v)
    t("como estão os inscritos do canau", "como estão os inscritos do canal", v)
    println("-- nunca mexe no que já está certo ou não tem relação")
    t("liga a luz do quarto", "liga a luz do quarto", v)
    t("Desliga a luz da sala de jantar.", "Desliga a luz da sala de jantar.", v)
    t("que horas são", "que horas são", v)
    t("toca uma música animada", "toca uma música animada", v)
    t("liga a televisão", "liga a televisão", v)
    t("lança 50 reais em mercado no financeiro", "lança 50 reais em mercado no financeiro", v)
    t("qual a previsão do tempo amanhã", "qual a previsão do tempo amanhã", v)
    t("liga o ventilador", "liga o ventilador", v)
    println("-- frases das suas automações")
    val a = v + listOf("consultar gastos", "mês atual", "consultar quanto foi amortizado", "amortizado",
        "desfazer último lançamento", "lançar no financeiro", "erros nos vídeos de hoje", "resumo do canal")
    t("consultar quanto foi amortisado", "consultar quanto foi amortizado", a)
    t("quanto foi amortizado", "quanto foi amortizado", a)
    t("desfazer o último lançamento", "desfazer o último lançamento", a)
    t("desfazer último lansamento", "desfazer último lançamento", a)
    t("consultar gastos do mês atual", "consultar gastos do mês atual", a)
    t("consultar gasto do mês atuau", "consultar gastos do mês atual", a)
    t("lançar 50 reais de mercado no financeiro", "lançar 50 reais de mercado no financeiro", a)
    t("erros nos vídeos de oje", "erros nos vídeos de hoje", a)
    t("resumo do canau", "resumo do canal", a)
    t("lançar 30 reais de farmácia no financeiro", "lançar 30 reais de farmácia no financeiro", a)
    t("lançar 20 de padaria no financeiro", "lançar 20 de padaria no financeiro", a)
    t("liga a luz do banheiro", "liga a luz do banheiro", a)
    t("liga a luz do corredor", "liga a luz do corredor", a)
    println("-- remove a palavra de ativação do início")
    w("Jarvis, liga a luz do quarto.", "liga a luz do quarto.")
    w("Ei Jarvis liga a luz", "liga a luz")
    w("Jarbas, apaga a luz", "apaga a luz")
    w("Jar vis, que horas são?", "que horas são?")
    w("liga a luz do quarto", "liga a luz do quarto")
    w("Ligar luz do quarto", "Ligar luz do quarto")
    w("Jarvis", "")
    w("e então, liga a luz do quarto", "liga a luz do quarto")
    println("-- com o detector pessoal (chamada \"Hey Tony\")")
    val tony = listOf("Tony", "TONY", "TONI", "TONEY", "TONEE")
    w("Ei Tony, liga a luz do quarto.", "liga a luz do quarto.", tony)
    w("é tom, apaga a luz da sala", "apaga a luz da sala", tony)
    w("e então, liga a luz do quarto", "liga a luz do quarto", tony)
    w("e ele tomi, liga a luz", "liga a luz", tony) // 1.6: chamada nas 4 primeiras palavras sai junto
    w("Toni liga a luz da sala", "liga a luz da sala", tony)
    w("o ventilador está ligado?", "o ventilador está ligado?", tony)
    w("ok, apaga tudo", "ok, apaga tudo", tony)
    w("liga a luz do quarto", "liga a luz do quarto", tony)
    w("e a luz da sala", "a luz da sala", tony)
    w("Tony", "", tony)
    w("tomate na lista de compras", "tomate na lista de compras", tony)
    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHAS")
}
