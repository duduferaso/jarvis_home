package br.com.jarvishome.calendar

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Entende pedidos de agenda em português do Brasil, sem internet e sem IA:
 *   "marca dentista amanhã às 15h", "me lembra de ligar pro banco sexta às 9 e meia",
 *   "coloca na agenda aniversário da Ana dia 20", "o que eu tenho amanhã?",
 *   "qual o meu próximo compromisso?", "desmarca o dentista de amanhã", "desmarca o último compromisso".
 * Lógica pura (testável na JVM). Datas e horas são resolvidas a partir de [now].
 */
object CalendarParser {

    sealed class Command
    data class Create(val title: String, val whenSpec: When) : Command()
    data class NeedWhen(val title: String) : Command()
    data class Query(val from: LocalDate, val days: Int, val nextOnly: Boolean) : Command()
    object UndoLast : Command()
    data class Delete(val title: String, val date: LocalDate?) : Command()

    /** Quando: data, hora (null = dia inteiro) e duração em minutos. */
    data class When(val date: LocalDate, val time: LocalTime?, val durationMin: Int) {
        val allDay: Boolean get() = time == null
    }

    // ------------------------------------------------------------------ vocabulário

    private val NUMW = "(?:(?:vinte|trinta|quarenta|cinquenta)(?: e (?:uma|um|dois|duas|tres|quatro|cinco|seis|sete|oito|nove))?" +
        "|zero|uma|um|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|quatorze|catorze|quinze" +
        "|dezesseis|dezessete|dezoito|dezenove)"
    private val HOUR = "(\\d{1,2}|$NUMW)"
    private val MIN = "(?: e (meia|\\d{1,2}|$NUMW))?"
    private val PERIOD = "(?: (?:da|de|a) (manha|tarde|noite|madrugada))?"
    private const val MONTHS = "janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro"

