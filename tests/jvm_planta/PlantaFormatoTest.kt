import br.com.jarvishome.home.FloorPlan
import br.com.jarvishome.home.Planta
import org.json.JSONObject

// Formato da planta do Home Control (planta.json): a planta atual convertida tem de desenhar
// exatamente as mesmas paredes quando o app novo calcula as paredes a partir dos cômodos.
fun main() {
    var falhas = 0
    fun ok(c: Boolean, m: String) { if (!c) falhas++; println((if (c) "PASSOU " else "FALHOU ") + m) }

    val casa = Planta.daPlantaAtual(mapOf("cozinha" to listOf("light.cozinha", "switch.luz_servico"), "quarto2" to listOf("switch.rele_2")))
    val andar = casa.andares.single()
    ok(andar.comodos.size == FloorPlan.zones.size, "todos os cômodos convertidos (${andar.comodos.size})")
    ok(andar.comodos.first { it.id == "cozinha" }.entidades == listOf("light.cozinha", "switch.luz_servico"), "luzes escolhidas vão para o cômodo")
    ok(andar.comodos.first { it.id == "quarto2" }.nomesFalados.contains("quarto de visita"), "nomes falados vão junto")
    ok(andar.comodos.first { it.id == "jantar" }.partes.size == 2, "cômodo em L continua com duas partes")
    ok(andar.comodos.first { it.id == "corredor" }.mostrarNome == false, "corredor continua sem nome na planta")

    val integ = andar.integrados.map { setOf(it.first, it.second) }.toSet()
    ok(integ == setOf(setOf("estar", "jantar"), setOf("estar", "corredor"), setOf("jantar", "corredor")),
        "ambientes integrados: " + andar.integrados.joinToString { "${it.first}+${it.second}" })
    ok(andar.portas.size == 5, "portas encontradas: ${andar.portas.size} (corredor↔quartos, banheiro, cozinha↔sala, cozinha↔jantar)")

    // o app novo calcula as paredes: têm de ser as mesmas desenhadas hoje
    fun norm(l: List<DoubleArray>) = Planta.uniao(l.map { Planta.Seg.of(it) })
        .map { (if (it.h) "h" else "v") + "%.2f:%.2f-%.2f".format(it.c, it.a, it.b) }.sorted()
    val calculadas = norm(Planta.paredes(andar))
    val hoje = norm(FloorPlan.walls.map { w -> DoubleArray(4) { i -> w[i].toDouble() } })
    ok(calculadas == hoje, "paredes calculadas = paredes da planta de hoje (${calculadas.size} trechos)")
    if (calculadas != hoje) { println("  calculadas: $calculadas"); println("  hoje:       $hoje") }

    // JSON ida e volta
    val txt = Planta.toJson(casa).toString()
    val volta = Planta.fromJson(JSONObject(txt))
    ok(Planta.toJson(volta).toString() == txt, "JSON ida e volta sem perder nada")
    ok(Planta.validar(casa).isEmpty(), "planta atual é válida: " + Planta.validar(casa))

    // validação
    val c1 = Planta.Comodo("a", "Sala", "sala", listOf(doubleArrayOf(0.0, 0.0, 3.0, 3.0)), emptyList(), null, listOf("light.x"), emptyList(), emptyList(), emptyList(), true)
    val c2 = Planta.Comodo("b", "Quarto", "quarto", listOf(doubleArrayOf(2.0, 0.0, 5.0, 3.0)), emptyList(), null, listOf("light.x"), emptyList(), emptyList(), emptyList(), true)
    val ruim = Planta.validar(Planta.Casa("x", listOf(Planta.Andar("t", "Térreo", listOf(c1, c2), emptyList(), emptyList()))))
    ok(ruim.any { "sobrepõem" in it } && ruim.any { "dois cômodos" in it }, "acusa sobreposição e luz repetida: $ruim")
    var recusou = false
    try { Planta.fromJson(JSONObject("{\"formato\":99}")) } catch (e: IllegalArgumentException) { recusou = true }
    ok(recusou, "recusa planta de formato mais novo")

    // casa simples de dois cômodos integrados: só o contorno
    val s1 = Planta.Comodo("sala", "Sala", "sala", listOf(doubleArrayOf(0.0, 0.0, 4.0, 3.0)), emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), true)
    val s2 = Planta.Comodo("coz", "Cozinha", "cozinha", listOf(doubleArrayOf(4.0, 0.0, 6.0, 3.0)), emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), true)
    val aberta = Planta.paredes(Planta.Andar("t", "T", listOf(s1, s2), listOf("sala" to "coz"), emptyList()))
    val fechada = Planta.paredes(Planta.Andar("t", "T", listOf(s1, s2), emptyList(), listOf(doubleArrayOf(4.0, 1.0, 4.0, 2.0))))
    ok(aberta.none { Math.abs(it[0] - 4.0) < 0.01 && Math.abs(it[2] - 4.0) < 0.01 }, "integrados: sem parede entre a sala e a cozinha")
    ok(fechada.count { Math.abs(it[0] - 4.0) < 0.01 && Math.abs(it[2] - 4.0) < 0.01 } == 2, "com porta: a parede do meio vira dois trechos")

    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHA(S)")
}
