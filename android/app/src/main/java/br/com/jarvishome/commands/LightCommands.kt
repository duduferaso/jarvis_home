package br.com.jarvishome.commands

import br.com.jarvishome.network.TextCorrector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ligar e desligar luzes direto pelo app, sem depender das frases do Home Assistant.
 *
 * 1) Exemplos treinados: cada pessoa grava "liga a luz do quarto" etc.; o app guarda COMO o
 *    Whisper transcreveu a voz dela ("lisa luz do zoquart") e reconhece aquilo depois.
 * 2) Frase: verbo (liga/acende/desliga/apaga, tolerando erros como "deliga") + nome da luz
 *    (comparado com as luzes da casa, tolerando erros de transcrição).
 * Pergunta ("a luz está ligada?"), porcentagem, cor ou dúvida entre duas luzes ficam para o
 * Home Assistant. Lógica pura, testável na JVM.
 */
object LightCommands {

    data class Light(val entityId: String, val name: String)
    data class Trained(val heard: String, val on: Boolean, val target: String)
    class Action(val on: Boolean, val lights: List<Light>, val all: Boolean, val how: String)

    const val ALL = "*"

    /** Chamado depois de acionar uma luz (para a tela atualizar logo). */
    @Volatile var onChange: (() -> Unit)? = null

    private val STOP = setOf(
        "a", "o", "as", "os", "luz", "luzes", "lampada", "lampadas", "lampadinha", "iluminacao",
        "do", "da", "dos", "das", "de", "no", "na", "nos", "nas", "em", "por", "favor", "pfv", "pra", "para",
        "pro", "agora", "ai", "ja", "tony", "toni", "hey", "ei", "hei", "oi", "me", "voce", "pode", "aqui"
    )
    private val ALL_WORDS = setOf("todas", "todos", "tudo", "toda", "todo")
    private val STATE_WORDS = Regex("^(des)?ligad[oa]s?$|^acesas?$|^apagad[oa]s?$")