    private val UNITS = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "primeiro" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12,
        "treze" to 13, "quatorze" to 14, "catorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17,
        "dezoito" to 18, "dezenove" to 19
    )
    private val TENS = mapOf("vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50)

    fun number(s: String?): Int? {
        if (s.isNullOrEmpty()) return null
        s.toIntOrNull()?.let { return it }
        if (s == "meia") return 30
        var total = 0
        for (p in s.split(" e ")) total += TENS[p] ?: UNITS[p] ?: return null
        return total
    }

    // horas
    private val RE_DURATION = Regex("\\bpor (?:(meia) hora|uma hora e meia|$HOUR (horas?|minutos?))\\b")
    private val RE_RELATIVE = Regex("\\b(?:daqui a|daqui|dentro de|em) (meia|\\d{1,3}|$NUMW) (horas?|minutos?)\\b")
    private val RE_RANGE = Regex("\\b(das|de) $HOUR(?:h(\\d{2})|:(\\d{2})|(h))?( horas?)? (?:as|ate as|ate|a) $HOUR(?:h(\\d{2})|:(\\d{2})|(h))?( horas?)?$PERIOD\\b")
    private val RE_NOON = Regex("\\b(?:(?:ao|as|a|para o|pro) )?meio(?:-| )dia(?: e (meia|\\d{1,2}|$NUMW))?\\b")
    private val RE_MIDNIGHT = Regex("\\b(?:(?:a|as|para a) )?meia(?:-| )noite\\b")
    private val RE_COLON = Regex("\\b(?:(?:as|a|pras|para as|pra) )?(\\d{1,2})(?::|h)(\\d{2})(?: ?h| horas?)?$PERIOD\\b")
    private val RE_H = Regex("\\b(?:(?:as|a|pras|para as|pra) )?(\\d{1,2}) ?h(?:oras?)?$MIN$PERIOD\\b")
    private val RE_AS = Regex("\\b(as|pras|para as|a) $HOUR( horas?)?$MIN$PERIOD\\b")
    private val RE_WORD_HOURS = Regex("\\b($NUMW) horas?$MIN$PERIOD\\b")
    private val RE_PERIOD_ONLY = Regex("\\b(?:de|pela|a|na parte da|no periodo da) (manha|tarde|noite)\\b")

    // datas
    private val RE_AFTER_TOMORROW = Regex("\\bdepois de amanha\\b")
    private val RE_TOMORROW = Regex("\\bamanha\\b")
    private val RE_TODAY = Regex("\\bhoje\\b")
    private val RE_IN_DAYS = Regex("\\b(?:daqui a|dentro de|em) (\\d{1,2}|$NUMW) dias?\\b")
    private val RE_DAY_MONTH = Regex("\\b(?:(?:no )?dia )?(\\d{1,2}|primeiro|$NUMW) de ($MONTHS)(?: de (\\d{4}))?\\b")
    private val RE_SLASH = Regex("\\b(?:(?:no )?dia )?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
    private val RE_DAY_ONLY = Regex("\\b(?:no )?dia (\\d{1,2}|primeiro|$NUMW)\\b")
    private val RE_WEEKDAY = Regex("\\b(?:(na proxima|no proximo|proxima|proximo|na|no|nesta|neste|nessa|nesse|esta|este|essa|esse) )?" +
        "(segunda|terca|quarta|quinta|sexta|sabado|domingo)(?:(?:-| )feira)?(?: (que vem|da semana que vem|da proxima semana))?\\b")

    // intenções
    private val RE_UNDO = Regex("^(?:desmarca|desmarque|desmarcar|cancela|cancele|cancelar|apaga|apague|apagar|remove|remova|remover|desfaz|desfaca|desfazer|tira|tire) " +
        "(?:(?:o|a) )?(?:ultim[oa]) (?:compromisso|evento|lembrete|marcacao|agendamento)\\b")
    private val RE_DELETE_ALWAYS = Regex("^(?:desmarca|desmarque|desmarcar)\\b")
    private val RE_DELETE_IF = Regex("^(?:cancela|cancele|cancelar|apaga|apague|apagar|remove|remova|remover|tira|tire)\\b")
    private val RE_AGENDA_WORD = Regex("\\b(?:agenda|calendario|compromisso|compromissos|evento|lembrete)\\b")
    private val RE_AGENDA_PLACE = Regex("\\b(?:na|no|da|do|na minha|no meu|da minha|do meu|na nossa|da nossa|pra|para a|para o) (?:agenda|calendario)\\b")
    private val RE_ITEM_WORD = Regex("\\b(?:(?:um|uma|o|a) )?(?:novo |nova )?(?:compromisso|evento|lembrete)(?: (?:de|do|da|para|pra))?\\b")

    private val RE_CREATE_DIRECT = Regex("^(?:por favor )?(?:marca|marque|marcar|agenda|agende|agendar)(?: (?:pra mim|para mim))?\\b")
    private val RE_CREATE_ADD = Regex("^(?:por favor )?(?:adiciona|adicione|adicionar|coloca|coloque|colocar|poe|ponha|bota|bote|anota|anote|registra|registre|inclui|inclua|cria|crie|criar|salva|salve)\\b")
    private val RE_REMIND = Regex("^(?:por favor )?(?:me )?(lembra|lembre|lembrar|lembre-me|avisa|avise)(?: me)?(?: (?:de|que|para|pra))?\\b")

    private val RE_QUERIES = listOf(
        Regex("^(?:e )?(?:o )?(?:que|oque) (?:(?:eu|a gente|nos) )?(?:tenho|temos|tem) (?:(?:de |para |pra )?(?:hoje|amanha|depois de amanha|essa semana|esta semana|nesta semana|nessa semana|semana que vem|na agenda|no calendario|marcado|agendado|na semana))"),
        Regex("^(?:me )?(?:como esta|como ta|qual e|qual a|fala|me fala|me diga|me diz|le|leia|mostra|mostre|veja|ver) (?:a |minha |nossa |o |meu )?(?:agenda|calendario|compromissos)"),
        Regex("\\bproxim[oa]s? (?:compromisso|evento|lembrete)"),
        Regex("^(?:e )?(?:eu )?(?:tenho|temos|tem) (?:algum |alguma |algo )?(?:compromisso|evento|coisa marcada|lembrete)"),
        Regex("^(?:a |minha |nossa )?agenda (?:de |para |da |pra )?(?:hoje|amanha|depois de amanha|semana|essa semana|desta semana|da semana)")
    )

    private val CONNECTORS = setOf("de", "da", "do", "das", "dos", "para", "pra", "pro", "no", "na", "nos", "nas", "em",
        "as", "a", "e", "o", "os", "um", "uma", "que", "ao", "pela", "pelo", "com")
    private val NEXT_TOKEN_OK = setOf("hoje", "amanha", "depois", "dia", "no", "na", "nesta", "neste", "proxima", "proximo",
        "segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo", "com", "para", "pra", "de", "do", "da", "e", "por", "ate")

    // ------------------------------------------------------------------ tokens

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun normTok(t: String): String = norm(t).replace(Regex("[^a-z0-9:/-]"), "").trim('-', ':', '/')

    /** Tokens originais ↔ normalizados (1:1), com marcação do que já foi interpretado. */
    private class Toks(text: String) {
        val orig = ArrayList<String>()
        val norm = ArrayList<String>()
        init {
            for (t in text.trim().split(Regex("\\s+"))) {
                val n = normTok(t)
                if (n.isNotEmpty()) { orig.add(t); norm.add(n) }
            }
        }
        val joined: String = norm.joinToString(" ")
        private val starts = IntArray(norm.size).also { arr ->
            var p = 0
            for (i in norm.indices) { arr[i] = p; p += norm[i].length + 1 }
        }
        val used = BooleanArray(norm.size)

        fun tokensIn(r: IntRange): List<Int> = norm.indices.filter { i ->
            val s = starts[i]; val e = s + norm[i].length - 1
            s <= r.last && e >= r.first
        }
        fun free(r: IntRange) = tokensIn(r).none { used[it] }
        fun mark(r: IntRange) { for (i in tokensIn(r)) used[i] = true }
        fun nextToken(r: IntRange): String? {
            val last = tokensIn(r).maxOrNull() ?: return null
            return norm.getOrNull(last + 1)
        }
        /** Primeira ocorrência ainda livre que passe em [accept]. */
        fun find(re: Regex, accept: (MatchResult) -> Boolean = { true }): MatchResult? =
            re.findAll(joined).firstOrNull { free(it.range) && accept(it) }
    }

    // ------------------------------------------------------------------ extração de data e hora

    private class Found {
        var date: LocalDate? = null
        var time: LocalTime? = null
        var end: LocalTime? = null
        var duration: Int? = null
        var relative: LocalDateTime? = null
        var periodTime: LocalTime? = null
        var weekday: DayOfWeek? = null
        var weekdayNext = false
        var weekdayStrict = false
        val any: Boolean get() = date != null || time != null || relative != null || periodTime != null || weekday != null
    }

    private fun adjustHour(h: Int, period: String?): Int? {
        var x = h
        when (period) {
            "tarde" -> if (x in 1..11) x += 12
            "noite" -> if (x in 1..11) x += 12 else if (x == 12) x = 0
            "madrugada" -> if (x == 12) x = 0
            "manha" -> {}
            null -> if (x in 1..6) x += 12   // "às 3" = 15h no uso comum; o Jarvis repete a hora na resposta
        }
        return if (x in 0..23) x else null
    }

    private fun mkTime(h: Int?, m: Int?): LocalTime? {
        if (h == null || h !in 0..23) return null
        val mm = m ?: 0
        if (mm !in 0..59) return null
        return LocalTime.of(h, mm)
    }

    private fun extract(t: Toks, now: LocalDateTime): Found {
        val f = Found()
        val today = now.toLocalDate()

        // duração e tempo relativo
        t.find(RE_DURATION)?.let { m ->
            f.duration = when {
                m.groupValues[1] == "meia" -> 30
                m.value.contains("hora e meia") -> 90
                else -> {
                    val n = number(m.groupValues[2]) ?: 1
                    if (m.groupValues[3].startsWith("min")) n else n * 60
                }
            }
            t.mark(m.range)
        }
        t.find(RE_RELATIVE)?.let { m ->
            val n = number(m.groupValues[1])
            if (n != null) {
                val minutes = if (m.groupValues[1] == "meia") 30 else if (m.groupValues[2].startsWith("min")) n else n * 60
                f.relative = now.plusMinutes(minutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
                t.mark(m.range)
            }
        }

        // intervalo "das 14 às 16"
        t.find(RE_RANGE) { m ->
            val g = m.groupValues
            val marked = g[3].isNotEmpty() || g[4].isNotEmpty() || g[5].isNotEmpty() || g[6].isNotEmpty() ||
                g[8].isNotEmpty() || g[9].isNotEmpty() || g[10].isNotEmpty() || g[11].isNotEmpty()
            g[1] == "das" || marked
        }?.let { m ->
            val g = m.groupValues
            val period = g[12].ifEmpty { null }
            val h1 = number(g[2])?.let { adjustHour(it, period) }
            val h2 = number(g[7])?.let { adjustHour(it, period) }
            val a = mkTime(h1, (g[3].ifEmpty { g[4] }).toIntOrNull())
            val b = mkTime(h2, (g[8].ifEmpty { g[9] }).toIntOrNull())
            if (a != null && b != null) {
                f.time = a; f.end = b
                t.mark(m.range)
            }
        }

        // hora única
        if (f.time == null && f.relative == null) {
            t.find(RE_NOON)?.let { m ->
                f.time = LocalTime.of(12, number(m.groupValues[1].ifEmpty { null }) ?: 0)
                t.mark(m.range)
            }
        }
        if (f.time == null && f.relative == null) {
            t.find(RE_MIDNIGHT)?.let { m -> f.time = LocalTime.MIDNIGHT; t.mark(m.range) }
        }
        if (f.time == null && f.relative == null) {
            t.find(RE_COLON)?.let { m ->
                val tm = mkTime(number(m.groupValues[1])?.let { adjustHour(it, m.groupValues[3].ifEmpty { null }) }, m.groupValues[2].toIntOrNull())
                if (tm != null) { f.time = tm; t.mark(m.range) }
            }
        }
        if (f.time == null && f.relative == null) {
            t.find(RE_H)?.let { m ->
                val tm = mkTime(number(m.groupValues[1])?.let { adjustHour(it, m.groupValues[3].ifEmpty { null }) }, number(m.groupValues[2].ifEmpty { null }))
                if (tm != null) { f.time = tm; t.mark(m.range) }
            }
        }
        if (f.time == null && f.relative == null) {
            t.find(RE_AS) { m ->
                val g = m.groupValues
                val hourTxt = g[2]
                val isDigit = hourTxt.all { it.isDigit() }
                // "a" só vale para "à uma" (13h); "às duas meninas" não é hora.
                if (g[1] == "a" && number(hourTxt) != 1) return@find false
                val strong = g[3].isNotEmpty() || g[4].isNotEmpty() || g[5].isNotEmpty()
                if (strong || isDigit) {
                    val nx = t.nextToken(m.range)
                    isDigit || nx == null || nx in NEXT_TOKEN_OK || strong
                } else {
                    val nx = t.nextToken(m.range)
                    nx == null || nx in NEXT_TOKEN_OK
                }
            }?.let { m ->
                val g = m.groupValues
                val tm = mkTime(number(g[2])?.let { adjustHour(it, g[5].ifEmpty { null }) }, number(g[4].ifEmpty { null }))
                if (tm != null) { f.time = tm; t.mark(m.range) }
            }
        }
        if (f.time == null && f.relative == null) {
            t.find(RE_WORD_HOURS)?.let { m ->
                val tm = mkTime(number(m.groupValues[1])?.let { adjustHour(it, m.groupValues[3].ifEmpty { null }) }, number(m.groupValues[2].ifEmpty { null }))
                if (tm != null) { f.time = tm; t.mark(m.range) }
            }
        }

        // datas
        t.find(RE_AFTER_TOMORROW)?.let { f.date = today.plusDays(2); t.mark(it.range) }
        if (f.date == null) t.find(RE_TOMORROW)?.let { f.date = today.plusDays(1); t.mark(it.range) }
        if (f.date == null) t.find(RE_TODAY)?.let { f.date = today; t.mark(it.range) }
        if (f.date == null) t.find(RE_IN_DAYS)?.let { m ->
            number(m.groupValues[1])?.let { n -> f.date = today.plusDays(n.toLong()); t.mark(m.range) }
        }
        if (f.date == null) t.find(RE_DAY_MONTH)?.let { m ->
            val d = number(m.groupValues[1]); val mo = MONTHS.split('|').indexOf(m.groupValues[2]) + 1
            val y = m.groupValues[3].toIntOrNull()
            resolveDayMonth(d, mo, y, today)?.let { f.date = it; t.mark(m.range) }
        }
        if (f.date == null) t.find(RE_SLASH)?.let { m ->
            val d = m.groupValues[1].toIntOrNull(); val mo = m.groupValues[2].toIntOrNull()
            var y = m.groupValues[3].toIntOrNull()
            if (y != null && y < 100) y += 2000
            if (mo != null) resolveDayMonth(d, mo, y, today)?.let { f.date = it; t.mark(m.range) }
        }
        if (f.date == null) t.find(RE_DAY_ONLY)?.let { m ->
            resolveDayOnly(number(m.groupValues[1]), today)?.let { f.date = it; t.mark(m.range) }
        }
        if (f.date == null) t.find(RE_WEEKDAY)?.let { m ->
            f.weekday = DayOfWeek.values()[listOf("segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo").indexOf(m.groupValues[2])]
            f.weekdayNext = m.groupValues[3].isNotEmpty()
            f.weekdayStrict = m.groupValues[1].startsWith("proxim") || m.groupValues[1].startsWith("na proxima") || m.groupValues[1].startsWith("no proximo")
            t.mark(m.range)
        }

        // período sem hora: "de manhã", "à tarde", "à noite"
        if (f.time == null && f.relative == null) {
            t.find(RE_PERIOD_ONLY)?.let { m ->
                f.periodTime = when (m.groupValues[1]) { "manha" -> LocalTime.of(9, 0); "tarde" -> LocalTime.of(15, 0); else -> LocalTime.of(20, 0) }
                t.mark(m.range)
            }
        }
        return f
    }

    private fun resolveDayMonth(day: Int?, month: Int, year: Int?, today: LocalDate): LocalDate? {
        if (day == null || month !in 1..12) return null
        return try {
            var d = LocalDate.of(year ?: today.year, month, day)
            if (year == null && d.isBefore(today)) d = d.plusYears(1)
            d
        } catch (e: Exception) { null }
    }

    private fun resolveDayOnly(day: Int?, today: LocalDate): LocalDate? {
        if (day == null || day !in 1..31) return null
        var base = today.withDayOfMonth(1)
        for (k in 0..2) {
            val ym = base.plusMonths(k.toLong())
            if (day <= ym.lengthOfMonth()) {
                val d = ym.withDayOfMonth(day)
                if (!d.isBefore(today)) return d
            }
        }
        return null
    }

    private fun toWhen(f: Found, now: LocalDateTime): When? {
        f.relative?.let { return When(it.toLocalDate(), it.toLocalTime(), f.duration ?: 30) }
        val time = f.time ?: f.periodTime
        val today = now.toLocalDate()
        var date = f.date
        if (date == null && f.weekday != null) {
            val wd = f.weekday!!
            date = if (f.weekdayNext) {
                today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays((wd.value - 1).toLong())
            } else {
                val ahead = ((wd.value - today.dayOfWeek.value) + 7) % 7
                when {
                    ahead > 0 -> today.plusDays(ahead.toLong())
                    !f.weekdayStrict && time != null && time > now.toLocalTime() -> today
                    else -> today.plusDays(7)
                }
            }
        }
        if (date == null && time == null) return null
        if (date == null) date = if (time!! > now.toLocalTime()) today else today.plusDays(1)
        val dur = f.duration ?: f.end?.let { e ->
            var m = ChronoUnit.MINUTES.between(time, e).toInt()
            if (m <= 0) m += 24 * 60
            m
        } ?: 60
        return When(date, time, dur)
    }

    private fun title(t: Toks): String {
        val kept = t.orig.indices.filter { !t.used[it] }.toMutableList()
        while (kept.isNotEmpty() && t.norm[kept.first()] in CONNECTORS) kept.removeAt(0)
        while (kept.isNotEmpty() && t.norm[kept.last()] in CONNECTORS) kept.removeAt(kept.size - 1)
        val words = kept.map { t.orig[it].trim(',', '.', ';', ':', '!', '?', '"', '“', '”', '\'') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val s = words.joinToString(" ")
        return s.substring(0, 1).uppercase() + s.substring(1)
    }

    // ------------------------------------------------------------------ API pública

    /** Só data/hora (resposta a "Para quando?"). */
    fun parseWhen(text: String, now: LocalDateTime): When? {
        val t = Toks(text)
        val f = extract(t, now)
        return if (f.any) toWhen(f, now) else null
    }

    /** null = não é um pedido de agenda (segue para o Home Assistant). */
    fun parse(text: String, now: LocalDateTime): Command? {
        val t = Toks(text)
        val n = t.joined
        if (n.isEmpty()) return null
        val today = now.toLocalDate()

        if (RE_UNDO.containsMatchIn(n)) return UndoLast

        val deleting = RE_DELETE_ALWAYS.containsMatchIn(n) || (RE_DELETE_IF.containsMatchIn(n) && RE_AGENDA_WORD.containsMatchIn(n))
        if (deleting) {
            (RE_DELETE_ALWAYS.find(n) ?: RE_DELETE_IF.find(n))?.let { t.mark(it.range) }
            t.find(RE_AGENDA_PLACE)?.let { t.mark(it.range) }
            t.find(RE_ITEM_WORD)?.let { t.mark(it.range) }
            val f = extract(t, now)
            val w = if (f.any) toWhen(f, now) else null
            val q = title(t).lowercase()
            return if (q.isEmpty()) UndoLast else Delete(q, if (f.date != null || f.weekday != null) w?.date else null)
        }

        if (RE_QUERIES.any { it.containsMatchIn(n) }) {
            val next = Regex("\\bproxim[oa]s? (?:compromisso|evento|lembrete)").containsMatchIn(n)
            if (next) return Query(today, 30, true)
            return when {
                Regex("\\bdepois de amanha\\b").containsMatchIn(n) -> Query(today.plusDays(2), 1, false)
                Regex("\\bamanha\\b").containsMatchIn(n) -> Query(today.plusDays(1), 1, false)
                Regex("\\bhoje\\b").containsMatchIn(n) -> Query(today, 1, false)
                Regex("\\b(?:semana que vem|proxima semana)\\b").containsMatchIn(n) ->
                    Query(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), 7, false)
                Regex("\\bsemana\\b").containsMatchIn(n) -> Query(today, 7, false)
                else -> {
                    val f = extract(Toks(text), now)
                    val w = if (f.weekday != null || f.date != null) toWhen(f, now) else null
                    if (w != null) Query(w.date, 1, false) else Query(today, 2, false)
                }
            }
        }

        var reminderOnlyWithWhen = false
        val trigger = RE_CREATE_DIRECT.find(n)
            ?: RE_CREATE_ADD.find(n)?.takeIf { RE_AGENDA_WORD.containsMatchIn(n) }
            ?: RE_REMIND.find(n)?.also { reminderOnlyWithWhen = it.groupValues[1].startsWith("avis") }
            ?: return null
        t.mark(trigger.range)
        t.find(RE_AGENDA_PLACE)?.let { t.mark(it.range) }
        RE_ITEM_WORD.find(n)?.let { m ->
            // "um compromisso", "um lembrete de": só perto do verbo, para não comer o título
            if (t.free(m.range) && t.tokensIn(m.range).minOrNull()?.let { it <= 3 } == true) t.mark(m.range)
        }
        val f = extract(t, now)
        val name = title(t)
        val w = if (f.any) toWhen(f, now) else null
        if (w == null) {
            if (reminderOnlyWithWhen) return null   // "me avisa que a máquina terminou" não é agenda
            return NeedWhen(name.ifEmpty { "Compromisso" })
        }
        return Create(name.ifEmpty { "Compromisso" }, w)
    }
}
