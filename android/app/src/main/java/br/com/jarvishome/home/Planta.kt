package br.com.jarvishome.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * Formato da planta do Home Control (planta.json, formato 1) — o mesmo que o app novo
 * (ha_local_assistant) lê e edita. Lógica pura, testada em tests/jvm_planta/PlantaFormatoTest.kt.
 *
 * As paredes não são guardadas: saem dos cômodos. Parede = borda entre cômodos diferentes
 * (que não sejam "integrados") ou com o lado de fora, menos as portas (vãos).
 */
object Planta {
    const val FORMATO = 1
    private const val EPS = 0.01

    class Comodo(
        val id: String,
        val nome: String,
        val tipo: String,
        val partes: List<DoubleArray>,          // x1, y1, x2, y2 (metros)
        val luzes: List<DoubleArray>,           // x, y
        val rotulo: DoubleArray?,               // x, y do nome na planta (opcional)
        val entidades: List<String>,
        val nomesFalados: List<String>,
        val palavrasLuz: List<String>,          // para achar a luz no HA pelo nome (opcional)
        val evitarPalavras: List<String>,
        val mostrarNome: Boolean
    )

    class Andar(
        val id: String,
        val nome: String,
        val comodos: List<Comodo>,
        val integrados: List<Pair<String, String>>,
        val portas: List<DoubleArray>           // x1, y1, x2, y2: vão numa parede
    )

    class Casa(val nome: String, val andares: List<Andar>)

    // ------------------------------------------------------------------ planta atual → formato 1

    private fun tipoDe(id: String) = when {
        id == "estar" || id == "jantar" -> "sala"
        id.startsWith("quarto") -> "quarto"
        id == "cozinha" || id == "banheiro" || id == "corredor" -> id
        else -> "outro"
    }

    /** Converte a planta escrita no app (FloorPlan) e as luzes escolhidas em cada cômodo. */
    fun daPlantaAtual(luzesEscolhidas: Map<String, List<String>>): Casa {
        val zonas = FloorPlan.zones
        val comodos = zonas.map { z ->
            Comodo(
                id = z.id, nome = z.name, tipo = tipoDe(z.id),
                partes = z.rects.map { r -> DoubleArray(4) { i -> r[i].toDouble() } },
                luzes = z.lightPoints.map { p -> doubleArrayOf(p[0].toDouble(), p[1].toDouble()) },
                rotulo = doubleArrayOf(z.label[0].toDouble(), z.label[1].toDouble()),
                entidades = luzesEscolhidas[z.id].orEmpty(),
                nomesFalados = FloorPlan.spokenNames(z.id),
                palavrasLuz = z.keywords, evitarPalavras = z.avoid, mostrarNome = z.showLabel
            )
        }
        val paredes = FloorPlan.walls.map { w -> DoubleArray(4) { i -> w[i].toDouble() } }
        val (integrados, portas) = aberturas(comodos, paredes)
        return Casa("Minha casa", listOf(Andar("terreo", "Térreo", comodos, integrados, portas)))
    }

    /**
     * A partir das paredes desenhadas à mão, descobre o que é ambiente integrado (nenhuma parede entre
     * os dois cômodos) e o que é porta (vão numa borda que tem parede em outra parte).
     */
    fun aberturas(comodos: List<Comodo>, paredes: List<DoubleArray>): Pair<List<Pair<String, String>>, List<DoubleArray>> {
        val integrados = ArrayList<Pair<String, String>>()
        val portas = ArrayList<DoubleArray>()
        // bordas entre dois cômodos
        for (i in comodos.indices) for (j in i + 1 until comodos.size) {
            val a = comodos[i]; val b = comodos[j]
            val comuns = ArrayList<Seg>()
            for (ra in a.partes) for (rb in b.partes) comuns.addAll(bordaComum(ra, rb))
            if (comuns.isEmpty()) continue
            val vaos = comuns.map { s -> s to descobertos(s, paredes) }
            if (vaos.all { (s, v) -> v.size == 1 && v[0].len() >= s.len() - EPS }) {
                integrados.add(a.id to b.id)
            } else {
                for ((_, v) in vaos) for (g in v) if (g.len() >= 0.2) portas.add(g.arr())
            }
        }
        // borda com o lado de fora
        for (s in bordaExterna(comodos)) for (g in descobertos(s, paredes)) if (g.len() >= 0.2) portas.add(g.arr())
        return integrados to portas
    }

    // ------------------------------------------------------------------ paredes a partir dos cômodos

