package br.com.jarvishome.commands

import br.com.jarvishome.calendar.AgendaFormat
import br.com.jarvishome.calendar.CalendarParser
import br.com.jarvishome.network.TextCorrector
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Timers e alarmes por voz, no próprio app (funcionam sem internet):
 *   "timer de 5 minutos", "alarme de 10 minutos para o arroz", "me avisa em 20 minutos",
 *   "temporizador de uma hora e meia", "alarme para as 7 horas", "me acorda amanhã às 6 e meia",
 *   "quanto falta?", "cancela o timer do arroz", "para" (quando está tocando).
 * Parte pura (texto → comando, lista de timers); quem toca o som é o serviço.
 */
object Timers {

    enum class Kind { TIMER, ALARM }

    class Item(val id: Long, val kind: Kind, val label: String, val endsAt: Long, val durationSec: Long) {
        @Volatile var ringingSince = 0L
    }

    sealed class Cmd {
        data class SetTimer(val seconds: Long, val label: String) : Cmd()
        data class SetAlarm(val at: LocalDateTime, val label: String) : Cmd()
        data class Query(val label: String) : Cmd()
        data class Cancel(val label: String, val all: Boolean) : Cmd()
        object Stop : Cmd()
        object AskDuration : Cmd()
    }

    class Reply(val speech: String, val done: Boolean, val continueConversation: Boolean = false)

    // ------------------------------------------------------------------ lista

    private val items = ArrayList<Item>()
    private var nextId = 1L
    @Volatile private var askedAt = 0L
    /** Quando parou de tocar (o "Hey Tony" já para o toque; o "para" que vem depois só confirma). */
    @Volatile var stoppedAt = 0L

    /** Chamado quando a lista muda (salvar e atualizar a tela). */
    @Volatile var onChange: (() -> Unit)? = null

    @Synchronized fun all(): List<Item> = items.sortedBy { it.endsAt }
    @Synchronized fun ringing(): List<Item> = items.filter { it.ringingSince > 0 }
    fun isRinging() = ringing().isNotEmpty()

    @Synchronized fun add(kind: Kind, label: String, endsAt: Long, durationSec: Long): Item {
        val it = Item(nextId++, kind, label, endsAt, durationSec)
        items.add(it)
        changed()
        return it
    }

    @Synchronized fun remove(list: Collection<Item>) {
        if (items.removeAll(list.toSet())) changed()
    }

    /** Para o que está tocando. Devolve true se havia algo tocando. */
    fun stopRinging(): Boolean {
        val r = ringing()
        if (r.isEmpty()) return false
        remove(r)
        stoppedAt = System.currentTimeMillis()
        return true
    }

    /** Os que acabaram agora (passam a "tocando"). */
    @Synchronized fun due(nowMs: Long): List<Item> {
        val out = items.filter { it.ringingSince == 0L && it.endsAt <= nowMs }
        for (i in out) i.ringingSince = nowMs
        if (out.isNotEmpty()) changed()
        return out
    }

    @Synchronized fun clear() { items.clear(); changed() }

    private fun changed() { try { onChange?.invoke() } catch (_: Exception) {} }

    @Synchronized fun encode(): String {
        val a = JSONArray()
        for (i in items) a.put(JSONObject().put("id", i.id).put("kind", i.kind.name).put("label", i.label)
            .put("endsAt", i.endsAt).put("dur", i.durationSec))
        return a.toString()
    }

