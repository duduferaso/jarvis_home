import br.com.jarvishome.commands.LightCommands
import br.com.jarvishome.network.TextCorrector

// Transcrições reais do log (17/11) e variações do mesmo tipo: a chamada vai sempre no começo do áudio.
fun main() {
    var fl = 0
    fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val names = listOf("Hey Tony", "Tony", "Toni", "Tonie", "Tohny", "Toney", "Tonee", "Tawny", "Toh nee", "Dony", "Tonia")
    val lights = L
    fun strip(s: String) = TextCorrector.stripWakeWord(s, names)
    // só a chamada → reabre o microfone (antes ia para a casa e voltava "Desculpe, não consegui entender")
    for (s in listOf("Reitone.", "Heitori.", "Ei Tony.", "Hey Tony.", "Ei, Tony.", "Eitoni.", "Rei tone.", "Hei Tony!", "Tony."))
        ok(strip(s).isBlank(), "só a chamada: \"$s\" → \"${strip(s)}\"")
    // chamada + comando
    val cases = listOf(
        "Reitone, liga a luz da cozinha." to "on:Luz da Cozinha",
        "Heitori, desliga a luz do quarto." to "off:Luz do Quarto",
        "Ei Tony, acende a luz da sala." to "on:Luz da Sala",
        "Hey Tony, apaga a luz da cozinha." to "off:Luz da Cozinha",
        "Rei Tony, liga a luz do jantar." to "on:Luz do Jantar",
        "Eitoni liga luz da sala." to "on:Luz da Sala",
        "Ei, Tony. Liga a luz do quarto." to "on:Luz do Quarto",
        "Ei Tony, deliga a luz da sala." to "off:Luz da Sala",
        "E aí Tony, liga a luz da cozinha." to "on:Luz da Cozinha",
        "Reitone. Desliga a luz da cozinha." to "off:Luz da Cozinha",
        "Hey Tony, Ligar luz da cozinha." to "on:Luz da Cozinha")
    for ((heard, want) in cases) {
        val t = strip(heard)
        val a = LightCommands.resolve(t, lights)
        val got = a?.let { (if (it.on) "on:" else "off:") + it.lights.joinToString(",") { l -> l.name } }
        ok(got == want, "\"$heard\" → \"$t\" → $got")
    }
    // palavras de comando no começo não podem ser comidas
    for (s in listOf("Heitor ligou?", "Desliga a luz da sala", "Acende tudo", "o que tem na lista"))
        ok(strip(s).isNotBlank(), "não come comando: \"$s\" → \"${strip(s)}\"")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
}
