package br.com.jarvishome.commands

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Lista de compras e de tarefas por voz, nas listas (todo.*) do Home Assistant:
 *   "coloca leite e pão na lista de compras", "adiciona na lista ovos", "o que tem na lista?",
 *   "tira o leite da lista", "comprei pão", "coloca pagar a luz na lista de tarefas".
 * Só a parte de entender e responder; quem chama o Home Assistant é o HaDirectBackend.
 */
object Lists {

    enum class Which { SHOPPING, TASKS }

    sealed class Cmd {
        abstract val which: Which
        data class Add(val items: List<String>, override val which: Which) : Cmd()
        data class Remove(val items: List<String>, override val which: Which, val bought: Boolean) : Cmd()
        data class Read(override val which: Which) : Cmd()
    }

    data class Item(val uid: String, val summary: String)
    data class TodoList(val entityId: String, val name: String)

    // ------------------------------------------------------------------ texto

    /** Minúsculas e sem acento, com o MESMO tamanho do original (para recortar o item com acento). */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            sb.append(d[0].lowercaseChar())
        }
        return sb.toString()
    }

    private const val LIST = "(?:(?:a|na|da|pra|para a|para|à|pra minha|na minha|da minha|na nossa|da nossa|a minha|minha|nossa) )?lista" +
        "(?: (?:de compras|das compras|do mercado|de mercado|do supermercado|de supermercado|da feira|de tarefas|das tarefas|de afazeres|de coisas (?:pra|para) fazer|de pendencias))?"
    private const val ADD_VERB = "(?:coloca|coloque|colocar|adiciona|adicione|adicionar|acrescenta|acrescente|poe|ponha|bota|bote|botar|inclui|inclua|incluir|anota|anote|anotar|insere|insira|inserir|lanca|lance|adiciono|coloco)"
    private const val DEL_VERB = "(?:tira|tire|tirar|remove|remova|remover|apaga|apague|apagar|risca|risque|riscar|exclui|exclua|excluir|deleta|delete|retira|retire)"

    private val RE_ADD_ITEM_FIRST = Regex("^(?:por favor )?$ADD_VERB (.+?) (?:na|a|à|pra|para a|para|no) ?$LIST(?: por favor)?$")
    private val RE_ADD_LIST_FIRST = Regex("^(?:por favor )?$ADD_VERB (?:na|a|à|pra|para a|para|no) $LIST (.+)$")
    private val RE_DEL = Regex("^(?:por favor )?$DEL_VERB (.+?) (?:da|na|do) ?$LIST(?: por favor)?$")
    private val RE_DEL_LIST_FIRST = Regex("^(?:por favor )?$DEL_VERB (?:da|na) $LIST (.+)$")
    private val RE_BOUGHT = Regex("^(?:eu )?(?:ja )?(?:comprei|compramos|ja comprei|peguei) (.+)$")
    private val RE_DONE_TASK = Regex("^(?:ja )?(?:fiz|terminei|conclui|resolvi) (.+?) (?:da|na) ?$LIST$")
    private val RE_READ = Regex("\\b(?:o que (?:tem|ha|falta|esta)|que tem|quais (?:sao )?(?:os )?itens|le|leia|ler|me (?:fala|diz|diga|conta)|fala|diz|como (?:esta|ta)|tem (?:algo|alguma coisa|coisa))\\b.*\\blista\\b|\\blista\\b.*\\b(?:tem o que|o que tem)\\b|^(?:o que )?falta comprar\\b|^(?:a )?minha lista\\b")

    private fun whichOf(f: String): Which =
        if (Regex("\\b(tarefas|afazeres|coisas (pra|para) fazer|pendencias)\\b").containsMatchIn(f)) Which.TASKS else Which.SHOPPING

    private fun clean(text: String): String = text.trim().trimEnd('.', '!', '?', ',', ';').trim().replace(Regex("\\s+"), " ")

    /** "leite, pão e 2 dúzias de ovos" → [Leite, Pão, 2 dúzias de ovos]. */
    fun splitItems(raw: String): List<String> {
        val parts = raw.split(Regex(",|;| e (?=\\S)| mais ")).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { p ->
            var s = p.replace(Regex("^(?i)(o|a|os|as|um|uma|uns|umas|tambem|também) "), "").trim()
            s = s.replace(Regex("(?i) (tambem|também|por favor)$"), "").trim()
            s.replaceFirstChar { it.uppercaseChar() }
        }.filter { it.isNotEmpty() && fold(it) !in setOf("lista", "isso", "tudo") }
    }

    /** null = não é comando de lista. */
    fun parse(text: String): Cmd? {
        val orig = clean(text)
        val f = fold(orig)
        if (f.isEmpty()) return null
        fun cut(m: MatchResult, g: Int) = orig.substring(m.groups[g]!!.range.first, m.groups[g]!!.range.last + 1)
        if (f.contains("lista")) {
            RE_ADD_ITEM_FIRST.find(f)?.let { m -> splitItems(cut(m, 1)).takeIf { it.isNotEmpty() }?.let { return Cmd.Add(it, whichOf(f)) } }
            RE_ADD_LIST_FIRST.find(f)?.let { m -> splitItems(cut(m, 1)).takeIf { it.isNotEmpty() }?.let { return Cmd.Add(it, whichOf(f)) } }
            RE_DEL.find(f)?.let { m -> splitItems(cut(m, 1)).takeIf { it.isNotEmpty() }?.let { return Cmd.Remove(it, whichOf(f), false) } }
            RE_DEL_LIST_FIRST.find(f)?.let { m -> splitItems(cut(m, 1)).takeIf { it.isNotEmpty() }?.let { return Cmd.Remove(it, whichOf(f), false) } }
            RE_DONE_TASK.find(f)?.let { m -> splitItems(cut(m, 1)).takeIf { it.isNotEmpty() }?.let { return Cmd.Remove(it, whichOf(f), true) } }
            if (RE_READ.containsMatchIn(f)) return Cmd.Read(whichOf(f))
            return null
        }
        if (Regex("^(?:o que )?falta comprar\\b").containsMatchIn(f)) return Cmd.Read(Which.SHOPPING)
        RE_BOUGHT.find(f)?.let { m -> splitItems(cut(m, 1)).takeIf { it.isNotEmpty() }?.let { return Cmd.Remove(it, Which.SHOPPING, true) } }
        return null
    }

    // ------------------------------------------------------------------ listas do Home Assistant

    /** Listas todo.* disponíveis. */
    fun listsFrom(states: JSONArray): List<TodoList> {
        val out = ArrayList<TodoList>()
        for (i in 0 until states.length()) {
            val st = states.optJSONObject(i) ?: continue
            val id = st.optString("entity_id")
            if (!id.startsWith("todo.") || st.optString("state") == "unavailable") continue
            out.add(TodoList(id, st.optJSONObject("attributes")?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id))
        }
        return out
    }

    /** Qual lista usar: a escolhida nas configurações; senão, pelo nome; senão, a única que houver. */
    fun pick(which: Which, lists: List<TodoList>, chosen: String): TodoList? {
        if (chosen.isNotBlank()) lists.firstOrNull { it.entityId == chosen }?.let { return it }
        val re = if (which == Which.SHOPPING) Regex("compra|shopping|mercado|feira") else Regex("tarefa|task|afazer|pendenc")
        lists.firstOrNull { re.containsMatchIn(fold(it.entityId.removePrefix("todo.") + " " + it.name)) }?.let { return it }
        if (which == Which.SHOPPING && lists.size == 1) return lists[0]
        return null
    }

    /** Resposta de todo.get_items → itens pendentes. */
    fun itemsFrom(resp: JSONObject, entity: String): List<Item> {
        val root = resp.optJSONObject("service_response") ?: resp
        val ent = root.optJSONObject(entity) ?: return emptyList()
        val arr = ent.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("status", "needs_action") != "needs_action") continue
            val s = o.optString("summary")
            if (s.isNotBlank()) out.add(Item(o.optString("uid"), s))
        }
        return out
    }

    private fun key(s: String) = fold(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        .split(' ').filter { it !in setOf("o", "a", "os", "as", "de", "do", "da", "um", "uma") }
        .joinToString(" ") { it.removeSuffix("s") }

    /** Item da lista que corresponde ao que foi falado ("pão" acha "Pão francês"; "ovo" acha "Ovos"). */
    fun match(spoken: String, items: List<Item>): Item? {
        val k = key(spoken)
        if (k.isEmpty()) return null
        items.firstOrNull { key(it.summary) == k }?.let { return it }
        items.firstOrNull { val ik = key(it.summary); ik.startsWith("$k ") || ik.endsWith(" $k") || ik.contains(" $k ") }?.let { return it }
        items.firstOrNull { k.contains(key(it.summary)) && key(it.summary).length >= 3 }?.let { return it }
        // um erro de letra ("arros" → "arroz")
        return items.firstOrNull { val ik = key(it.summary); ik.length >= 4 && TextDistance.lev(ik, k) <= 1 }
    }

    // ------------------------------------------------------------------ falas

    fun listName(which: Which) = if (which == Which.SHOPPING) "lista de compras" else "lista de tarefas"

    fun join(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " e " + items.last()
    }

    fun readSpeech(which: Which, items: List<Item>): String {
        if (items.isEmpty()) return "A ${listName(which)} está vazia."
        val names = items.map { it.summary.lowercase() }
        val shown = names.take(12)
        val more = names.size - shown.size
        val n = items.size
        return "Na ${listName(which)} " + (if (n == 1) "tem 1 item: " else "tem $n itens: ") + join(shown) +
            (if (more > 0) " e mais $more" else "") + "."
    }

    fun addSpeech(which: Which, added: List<String>, already: List<String>): String {
        val parts = ArrayList<String>()
        if (added.isNotEmpty()) parts.add("Coloquei ${join(added.map { it.lowercase() })} na ${listName(which)}.")
        if (already.isNotEmpty()) parts.add(join(already).replaceFirstChar { it.uppercaseChar() } +
            (if (already.size == 1) " já estava" else " já estavam") + " na lista.")
        return parts.joinToString(" ")
    }

    fun removeSpeech(which: Which, removed: List<String>, missing: List<String>, bought: Boolean): String {
        val parts = ArrayList<String>()
        if (removed.isNotEmpty()) parts.add((if (bought && which == Which.SHOPPING) "Marquei como comprado: " else if (bought) "Marquei como feito: " else "Tirei da lista: ") +
            join(removed.map { it.lowercase() }) + ".")
        if (missing.isNotEmpty()) parts.add("Não achei ${join(missing.map { it.lowercase() })} na ${listName(which)}.")
        return parts.joinToString(" ")
    }
}

/** Distância de edição pequena (sem dependência de outras classes). */
object TextDistance {
    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
