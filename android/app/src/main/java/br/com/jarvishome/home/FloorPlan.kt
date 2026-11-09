package br.com.jarvishome.home

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Planta da casa (tirada da planta elétrica do apartamento), em metros aproximados,
 * origem no canto de cima à esquerda da planta.
 *  - Cômodos = áreas de luz (um ou mais retângulos, sem parede entre eles quando não existe).
 *  - Paredes = só as que existem, com os vãos das portas.
 * Cada cômodo controla um ou mais relés/luzes do Home Assistant (escolhidos no app ou
 * encontrados pelo nome). Lógica pura, testável na JVM.
 */
object FloorPlan {

    class Zone(
        val id: String,
        val name: String,
        val rects: List<FloatArray>,          // x1, y1, x2, y2
        val lightPoints: List<FloatArray>,    // x, y (pontos de luz da planta elétrica)
        val label: FloatArray,                // x, y
        val keywords: List<String>,           // para achar a luz pelo nome
        val avoid: List<String> = emptyList()
    ) {
        fun contains(x: Float, y: Float) = rects.any { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] }
    }

    private fun r(x1: Number, y1: Number, x2: Number, y2: Number) = floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
    private fun p(x: Number, y: Number) = floatArrayOf(x.toFloat(), y.toFloat())

    val zones: List<Zone> = listOf(
        Zone("estar", "Sala de estar", listOf(r(0, 0, 2.15, 1.62), r(2.15, 0, 5.75, 3.6)), listOf(p(3.95, 2.0)), p(3.9, 1.6),
            listOf("estar", "sala", "living", "tv"), avoid = listOf("jantar", "mesa")),
        Zone("jantar", "Jantar", listOf(r(2.15, 3.6, 5.55, 8.07)), listOf(p(3.85, 5.8)), p(3.85, 5.9),
            listOf("jantar", "mesa")),
        Zone("cozinha", "Cozinha", listOf(r(0, 1.62, 2.15, 7.12)), listOf(p(1.05, 3.3), p(1.05, 6.3)), p(1.05, 4.6),
            listOf("cozinha", "servico", "lavanderia", "area")),
        Zone("corredor", "Corredor", listOf(r(5.55, 3.6, 7.2, 4.82)), listOf(p(6.4, 4.2)), p(6.4, 4.2),
            listOf("corredor", "hall", "circulacao")),
        Zone("banheiro", "Banheiro", listOf(r(5.55, 4.82, 7.2, 8.07)), listOf(p(6.35, 6.45)), p(6.35, 6.5),
            listOf("banheiro", "banho", "wc", "lavabo")),
        Zone("quarto1", "Quarto 1", listOf(r(5.75, 0, 10.35, 3.6)), listOf(p(8.05, 1.85)), p(8.05, 1.9),
            listOf("quarto 1", "suite", "casal", "principal", "master")),
        Zone("quarto2", "Quarto 2", listOf(r(7.2, 3.6, 10.35, 7.22)), listOf(p(8.75, 5.4)), p(8.75, 5.45),
            listOf("quarto 2", "visita", "hospede", "escritorio", "bebe", "filho", "filha", "solteiro"))
    )

    /** Paredes (x1, y1, x2, y2): contorno externo e divisórias reais, com vãos de porta. */
    val walls: List<FloatArray> = listOf(
        r(0, 0, 10.35, 0), r(10.35, 0, 10.35, 7.22), r(7.2, 7.22, 10.35, 7.22), r(7.2, 7.22, 7.2, 8.07),
        r(2.15, 8.07, 7.2, 8.07), r(2.15, 7.12, 2.15, 8.07), r(0, 7.12, 2.15, 7.12), r(0, 0, 0, 7.12),
        r(0, 1.62, 2.15, 1.62),      // entrada | cozinha
        r(2.15, 3.9, 2.15, 7.12),    // cozinha | jantar (aberta em cima)
        r(5.75, 0, 5.75, 2.7),       // sala | quarto 1 (porta embaixo)
        r(6.9, 3.6, 10.35, 3.6),     // quarto 1 | corredor e quarto 2
        r(5.55, 4.82, 5.55, 8.07),   // jantar | banheiro
        r(6.3, 4.82, 7.2, 4.82),     // corredor | banheiro
        r(7.2, 4.5, 7.2, 7.22)       // banheiro | quarto 2
    )

    const val WIDTH = 10.35f
    const val DEPTH = 8.07f

    fun zoneAt(x: Float, y: Float): Zone? = zones.firstOrNull { it.contains(x, y) }

    // ------------------------------------------------------------------ luzes de cada cômodo

    class Relay(val entityId: String, val name: String, val on: Boolean, val available: Boolean)

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[_.]"), " ")

    /** Acha a luz de cada cômodo pelo nome (cada relé vai para um cômodo só). */
    fun autoMatch(relays: List<Relay>): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        val used = HashSet<String>()
        fun text(r: Relay) = " " + norm(r.name) + " " + norm(r.entityId.substringAfter('.')) + " "
        // primeiro os nomes específicos, depois os genéricos ("quarto" sozinho vai para o Quarto 1)
        for (z in zones) {
            val best = relays.filter { it.entityId !in used }.map { rl ->
                val t = text(rl)
                val score = z.keywords.count { k -> t.contains(" $k ") || t.contains(" $k") } -
                    z.avoid.count { a -> t.contains(a) } * 5
                rl to score
            }.filter { it.second > 0 }.sortedByDescending { it.second }
            val pick = best.firstOrNull()?.first ?: continue
            out[z.id] = listOf(pick.entityId)
            used.add(pick.entityId)
        }
        if (out["quarto1"] == null) {
            relays.firstOrNull { it.entityId !in used && text(it).contains(" quarto") }?.let {
                out["quarto1"] = listOf(it.entityId); used.add(it.entityId)
            }
        }
        if (out["quarto2"] == null) {
            relays.firstOrNull { it.entityId !in used && text(it).contains(" quarto") }?.let {
                out["quarto2"] = listOf(it.entityId); used.add(it.entityId)
            }
        }
        return out
    }

    /** Escolhas feitas no app (têm prioridade); cômodo sem escolha usa a busca pelo nome. */
    fun mapping(saved: Map<String, List<String>>, relays: List<Relay>): Map<String, List<String>> {
        val auto = autoMatch(relays.filter { r -> saved.values.none { r.entityId in it } })
        val out = LinkedHashMap<String, List<String>>()
        for (z in zones) out[z.id] = saved[z.id] ?: auto[z.id] ?: emptyList()
        return out
    }

    /** null = cômodo sem luz; true/false = alguma luz acesa. */
    fun isOn(zone: Zone, map: Map<String, List<String>>, relays: List<Relay>): Boolean? {
        val ids = map[zone.id].orEmpty()
        val mine = relays.filter { it.entityId in ids }
        if (mine.isEmpty()) return null
        return mine.any { it.on }
    }

    fun encode(m: Map<String, List<String>>): String {
        val o = JSONObject()
        for ((k, v) in m) { val a = JSONArray(); v.forEach { a.put(it) }; o.put(k, a) }
        return o.toString()
    }

    fun decode(s: String): Map<String, List<String>> = try {
        val o = JSONObject(s.ifBlank { "{}" })
        val out = LinkedHashMap<String, List<String>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.optJSONArray(k) ?: continue
            out[k] = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        }
        out
    } catch (e: Exception) { emptyMap() }
}