    fun words(s: String): List<String> =
        TextCorrector.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }

    private fun ratio(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        if (m == 0) return 1.0
        val r = 1.0 - TextCorrector.levenshtein(a, b).toDouble() / m
        // letras trocadas de lugar ("quatro" × "quarto") contam como quase iguais
        return if (a.length >= 4 && a.length == b.length && a.toList().sorted() == b.toList().sorted()) maxOf(r, 0.85) else r
    }

    /** Luzes da casa: light.* e interruptores com nome de luz (mesma regra da tela). */
    fun lightsFrom(states: JSONArray): List<Light> {
        val out = ArrayList<Light>()
        for (i in 0 until states.length()) {
            val st = states.optJSONObject(i) ?: continue
            val id = st.optString("entity_id")
            val domain = id.substringBefore('.')
            val attrs = st.optJSONObject("attributes")
            val name = (if (attrs != null && attrs.has("friendly_name") && !attrs.isNull("friendly_name"))
                attrs.optString("friendly_name") else "").ifEmpty { id.substringAfter('.').replace('_', ' ') }
            val n = TextCorrector.normalize(name)
            if (domain == "light" || (domain == "switch" && (n.startsWith("luz") || n.contains("lampada") || n.contains(" luz ")))) {
                out.add(Light(id, name.trim()))
            }
        }
        return out
    }

    private enum class P { ON, OFF }

    private fun polarityOf(t: String, next: String): P? = when {
        t.startsWith("desl") || t.startsWith("dezl") || t.startsWith("apag") || t.startsWith("desativ") ||
            t in setOf("deliga", "deligar", "disliga", "esliga", "desliga", "dsliga") || t.contains("deslig") -> P.OFF
        (t == "de" || t == "des" || t == "dis" || t == "this") && next.startsWith("lig") -> P.OFF
        t.startsWith("lig") || t.startsWith("acend") || t.startsWith("ascend") || t.startsWith("assend") ||
            t.startsWith("acenda") || t in setOf("ativa", "ativar", "ative", "acende") -> P.ON
        else -> null
    }

    private fun keyOf(l: Light): List<String> = words(l.name).filter { it !in STOP }.ifEmpty { words(l.name) }

    private fun tokenMatch(a: String, b: String) = a == b || (minOf(a.length, b.length) >= 4 && ratio(a, b) >= 0.75)

    private fun score(group: List<String>, key: List<String>): Double {
        val s1 = ratio(group.joinToString(""), key.joinToString(""))
        val matchedGroup = group.count { g -> key.any { tokenMatch(g, it) } }
        val s2 = if (matchedGroup == group.size) {
            val keyCov = key.count { k -> group.any { tokenMatch(it, k) } }.toDouble() / key.size
            0.75 + 0.25 * keyCov
        } else 0.0
        return maxOf(s1, s2)
    }

    /** A luz que o grupo de palavras descreve, ou null se nenhuma (ou mais de uma) servir. */
    private fun findLight(group: List<String>, lights: List<Light>): Light? {
        if (group.isEmpty()) return null
        val scored = lights.map { it to score(group, keyOf(it)) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < 0.72) return null
        if (scored.drop(1).any { it.second >= best.second - 0.08 }) return null
        return best.first
    }

    /** Entende a frase como comando de luz pelas palavras (sem treino). */
    private fun parse(text: String, lights: List<Light>): Action? {
        val w = words(text)
        if (w.isEmpty() || lights.isEmpty()) return null
        if (w.any { STATE_WORDS.matches(it) || it.any(Char::isDigit) || it == "porcento" || it == "cento" }) return null
        var pol: P? = null
        var pIdx = -1
        var i = 0
        while (i < minOf(w.size, 5)) {
            val p = polarityOf(w[i], w.getOrElse(i + 1) { "" })
            if (p != null) {
                if (pol == null) { pol = p; pIdx = i }
                else if (pol != p) return null
                if (p == P.OFF && w[i] in setOf("de", "des", "dis", "this")) i++ // "de liga" = desliga
            }
            i++
        }
        if (pol == null) return null
        // pula o verbo (e o "liga" de "de liga")
        var start = pIdx + 1
        if (start < w.size && pol == P.OFF && w[pIdx] in setOf("de", "des", "dis", "this")) start++
        val rest = w.drop(start)
        if (rest.any { polarityOf(it, "") != null && polarityOf(it, "") != pol }) return null
        val on = pol == P.ON
        val hasLightWord = w.any { it.startsWith("luz") || it.startsWith("lampad") }
        if (rest.any { it in setOf("todas", "todos", "toda", "todo") } || (rest.contains("tudo") && hasLightWord)) {
            return Action(on, lights, true, "frase")
        }
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (t in rest) {
            if (t == "e") { if (cur.isNotEmpty()) groups.add(cur); cur = ArrayList() }
            else if (t !in STOP && polarityOf(t, "") == null) cur.add(t)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        if (groups.isEmpty()) return if (lights.size == 1 && hasLightWord) Action(on, lights, false, "frase") else null
        val found = groups.map { findLight(it, lights) ?: return null }.distinct()
        return Action(on, found, false, "frase")
    }

    /** Exemplo treinado mais parecido (só vale se não houver outro comando quase igual). */
    private fun fromTrained(text: String, lights: List<Light>, trained: List<Trained>): Pair<Action, Double>? {
        val q = words(text).joinToString(" ")
        if (q.isEmpty()) return null
        val best = HashMap<Pair<Boolean, String>, Double>()
        for (t in trained) {
            val r = ratio(q, words(t.heard).joinToString(" "))
            val k = t.on to t.target
            if (r > (best[k] ?: -1.0)) best[k] = r
        }
        val ranked = best.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        if (top.value < 0.85) return null
        if (ranked.drop(1).any { it.value >= top.value - 0.05 }) return null
        val (on, target) = top.key
        val action = if (target == ALL) Action(on, lights, true, "treino")
            else Action(on, listOf(lights.firstOrNull { it.entityId == target } ?: return null), false, "treino")
        return action to top.value
    }

    private fun same(a: Action, b: Action) = a.on == b.on && a.all == b.all && a.lights.map { it.entityId }.toSet() == b.lights.map { it.entityId }.toSet()

    /** O comando de luz desta frase, ou null para deixar com o Home Assistant. */
    fun resolve(text: String, lights: List<Light>, trained: List<Trained> = emptyList()): Action? {
        if (lights.isEmpty()) return null
        val parsed = parse(text, lights)
        val learned = fromTrained(text, lights, trained)
        return when {
            learned == null -> parsed
            parsed == null -> learned.first
            same(parsed, learned.first) -> learned.first
            // O treino só contraria a frase quando é idêntico ao que a pessoa gravou.
            learned.second >= 0.999 -> learned.first
            else -> parsed
        }
    }

    fun label(on: Boolean, target: Light?): String =
        (if (on) "Ligar " else "Desligar ") + (target?.name ?: "todas as luzes")

    fun speech(a: Action): String {
        if (a.all) return if (a.on) "Todas as luzes ligadas." else "Todas as luzes desligadas."
        val l = a.lights
        if (l.size == 1) {
            val n = l[0].name
            val norm = TextCorrector.normalize(n)
            if (norm.startsWith("luz ")) {
                return n.substring(0, 1).uppercase() + n.substring(1).lowercase() + if (a.on) " ligada." else " desligada."
            }
            return (if (a.on) "Liguei " else "Desliguei ") + n.lowercase() + "."
        }
        val names = l.map { br.com.jarvishome.home.HomeParser.shortLightName(it.name).lowercase() }
        return (if (a.on) "Liguei: " else "Desliguei: ") + names.dropLast(1).joinToString(", ") + " e " + names.last() + "."
    }

    fun encode(list: List<Trained>): String {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("heard", t.heard).put("on", t.on).put("target", t.target))
        return arr.toString()
    }

    fun decode(s: String): List<Trained> = try {
        val arr = JSONArray(s.ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val h = o.optString("heard")
            if (h.isBlank()) null else Trained(h, o.optBoolean("on", true), o.optString("target"))
        }
    } catch (e: Exception) { emptyList() }
}