    /** Paredes do andar (x1, y1, x2, y2), já sem as portas e sem as bordas de ambientes integrados. */
    fun paredes(andar: Andar): List<DoubleArray> {
        val integ = andar.integrados.flatMap { listOf(it, it.second to it.first) }.toSet()
        val bruto = ArrayList<Seg>()
        val cs = andar.comodos
        for (i in cs.indices) for (j in i + 1 until cs.size) {
            if ((cs[i].id to cs[j].id) in integ) continue
            for (ra in cs[i].partes) for (rb in cs[j].partes) bruto.addAll(bordaComum(ra, rb))
        }
        bruto.addAll(bordaExterna(cs))
        val portas = andar.portas.map { Seg.of(it) }
        val out = ArrayList<DoubleArray>()
        for (s in uniao(bruto)) for (p in subtrai(s, portas)) if (p.len() > EPS) out.add(p.arr())
        return out
    }

    // ------------------------------------------------------------------ geometria (segmentos alinhados)

    /** Segmento horizontal (h=true: y=c, de a até b) ou vertical (x=c, de a até b). */
    class Seg(val h: Boolean, val c: Double, val a: Double, val b: Double) {
        fun len() = b - a
        fun arr() = if (h) doubleArrayOf(a, c, b, c) else doubleArrayOf(c, a, c, b)
        companion object {
            fun of(w: DoubleArray): Seg = if (Math.abs(w[1] - w[3]) < EPS)
                Seg(true, w[1], minOf(w[0], w[2]), maxOf(w[0], w[2])) else Seg(false, w[0], minOf(w[1], w[3]), maxOf(w[1], w[3]))
        }
    }

    private fun bordas(r: DoubleArray) = listOf(
        Seg(true, r[1], r[0], r[2]), Seg(true, r[3], r[0], r[2]),
        Seg(false, r[0], r[1], r[3]), Seg(false, r[2], r[1], r[3]))

    /** Trechos onde as bordas de dois retângulos se encostam. */
    fun bordaComum(ra: DoubleArray, rb: DoubleArray): List<Seg> {
        val out = ArrayList<Seg>()
        for (x in bordas(ra)) for (y in bordas(rb)) {
            if (x.h != y.h || Math.abs(x.c - y.c) > EPS) continue
            val a = maxOf(x.a, y.a); val b = minOf(x.b, y.b)
            if (b - a > EPS) out.add(Seg(x.h, x.c, a, b))
        }
        return out
    }

    /** Bordas dos cômodos que não encostam em nenhum outro retângulo (contorno da casa). */
    fun bordaExterna(comodos: List<Comodo>): List<Seg> {
        val todas = comodos.flatMap { c -> c.partes }
        val out = ArrayList<Seg>()
        for ((k, r) in todas.withIndex()) for (s in bordas(r)) {
            val cobertos = ArrayList<Seg>()
            for ((m, o) in todas.withIndex()) if (m != k) cobertos.addAll(bordaComum(r, o).filter { it.h == s.h && Math.abs(it.c - s.c) < EPS })
            out.addAll(subtrai(s, cobertos))
        }
        return uniao(out)
    }

    /** Partes de [s] que nenhuma parede cobre. */
    fun descobertos(s: Seg, paredes: List<DoubleArray>): List<Seg> =
        subtrai(s, paredes.map { Seg.of(it) }.filter { it.h == s.h && Math.abs(it.c - s.c) < EPS })

    private fun subtrai(s: Seg, tira: List<Seg>): List<Seg> {
        var pedacos = listOf(s)
        for (t in tira) {
            if (t.h != s.h || Math.abs(t.c - s.c) > EPS) continue
            pedacos = pedacos.flatMap { p ->
                if (t.b <= p.a + EPS || t.a >= p.b - EPS) listOf(p)
                else listOfNotNull(
                    if (t.a - p.a > EPS) Seg(p.h, p.c, p.a, t.a) else null,
                    if (p.b - t.b > EPS) Seg(p.h, p.c, t.b, p.b) else null)
            }
        }
        return pedacos
    }

    /** Junta segmentos colineares que se tocam ou se sobrepõem. */
    fun uniao(segs: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        val grupos = segs.groupBy { (if (it.h) "h" else "v") + Math.round(it.c * 100) }
        for ((_, g) in grupos) {
            val ord = g.sortedBy { it.a }
            var cur = ord[0]
            for (s in ord.drop(1)) {
                cur = if (s.a <= cur.b + EPS) Seg(cur.h, cur.c, cur.a, maxOf(cur.b, s.b)) else { out.add(cur); s }
            }
            out.add(cur)
        }
        return out
    }

    // ------------------------------------------------------------------ JSON