    /** Recarrega a lista salva (depois de reiniciar o app). Os que venceram com o app fechado tocam logo. */
    @Synchronized fun restore(s: String) {
        items.clear()
        try {
            val a = JSONArray(s.ifBlank { "[]" })
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val kind = if (o.optString("kind") == "ALARM") Kind.ALARM else Kind.TIMER
                items.add(Item(o.optLong("id"), kind, o.optString("label"), o.optLong("endsAt"), o.optLong("dur")))
                nextId = maxOf(nextId, o.optLong("id") + 1)
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ texto

    private val TIMER_WORDS = Regex("^(timer|timers|taimer|temporizador|temporizadores|cronometro|alarme|alarmes|despertador|contagem)$")
    private val NUM = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13,
        "quatorze" to 14, "catorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezasseis" to 16, "dezessete" to 17,
        "dezoito" to 18, "dezenove" to 19, "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50,
        "sessenta" to 60, "noventa" to 90, "cem" to 100, "cento" to 100
    )
    private val VERBS = setOf(
        "coloca", "coloque", "colocar", "poe", "por", "bota", "bote", "botar", "cria", "crie", "criar", "liga", "ligue",
        "ligar", "inicia", "inicie", "iniciar", "comeca", "comece", "faz", "faca", "fazer", "programa", "programe",
        "programar", "define", "defina", "marca", "marque", "ativa", "ative", "seta", "sete", "quero", "preciso"
    )
    private val CONNECTORS = setOf(
        "de", "do", "da", "dos", "das", "para", "pra", "pro", "o", "a", "os", "as", "um", "uma", "e", "por", "favor",
        "me", "ai", "agora", "em", "no", "na", "novo", "nova", "tony", "hey", "ei", "daqui", "que", "com", "chamado", "chamada"
    )
    private val CANCEL = setOf("cancela", "cancele", "cancelar", "desliga", "desligue", "desligar", "apaga", "apague",
        "apagar", "remove", "remova", "tira", "tire", "exclui", "exclua", "para", "pare", "parar", "zera", "zerar")
    private val STOP = setOf("para", "pare", "parar", "chega", "ok", "okay", "desliga", "desligue", "silencio", "cala",
        "stop", "cancela", "basta", "obrigado", "obrigada", "beleza", "pronto", "valeu")

    private class Toks(text: String) {
        val orig = text.split(Regex("\\s+")).map { it.trim(',', '.', ';', ':', '!', '?', '"', '“', '”', '\'') }.filter { it.isNotEmpty() }
        val norm = orig.map { TextCorrector.normalize(it).replace(Regex("[^a-z0-9:]"), "") }
        val used = BooleanArray(orig.size)
    }

    private fun number(t: Toks, i: Int): Pair<Double, Int>? {
        val w = t.norm.getOrNull(i) ?: return null
        w.replace(',', '.').toDoubleOrNull()?.let { return it to i + 1 }
        val n = NUM[w] ?: return null
        // "vinte e cinco"
        if (n in setOf(20, 30, 40, 50) && t.norm.getOrNull(i + 1) == "e") {
            val u = NUM[t.norm.getOrNull(i + 2) ?: ""]
            if (u != null && u < 10) return (n + u).toDouble() to i + 3
        }
        return n.toDouble() to i + 1
    }

    private fun unitOf(w: String): Long? = when {
        w.startsWith("segundo") || w == "seg" || w == "s" -> 1
        w.startsWith("minut") || w == "min" || w == "m" -> 60
        w.startsWith("hora") || w == "h" || w == "hr" || w == "hrs" -> 3600
        else -> null
    }

    /** Soma as durações faladas ("1 hora e 30 minutos", "meia hora", "2 minutos e meio", "1h30"). */
    private fun duration(t: Toks): Long? {
        var total = 0.0
        var found = false
        var i = 0
        while (i < t.norm.size) {
            val w = t.norm[i]
            // "1h30", "10min", "90s"
            val glued = Regex("^(\\d+)(h|min|m|s|seg)(\\d+)?$").matchEntire(w)
            if (glued != null) {
                val u = unitOf(glued.groupValues[2])!!
                total += glued.groupValues[1].toDouble() * u + (glued.groupValues[3].toDoubleOrNull() ?: 0.0) * (if (u == 3600L) 60 else 1)
                t.used[i] = true; found = true; i++
                continue
            }
            // "meia hora", "meio minuto"
            if ((w == "meia" || w == "meio") && i + 1 < t.norm.size && unitOf(t.norm[i + 1]) != null) {
                total += unitOf(t.norm[i + 1])!! / 2.0
                t.used[i] = true; t.used[i + 1] = true; found = true; i += 2; continue
            }
            val num = number(t, i)
            if (num != null && num.second < t.norm.size) {
                val u = unitOf(t.norm[num.second])
                val before = t.norm.getOrNull(i - 1) ?: ""
                if (u != null && before !in setOf("as", "pras", "ate") && !(before == "para" && u == 3600L)) {
                    total += num.first * u
                    for (k in i..num.second) t.used[k] = true
                    found = true
                    i = num.second + 1
                    // "... e meia" / "... e meio"
                    if (t.norm.getOrNull(i) == "e" && t.norm.getOrNull(i + 1) in setOf("meia", "meio")) {
                        total += u / 2.0; t.used[i] = true; t.used[i + 1] = true; i += 2
                    }
                    continue
                }
            }
            i++
        }
        if (!found) return null
        // marca o "de", "em", "daqui a", "por" que vinham antes da duração
        for (k in t.norm.indices) {
            if (t.used[k] && k > 0 && !t.used[k - 1] && t.norm[k - 1] in setOf("de", "em", "por", "durante", "a")) {
                t.used[k - 1] = true
                if (t.norm[k - 1] == "a" && k > 1 && t.norm[k - 2] == "daqui") t.used[k - 2] = true
            }
        }
        val s = total.toLong()
        return if (s in 1..24 * 3600) s else null
    }

    private fun label(t: Toks): String {
        val kept = t.orig.indices.filter { !t.used[it] && t.norm[it].isNotEmpty() }.toMutableList()
        while (kept.isNotEmpty() && t.norm[kept.first()] in CONNECTORS) kept.removeAt(0)
        while (kept.isNotEmpty() && t.norm[kept.last()] in CONNECTORS) kept.removeAt(kept.size - 1)
        return kept.joinToString(" ") { t.orig[it] }.lowercase().take(40)
    }

    private fun mark(t: Toks, pred: (String) -> Boolean) { for (k in t.norm.indices) if (pred(t.norm[k])) t.used[k] = true }

    /** null = não é sobre timer/alarme (segue adiante). */
    fun parse(text: String, now: LocalDateTime, ringing: Boolean = isRinging() || System.currentTimeMillis() - stoppedAt < 20_000, hasTimers: Boolean = all().isNotEmpty(),
              pendingAsk: Boolean = System.currentTimeMillis() - askedAt < 60_000): Cmd? {
        val t = Toks(text)
        val n = t.norm.filter { it.isNotEmpty() }
        if (n.isEmpty()) return null
        val hasWord = n.any { TIMER_WORDS.matches(it) }
        val joined = n.joinToString(" ")
        val avisa = Regex("\\bme (avisa|avise|chama|chame|lembra|lembre|acorda|acorde)\\b").containsMatchIn(joined)
        val acorda = Regex("\\bme (acorda|acorde)\\b").containsMatchIn(joined) || n.any { it == "despertador" }

        // tocando: "para", "chega", "ok"… desligam
        if (ringing && (n.size <= 4 && n.any { it in STOP } || (hasWord && n.indexOfFirst { it in CANCEL } in 0..1))) return Cmd.Stop

        // cancelar: o verbo vem antes da palavra timer ("cancela o timer", "para o alarme")
        val firstWord = n.indexOfFirst { TIMER_WORDS.matches(it) }
        val cancelIdx = n.indexOfFirst { it in CANCEL }
        if (hasWord && cancelIdx in 0 until firstWord && (n[cancelIdx] !in setOf("para", "pare", "parar") || cancelIdx <= 1)) {
            mark(t) { TIMER_WORDS.matches(it) || it in CANCEL || it in setOf("todos", "todas", "o", "os", "a", "as") }
            val all = n.any { it == "todos" || it == "todas" } || n.any { it == "timers" || it == "alarmes" }
            return Cmd.Cancel(label(t), all)
        }

        // quanto falta
        val asks = Regex("\\b(quanto (tempo )?(falta|faltam|resta|restam|tem|ainda)|tempo restante|quanto ta|quanto esta|como (esta|ta) o (timer|alarme|temporizador)|quais (timers|alarmes))\\b").containsMatchIn(joined)
        if (asks && (hasWord || hasTimers)) {
            mark(t) { TIMER_WORDS.matches(it) || it in setOf("quanto", "tempo", "falta", "faltam", "resta", "restam", "tem", "ainda", "restante", "ta", "esta", "como", "quais", "no", "na") }
            return Cmd.Query(label(t))
        }

        if (!hasWord && !avisa && !pendingAsk) return null
        if (!hasWord && avisa && Regex("\\b(as|pras|amanha|hoje|dia)\\b").containsMatchIn(joined) && !acorda) return null // "me lembra amanhã às 9" é agenda

        val dur = duration(t)
        if (dur != null) {
            mark(t) { TIMER_WORDS.matches(it) || it in VERBS || it in setOf("avisa", "avise", "chama", "chame", "lembra", "lembre", "acorda", "acorde") }
            askedAt = 0
            return Cmd.SetTimer(dur, label(t))
        }
        if (!hasWord && !avisa) return null

        // alarme por horário: "alarme para as 7", "me acorda amanhã às 6 e meia"
        if (Regex("\\b(as|pras|para|meio dia|meia noite)\\b|\\d+:\\d+|\\d+ ?h\\b").containsMatchIn(joined) &&
            (acorda || n.any { it == "alarme" || it == "alarmes" })) {
            val w = CalendarParser.parseWhen(text, now)
            if (w?.time != null) {
                // "às 6 e meia" sem dizer manhã/tarde: o próximo 6:30 que vier (como um despertador)
                val explicit24 = Regex("\\b(1[3-9]|2[0-3])\\b").containsMatchIn(joined) || Regex("\\b(meio dia|meia noite)\\b").containsMatchIn(joined)
                val hours = when {
                    explicit24 -> listOf(w.time.hour)
                    Regex("\\b(manha|madrugada)\\b").containsMatchIn(joined) -> listOf(w.time.hour % 12)
                    Regex("\\b(tarde|noite)\\b").containsMatchIn(joined) -> listOf(w.time.hour % 12 + 12)
                    else -> listOf(w.time.hour % 12, w.time.hour % 12 + 12)
                }
                val explicitDay = Regex("\\b(hoje|amanha|depois de amanha|segunda|terca|quarta|quinta|sexta|sabado|domingo|dia \\d+)\\b").containsMatchIn(joined)
                val at = hours.flatMap { h ->
                    val t = w.time.withHour(h)
                    if (explicitDay) listOf(w.date.atTime(t)) else listOf(now.toLocalDate().atTime(t), now.toLocalDate().plusDays(1).atTime(t))
                }.filter { it.isAfter(now) }.minOrNull() ?: w.date.atTime(w.time).plusDays(1)
                return Cmd.SetAlarm(at, "")
            }
        }
        if (hasWord) {
            askedAt = System.currentTimeMillis()
            return Cmd.AskDuration
        }
        return null
    }

    // ------------------------------------------------------------------ respostas

    fun durationPhrase(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add(if (h == 1L) "1 hora" else "$h horas")
        if (m > 0) parts.add(if (m == 1L) "1 minuto" else "$m minutos")
        if (s > 0) parts.add(if (s == 1L) "1 segundo" else "$s segundos")
        if (parts.isEmpty()) return "0 segundos"
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
    }

    private fun remaining(i: Item, nowMs: Long): Long = maxOf(0L, (i.endsAt - nowMs + 999) / 1000)

    /** "04:32" ou "1:04:32" para a tela. */
    fun clock(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun name(i: Item): String = when {
        i.kind == Kind.ALARM -> "alarme das " + LocalDateTime.ofInstant(Instant.ofEpochMilli(i.endsAt), ZoneId.systemDefault())
            .let { "%d:%02d".format(it.hour, it.minute) }
        i.label.isNotEmpty() -> "timer " + i.label
        else -> "timer de " + durationPhrase(i.durationSec)
    }

    fun finishedPhrase(i: Item): String = when {
        i.kind == Kind.ALARM -> "Alarme! São " + LocalDateTime.ofInstant(Instant.ofEpochMilli(i.endsAt), ZoneId.systemDefault())
            .let { AgendaFormat.timePhrase(it.toLocalTime()).removePrefix("às ").removePrefix("à ") } + "."
        i.label.isNotEmpty() -> "Acabou o tempo: ${i.label}."
        else -> "Seu timer de ${durationPhrase(i.durationSec)} acabou."
    }

    private fun find(label: String): List<Item> {
        if (label.isEmpty()) return all()
        val q = LightCommands.words(label).filter { it !in CONNECTORS }
        if (q.isEmpty()) return all()
        return all().filter { i ->
            val w = LightCommands.words(i.label + " " + durationPhrase(i.durationSec))
            q.all { a -> w.any { b -> a == b || (a.length >= 4 && TextCorrector.levenshtein(a, b) <= 1) } }
        }
    }

    /** Responde ao comando (ou null se a frase não é sobre timers). */
    fun handle(text: String, now: LocalDateTime = LocalDateTime.now(), nowMs: Long = System.currentTimeMillis()): Reply? {
        val cmd = parse(text, now) ?: return null
        return when (cmd) {
            is Cmd.Stop -> { stopRinging(); Reply("Ok.", true) }
            is Cmd.AskDuration -> Reply("De quanto tempo?", false, continueConversation = true)
            is Cmd.SetTimer -> {
                if (all().size >= 10) return Reply("Já tem 10 timers ligados. Cancele algum antes.", false)
                add(Kind.TIMER, cmd.label, nowMs + cmd.seconds * 1000, cmd.seconds)
                val d = durationPhrase(cmd.seconds)
                Reply(if (cmd.label.isNotEmpty()) "Timer de $d para ${cmd.label}, começando agora." else "$d, começando agora."
                    .replaceFirstChar { it.uppercase() }, true)
            }
            is Cmd.SetAlarm -> {
                val ms = cmd.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                add(Kind.ALARM, cmd.label, ms, Duration.between(now, cmd.at).seconds)
                val day = if (cmd.at.toLocalDate() == now.toLocalDate()) "hoje" else if (cmd.at.toLocalDate() == now.toLocalDate().plusDays(1)) "amanhã" else
                    AgendaFormat.dayTitle(cmd.at.toLocalDate(), now.toLocalDate()).lowercase()
                Reply("Alarme para $day " + AgendaFormat.timePhrase(cmd.at.toLocalTime()) + ".", true)
            }
            is Cmd.Query -> {
                val list = find(cmd.label).filter { it.ringingSince == 0L }
                when {
                    all().isEmpty() -> Reply("Não tem nenhum timer ligado.", false)
                    list.isEmpty() -> Reply("Não achei esse timer.", false)
                    list.size == 1 -> {
                        val i = list[0]
                        if (i.kind == Kind.ALARM) Reply("O alarme está marcado para " + AgendaFormat.timePhrase(
                            LocalDateTime.ofInstant(Instant.ofEpochMilli(i.endsAt), ZoneId.systemDefault()).toLocalTime()).removePrefix("às ") + ".", true)
                        else Reply("Faltam " + durationPhrase(remaining(i, nowMs)) + (if (i.label.isNotEmpty()) " para ${i.label}." else "."), true)
                    }
                    else -> Reply(list.joinToString("; ") { i ->
                        if (i.kind == Kind.ALARM) name(i) else name(i) + ": faltam " + durationPhrase(remaining(i, nowMs))
                    }.replaceFirstChar { it.uppercase() } + ".", true)
                }
            }
            is Cmd.Cancel -> {
                val list = if (cmd.all) all() else find(cmd.label)
                when {
                    list.isEmpty() -> Reply(if (all().isEmpty()) "Não tem nenhum timer ligado." else "Não achei esse timer.", false)
                    else -> {
                        remove(list)
                        Reply(if (list.size == 1) name(list[0]).replaceFirstChar { it.uppercase() } + " cancelado."
                              else "Cancelei ${list.size} timers.", true)
                    }
                }
            }
        }
    }
}
