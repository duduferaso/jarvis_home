import br.com.jarvishome.commands.LightCommands
import br.com.jarvishome.commands.LightCommands.Light
import br.com.jarvishome.home.FloorPlan

// Voz usa os mesmos relés da planta: "quarto de visita" aciona o que foi escolhido no Quarto 2.
fun main() {
    var fl = 0
    fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val relays = listOf(
        FloorPlan.Relay("light.luz_do_quarto", "Luz do Quarto", false, true),
        FloorPlan.Relay("switch.sonoff_3", "Interruptor 3", false, true),       // nome sem "visita"
        FloorPlan.Relay("light.luz_da_sala", "Luz da Sala", false, true),
        FloorPlan.Relay("switch.sonoff_jantar", "Sonoff 5", false, true),
        FloorPlan.Relay("light.luz_da_cozinha", "Luz da Cozinha", false, true),
        FloorPlan.Relay("switch.servico", "Luz Serviço", false, true))
    // escolhas feitas na planta (toque longo)
    val saved = mapOf("quarto2" to listOf("switch.sonoff_3"), "quarto1" to listOf("light.luz_do_quarto"),
        "estar" to listOf("light.luz_da_sala"), "jantar" to listOf("switch.sonoff_jantar"),
        "cozinha" to listOf("light.luz_da_cozinha", "switch.servico"))
    val map = FloorPlan.mapping(saved, relays)
    val rooms = FloorPlan.zones.mapNotNull { z ->
        val ls = relays.filter { it.entityId in map[z.id].orEmpty() }.map { Light(it.entityId, it.name) }
        if (ls.isEmpty()) null else LightCommands.Room(z.id, FloorPlan.spokenNames(z.id), ls)
    }
    val lights = LightCommands.lightsFrom(org.json.JSONArray(relays.joinToString(",", "[", "]") {
        """{"entity_id":"${it.entityId}","state":"off","attributes":{"friendly_name":"${it.name}"}}""" }))
    fun r(s: String) = LightCommands.resolve(s, lights, emptyList(), rooms)
    fun d(s: String) = r(s)?.let { (if (it.on) "on:" else "off:") + it.lights.joinToString(",") { l -> l.entityId } }
    val cases = listOf(
        "liga a luz do quarto de visita" to "on:switch.sonoff_3",
        "desliga o quarto de visitas" to "off:switch.sonoff_3",
        "acende a luz do quarto das visitas" to "on:switch.sonoff_3",
        "liga a luz do quatro de visita" to "on:switch.sonoff_3",
        "desliga a luz do quarto 2" to "off:switch.sonoff_3",
        "liga o quarto dois" to "on:switch.sonoff_3",
        "desliga a luz do quarto de hóspedes" to "off:switch.sonoff_3",
        "liga a luz da visita" to "on:switch.sonoff_3",
        "liga a luz do quarto" to "on:light.luz_do_quarto",          // nome do Home Assistant continua valendo
        "desliga o quarto de casal" to "off:light.luz_do_quarto",
        "liga a luz do quarto 1" to "on:light.luz_do_quarto",
        "liga a luz da sala de jantar" to "on:switch.sonoff_jantar",
        "desliga a luz do jantar" to "off:switch.sonoff_jantar",
        "liga a luz da sala" to "on:light.luz_da_sala",
        "desliga a luz da cozinha" to "off:light.luz_da_cozinha",    // o nome exato vence a planta
        "liga a área de serviço" to "on:light.luz_da_cozinha,switch.servico",
        "desliga o quarto de visita e a sala" to "off:switch.sonoff_3,light.luz_da_sala")
    for ((s, want) in cases) ok(d(s) == want, "\"$s\" → ${d(s)}")
    ok(r("liga a luz do quarto de visita")?.let { LightCommands.speech(it) } == "Luz do quarto de visita ligada.", "resposta: ${r("liga a luz do quarto de visita")?.let { LightCommands.speech(it) }}")
    ok(r("desliga a luz do quarto 2")?.let { LightCommands.speech(it) } == "Luz do quarto 2 desligada.", "resposta: ${r("desliga a luz do quarto 2")?.let { LightCommands.speech(it) }}")
    ok(r("luz da sala em 50%") == null, "porcentagem continua com o Home Assistant")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
    treinoRelevante()
}

// treino de comandos: tudo o que foi gravado vale como foi gravado (quem decide apagar é a pessoa)
fun treinoRelevante() {
    var fl = 0
    fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
    val L = listOf(Light("light.luz_da_cozinha", "Luz da Cozinha"), Light("light.luz_da_visita", "Luz da Visita"))
    val tr = listOf(LightCommands.Trained("Obrigada, Jesus, da cozinha", true, "light.luz_da_cozinha"))
    val a = LightCommands.resolve("Obrigada, Jesus, da cozinha.", L, tr)
    ok(a != null && a.on && a.lights.map { it.entityId } == listOf("light.luz_da_cozinha"), "\"Obrigada, Jesus, da cozinha\" (longe, com TV) liga a cozinha")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM (treino)" else "$fl FALHA(S) (treino)")
}
