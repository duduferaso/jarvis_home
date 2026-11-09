import br.com.jarvishome.commands.LightCommands
import br.com.jarvishome.commands.LightCommands.Light
import br.com.jarvishome.commands.LightCommands.Trained

var fl = 0
val L = listOf(Light("light.luz_do_quarto", "Luz do Quarto"), Light("light.luz_da_sala", "Luz da Sala"),
    Light("light.luz_da_cozinha", "Luz da Cozinha"), Light("switch.luz_do_jantar", "Luz do Jantar"),
    Light("light.quarto_de_visita", "Luz do Quarto de Visita"))
fun desc(a: LightCommands.Action?) = if (a == null) "(HA)" else (if (a.on) "ON " else "OFF ") +
    (if (a.all) "todas" else a.lights.joinToString("+") { it.entityId.substringAfter('.') })
fun c(text: String, exp: String, trained: List<Trained> = emptyList()) {
    val r = desc(LightCommands.resolve(text, L, trained)); val ok = r == exp; if (!ok) fl++
    println((if (ok) "PASSOU " else "FALHOU ") + "\"$text\" -> $r" + if (ok) "" else "   (esperado $exp)")
}
fun main() {
    println("-- frases comuns")
    c("liga a luz do quarto", "ON luz_do_quarto")
    c("Ligar luz do quarto.", "ON luz_do_quarto")
    c("desliga a luz do quarto", "OFF luz_do_quarto")
    c("Desligar a luz da sala.", "OFF luz_da_sala")
    c("apaga a luz da cozinha", "OFF luz_da_cozinha")
    c("acende a luz da cozinha", "ON luz_da_cozinha")
    c("acenda a sala", "ON luz_da_sala")
    c("liga o quarto", "ON luz_do_quarto")
    c("liga a luz do quarto de visita", "ON quarto_de_visita")
    c("desliga a luz da visita", "OFF quarto_de_visita")
    c("liga a luz do jantar", "ON luz_do_jantar")
    c("liga a luz da sala e da cozinha", "ON luz_da_sala+luz_da_cozinha")
    c("desliga todas as luzes", "OFF todas")
    c("apaga tudo as luzes", "OFF todas")
    c("por favor liga a luz do quarto", "ON luz_do_quarto")
    println("-- erros do Whisper")
    c("ligar luz do zoquart", "(HA)")
    c("liga a luz do quatro", "ON luz_do_quarto")
    c("deliga a luz do quarto", "OFF luz_do_quarto")
    c("de liga a luz do quarto", "OFF luz_do_quarto")
    c("Esliga a luz da sala", "OFF luz_da_sala")
    c("desligue a luz da cosinha", "OFF luz_da_cozinha")
    c("liga a luz da salla", "ON luz_da_sala")
    c("Ni, desliga a luz do quarto.", "OFF luz_do_quarto")
    println("-- fica com o Home Assistant")
    c("a luz do quarto está ligada?", "(HA)")
    c("liga a luz do quarto em 50%", "(HA)")
    c("liga a televisão", "(HA)")
    c("liga o ventilador", "(HA)")
    c("que horas são", "(HA)")
    c("liga e desliga a luz", "(HA)")
    c("liga a luz", "(HA)")
    c("consultar gastos", "(HA)")
    println("-- treino")
    val tr = listOf(Trained("lisa luz do zoquart", true, "light.luz_do_quarto"),
        Trained("esta luz do quarto", false, "light.luz_do_quarto"),
        Trained("liga a luz do quarto", false, "light.luz_da_sala"),
        Trained("pagar a sala", false, "light.luz_da_sala"),
        Trained("apaga tudo", false, LightCommands.ALL))
    c("Lisa luz do zoquart.", "ON luz_do_quarto", tr)
    c("lisa a luz do zoquart", "ON luz_do_quarto", tr)
    c("Esta luz do quarto", "OFF luz_do_quarto", tr)
    c("pagar a sala", "OFF luz_da_sala", tr)
    c("apaga tudo", "OFF todas", tr)
    c("liga a luz do quarto", "OFF luz_da_sala", tr) // idêntico ao gravado: vale o treino
    c("liga a luz do quarto já", "ON luz_do_quarto", tr) // parecido mas não idêntico: vale a frase
    c("desliga a luz do quarto", "OFF luz_do_quarto", tr)
    val conflict = listOf(Trained("liga a luz da sala", true, "light.luz_da_sala"), Trained("liga a luz da sala", false, "light.luz_da_sala"))
    c("liga a luz da sala", "ON luz_da_sala", conflict) // treino empatado: usa a frase
    println("-- fala")
    println(LightCommands.speech(LightCommands.resolve("liga a luz do quarto", L)!!))
    println(LightCommands.speech(LightCommands.resolve("desliga a sala e a cozinha", L)!!))
    println(LightCommands.speech(LightCommands.resolve("desliga todas as luzes", L)!!))
    val enc = LightCommands.encode(tr); val dec = LightCommands.decode(enc)
    if (dec != tr) { fl++; println("FALHOU encode/decode") } else println("PASSOU encode/decode")
    println(if (fl == 0) "TODOS PASSARAM" else "$fl FALHARAM"); if (fl > 0) System.exit(1)
}
