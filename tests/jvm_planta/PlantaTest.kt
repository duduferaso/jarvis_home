import br.com.jarvishome.home.FloorPlan
import br.com.jarvishome.home.FloorPlan.Relay

var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
fun main() {
    val rs = listOf(Relay("light.luz_da_sala", "Luz da Sala", true, true), Relay("switch.rele_jantar", "Luz da Sala de Jantar", false, true),
        Relay("light.luz_da_cozinha", "Luz da Cozinha", false, true), Relay("light.luz_do_quarto", "Luz do Quarto", true, true),
        Relay("light.quarto_de_visita", "Luz do Quarto de Visita", false, true), Relay("switch.banheiro", "Banheiro", false, true),
        Relay("switch.rele_3", "Relé 3", false, true), Relay("switch.tomada_tv", "Tomada da TV", false, true))
    val m = FloorPlan.autoMatch(rs)
    println("   automático: $m")
    ok(m["estar"] == listOf("light.luz_da_sala"), "sala de estar ← Luz da Sala")
    ok(m["jantar"] == listOf("switch.rele_jantar"), "jantar ← Luz da Sala de Jantar (não vai para a sala de estar)")
    ok(m["cozinha"] == listOf("light.luz_da_cozinha"), "cozinha")
    ok(m["quarto1"] == listOf("light.luz_do_quarto"), "Quarto 1 ← Luz do Quarto")
    ok(m["quarto2"] == listOf("light.quarto_de_visita"), "Quarto 2 ← Quarto de Visita")
    ok(m["banheiro"] == listOf("switch.banheiro"), "banheiro")
    ok(m["corredor"] == null, "corredor sem luz")
    ok(m.values.flatten().none { it == "switch.rele_3" }, "relé sem nome não é chutado")
    val saved = mapOf("corredor" to listOf("switch.rele_3"), "estar" to listOf("light.luz_da_sala", "switch.tomada_tv"))
    val mm = FloorPlan.mapping(saved, rs)
    ok(mm["corredor"] == listOf("switch.rele_3") && mm["estar"]!!.size == 2 && mm["jantar"] == listOf("switch.rele_jantar"), "escolha manual tem prioridade: $mm")
    ok(FloorPlan.decode(FloorPlan.encode(saved)) == saved, "salva e lê a escolha")
    val z = FloorPlan.zones
    ok(FloorPlan.isOn(z.first { it.id == "estar" }, mm, rs) == true, "sala acesa")
    ok(FloorPlan.isOn(z.first { it.id == "jantar" }, mm, rs) == false, "jantar apagada")
    ok(FloorPlan.isOn(z.first { it.id == "quarto2" }, mapOf(), rs) == null, "cômodo sem luz = null")
    ok(FloorPlan.zoneAt(3.0f, 1.0f)?.id == "jantar" && FloorPlan.zoneAt(1.0f, 0.5f)?.id == "jantar", "jantar fica em cima, com a entrada")
    ok(FloorPlan.zoneAt(3.0f, 6.0f)?.id == "estar" && FloorPlan.zoneAt(1.0f, 5.0f)?.id == "cozinha", "sala de estar embaixo e cozinha")
    ok(FloorPlan.zoneAt(9.0f, 2.0f)?.id == "quarto1" && FloorPlan.zoneAt(9.0f, 5.0f)?.id == "quarto2", "quartos")
    ok(FloorPlan.zoneAt(6.4f, 4.2f)?.id == "corredor" && FloorPlan.zoneAt(6.4f, 6.5f)?.id == "banheiro", "corredor e banheiro")
    ok(FloorPlan.zoneAt(1.0f, 7.8f) == null, "fora da casa")
    println(if (fl == 0) "TODOS PASSARAM" else "$fl FALHARAM"); if (fl > 0) System.exit(1)
}