    private fun nums(a: DoubleArray) = JSONArray().apply { a.forEach { put(Math.round(it * 1000) / 1000.0) } }
    private fun strs(l: List<String>) = JSONArray().apply { l.forEach { put(it) } }

    fun toJson(casa: Casa): JSONObject {
        val andares = JSONArray()
        for (a in casa.andares) {
            val cs = JSONArray()
            for (c in a.comodos) {
                val o = JSONObject().put("id", c.id).put("nome", c.nome).put("tipo", c.tipo)
                    .put("partes", JSONArray().apply { c.partes.forEach { put(nums(it)) } })
                    .put("luzes", JSONArray().apply { c.luzes.forEach { put(nums(it)) } })
                    .put("entidades", strs(c.entidades)).put("nomesFalados", strs(c.nomesFalados))
                    .put("mostrarNome", c.mostrarNome)
                c.rotulo?.let { o.put("rotulo", nums(it)) }
                if (c.palavrasLuz.isNotEmpty()) o.put("palavrasLuz", strs(c.palavrasLuz))
                if (c.evitarPalavras.isNotEmpty()) o.put("evitarPalavras", strs(c.evitarPalavras))
                cs.put(o)
            }
            andares.put(JSONObject().put("id", a.id).put("nome", a.nome).put("comodos", cs)
                .put("integrados", JSONArray().apply { a.integrados.forEach { put(JSONArray().put(it.first).put(it.second)) } })
                .put("portas", JSONArray().apply { a.portas.forEach { put(nums(it)) } }))
        }
        return JSONObject().put("formato", FORMATO).put("nome", casa.nome).put("andares", andares)
    }

    private fun dArr(a: JSONArray?) = if (a == null) DoubleArray(0) else DoubleArray(a.length()) { a.optDouble(it) }
    private fun dList(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { dArr(it) }
    private fun sList(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    fun fromJson(o: JSONObject): Casa {
        val f = o.optInt("formato", 0)
        require(f in 1..FORMATO) { if (f > FORMATO) "planta feita por uma versão mais nova do app" else "arquivo não é uma planta" }
        val andares = o.optJSONArray("andares") ?: JSONArray()
        return Casa(o.optString("nome", "Minha casa"), (0 until andares.length()).map { i ->
            val a = andares.getJSONObject(i)
            val cs = a.optJSONArray("comodos") ?: JSONArray()
            Andar(a.optString("id"), a.optString("nome"), (0 until cs.length()).map { k ->
                val c = cs.getJSONObject(k)
                Comodo(c.optString("id"), c.optString("nome"), c.optString("tipo", "outro"),
                    dList(c.optJSONArray("partes")), dList(c.optJSONArray("luzes")),
                    c.optJSONArray("rotulo")?.let { dArr(it) }, sList(c.optJSONArray("entidades")),
                    sList(c.optJSONArray("nomesFalados")), sList(c.optJSONArray("palavrasLuz")),
                    sList(c.optJSONArray("evitarPalavras")), c.optBoolean("mostrarNome", true))
            }, (a.optJSONArray("integrados") ?: JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.filter { it.length() == 2 }.map { it.optString(0) to it.optString(1) }
            }, dList(a.optJSONArray("portas")))
        })
    }

    /** Problemas que impedem usar a planta (lista vazia = válida). */
    fun validar(casa: Casa): List<String> {
        val erros = ArrayList<String>()
        for (a in casa.andares) {
            val ids = a.comodos.map { it.id }
            if (ids.size != ids.toSet().size) erros.add("${a.nome}: ids de cômodo repetidos")
            for (c in a.comodos) {
                if (c.partes.isEmpty()) erros.add("${c.nome}: sem nenhuma parte")
                for (p in c.partes) if (p.size != 4 || p[2] - p[0] < 0.5 - EPS || p[3] - p[1] < 0.5 - EPS) erros.add("${c.nome}: parte menor que 0,5 × 0,5 m")
            }
            for (i in a.comodos.indices) for (j in i + 1 until a.comodos.size)
                for (p in a.comodos[i].partes) for (q in a.comodos[j].partes)
                    if (minOf(p[2], q[2]) - maxOf(p[0], q[0]) > EPS && minOf(p[3], q[3]) - maxOf(p[1], q[1]) > EPS)
                        erros.add("${a.comodos[i].nome} e ${a.comodos[j].nome} se sobrepõem")
            val ents = a.comodos.flatMap { it.entidades }
            if (ents.size != ents.toSet().size) erros.add("${a.nome}: a mesma luz está em dois cômodos")
            for ((x, y) in a.integrados) if (x !in ids || y !in ids) erros.add("${a.nome}: integrado com cômodo que não existe")
        }
        return erros
    }
}
