package br.com.jarvishome.commands

import br.com.jarvishome.network.TextCorrector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reforço: quando um comando dá certo, o Jarvis guarda o que funcionou.
 *  - Chamada: a gravação daquele "Hey Tony" (com o barulho real da casa) vira mais um exemplo.
 *  - Correções: se a casa só entendeu na 2ª tentativa, a frase ouvida passa a ir direto corrigida.
 *  - Luzes: a frase ouvida (mesmo com erro do Whisper) passa a ligar/desligar aquela luz.
 * "Hey Tony, não era isso" desfaz o último aprendizado. Parte pura (sem Android).
 */
object Learning {

    class Fix(val heard: String, val sent: String, var hits: Int, var last: Long)

    /** Chamados pela sessão; o serviço guarda nas configurações. */
    @Volatile var onLearnFix: ((heard: String, sent: String) -> Unit)? = null
    @Volatile var onLearnLight: ((heard: String, on: Boolean, target: String) -> Unit)? = null
    /** Desfaz o último aprendizado; devolve o que foi esquecido (ou null). */
    @Volatile var onUndo: (() -> String?)? = null

    const val MAX_FIXES = 60

    fun key(text: String): String = LightCommands.words(text).joinToString(" ")

    private val UNDO = Regex("\\b(nao|não) (era|e|é) (isso|esse|essa)\\b|\\b(errou|errado|esquece (isso|o ultimo|o último)|desfaz( o aprendizado)?|desaprende)\\b")

    /** Frase curta pedindo para desfazer ("não era isso", "errou"…). */
    fun isUndo(text: String): Boolean {
        val n = TextCorrector.normalize(text).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.split(' ').size > 6) return false
        return UNDO.containsMatchIn(n)
    }

    fun decode(s: String): MutableList<Fix> = try {
        val a = JSONArray(s.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Fix(o.optString("heard"), o.optString("sent"), o.optInt("hits", 1), o.optLong("last", 0L))
                .takeIf { it.heard.isNotBlank() && it.sent.isNotBlank() }
        }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    fun encode(list: List<Fix>): String {
        val a = JSONArray()
        for (f in list) a.put(JSONObject().put("heard", f.heard).put("sent", f.sent).put("hits", f.hits).put("last", f.last))
        return a.toString()
    }

    /** Acrescenta (ou reforça) uma correção. Mantém as [MAX_FIXES] mais usadas/recentes. */
    fun addFix(list: MutableList<Fix>, heard: String, sent: String, now: Long): MutableList<Fix> {
        val k = key(heard)
        if (k.isEmpty() || k == key(sent)) return list
        val old = list.firstOrNull { key(it.heard) == k }
        if (old != null) {
            if (key(old.sent) == key(sent)) { old.hits++; old.last = now } else { list.remove(old); list.add(Fix(heard, sent, 1, now)) }
        } else list.add(Fix(heard, sent, 1, now))
        while (list.size > MAX_FIXES) list.remove(list.minByOrNull { it.hits * 1_000_000_000L + it.last / 1000 }!!)
        return list
    }

    /** Correção aprendida para esta frase (comparação exata depois de normalizar), ou null. */
    fun lookup(fixes: Map<String, String>, text: String): String? = fixes[key(text)]

    fun asMap(list: List<Fix>): Map<String, String> = list.associate { key(it.heard) to it.sent }
}
