package br.com.jarvishome.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Frases de data e hora em português, para falar e para mostrar na tela. */
object AgendaFormat {
    private val MONTHS = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
        "setembro", "outubro", "novembro", "dezembro")
    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to "segunda-feira", DayOfWeek.TUESDAY to "terça-feira", DayOfWeek.WEDNESDAY to "quarta-feira",
        DayOfWeek.THURSDAY to "quinta-feira", DayOfWeek.FRIDAY to "sexta-feira", DayOfWeek.SATURDAY to "sábado",
        DayOfWeek.SUNDAY to "domingo"
    )
    private val SHORT = mapOf(
        DayOfWeek.MONDAY to "Seg", DayOfWeek.TUESDAY to "Ter", DayOfWeek.WEDNESDAY to "Qua", DayOfWeek.THURSDAY to "Qui",
        DayOfWeek.FRIDAY to "Sex", DayOfWeek.SATURDAY to "Sáb", DayOfWeek.SUNDAY to "Dom"
    )

    fun weekdayName(d: LocalDate): String = WEEKDAYS.getValue(d.dayOfWeek)

    /** "às 15 horas", "às 9 e 30", "à 1 hora", "ao meio-dia", "à meia-noite". */
    fun timePhrase(t: LocalTime): String {
        if (t.hour == 12 && t.minute == 0) return "ao meio-dia"
        if (t.hour == 0 && t.minute == 0) return "à meia-noite"
        val prep = if (t.hour == 1) "à" else "às"
        return if (t.minute == 0) "$prep ${t.hour} ${if (t.hour == 1) "hora" else "horas"}" else "$prep ${t.hour} e ${t.minute}"
    }

    fun rangePhrase(a: LocalTime, b: LocalTime): String {
        fun p(t: LocalTime) = if (t.minute == 0) "${t.hour}" else "${t.hour} e ${t.minute}"
        val end = if (b.minute == 0) "${b.hour} horas" else p(b)
        return "das ${p(a)} às $end"
    }

    /** "hoje", "amanhã", "depois de amanhã", "na segunda-feira", "no sábado", "no dia 20 de novembro". */
    fun dayPhrase(d: LocalDate, today: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when {
            diff == 0L -> "hoje"
            diff == 1L -> "amanhã"
            diff == 2L -> "depois de amanhã"
            diff in 3..6 -> (if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) "no " else "na ") + weekdayName(d)
            else -> "no dia ${d.dayOfMonth} de ${MONTHS[d.monthValue - 1]}" + (if (d.year != today.year) " de ${d.year}" else "")
        }
    }

    fun dayTitle(d: LocalDate, today: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when (diff) {
            0L -> "Hoje"
            1L -> "Amanhã"
            else -> weekdayName(d).replaceFirstChar { it.uppercase() } + ", " + d.dayOfMonth + " de " + MONTHS[d.monthValue - 1]
        }
    }

    /** Título falado: minúscula no começo, a não ser que pareça nome próprio. */
    fun spoken(title: String): String =
        if (title.length > 1 && title[1].isLowerCase()) title.substring(0, 1).lowercase() + title.substring(1) else title

    /** Para a tela: "Hoje 15:00", "Amanhã · dia todo", "Sáb 20:00", "20/11 09:00". */
    fun screenWhen(e: CalEvent, today: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, e.date)
        val day = when {
            diff == 0L -> "Hoje"
            diff == 1L -> "Amanhã"
            diff in 2..6 -> SHORT.getValue(e.date.dayOfWeek)
            else -> "%02d/%02d".format(e.date.dayOfMonth, e.date.monthValue)
        }
        return if (e.allDay) "$day · dia todo" else "$day %02d:%02d".format(e.start.hour, e.start.minute)
    }

    fun eventPhrase(e: CalEvent): String =
        spoken(e.title) + if (e.allDay) ", o dia todo" else " " + timePhrase(e.start.toLocalTime())
}

/**
 * Executa os pedidos de agenda e monta a resposta falada.
 * [writeCalendar]: agenda escolhida para novos compromissos ("" = a primeira que aceitar).
 */
class CalendarAssistant(private val api: CalendarApi, private val writeCalendar: String) {

    enum class Kind { DONE, INFO, ERROR }

    data class Answer(
        val speech: String,
        val kind: Kind,
        val continueConversation: Boolean = false,
        val changed: Boolean = false
    )

    companion object {
        /** Título aguardando "Para quando?" (vale por 60 s). */
        @Volatile var pendingTitle: String? = null
        @Volatile var pendingUntil: LocalDateTime = LocalDateTime.MIN
        /** Último compromisso marcado por voz (para "desmarca o último"). */
        @Volatile var lastCreated: CalEvent? = null
        /** Avisado quando a agenda muda (o app recarrega a tela). */
        @Volatile var onChange: (() -> Unit)? = null

        fun clearState() {
            pendingTitle = null
            pendingUntil = LocalDateTime.MIN
            lastCreated = null
        }
    }

    /** null = não é pedido de agenda. */
    fun handle(text: String, now: LocalDateTime): Answer? {
        val pending = pendingTitle?.takeIf { now.isBefore(pendingUntil) }
        val cmd = CalendarParser.parse(text, now)
        if (cmd == null && pending != null) {
            val w = CalendarParser.parseWhen(text, now)
            if (w != null) {
                pendingTitle = null
                return create(pending, w, now)
            }
        }
        if (cmd != null) pendingTitle = null
        return when (cmd) {
            null -> null
            is CalendarParser.Create -> create(cmd.title, cmd.whenSpec, now)
            is CalendarParser.NeedWhen -> {
                pendingTitle = cmd.title
                pendingUntil = now.plusSeconds(60)
                Answer("Certo, ${AgendaFormat.spoken(cmd.title)}. Para quando?", Kind.INFO, continueConversation = true)
            }
            is CalendarParser.Query -> query(cmd, now)
            is CalendarParser.UndoLast -> undo(now)
            is CalendarParser.Delete -> delete(cmd, now)
        }
    }

    private fun writable(): CalendarInfo? {
        val cals = api.calendars().filter { it.canCreate }
        return cals.firstOrNull { it.entityId == writeCalendar } ?: cals.firstOrNull()
    }

    private fun create(title: String, w: CalendarParser.When, now: LocalDateTime): Answer {
        val cal = writable()
            ?: return Answer("Não encontrei uma agenda que aceite compromissos. Adicione o Calendário local no Home Assistant.", Kind.ERROR)
        val today = now.toLocalDate()
        val start: LocalDateTime
        val end: LocalDateTime
        if (w.time == null) {
            api.create(cal.entityId, title, null, null, w.date, w.date.plusDays(1))
            start = w.date.atStartOfDay(); end = w.date.plusDays(1).atStartOfDay()
        } else {
            start = w.date.atTime(w.time)
            end = start.plusMinutes(w.durationMin.toLong())
            api.create(cal.entityId, title, start, end, null, null)
        }
        // Descobre o identificador do evento criado, para poder desfazer.
        lastCreated = try {
            api.events(listOf(cal.entityId), start.minusMinutes(1), end.plusMinutes(1))
                .firstOrNull { CalendarParser.norm(it.title) == CalendarParser.norm(title) && it.start == start }
        } catch (e: Exception) { null }
            ?: CalEvent(cal.entityId, "", "", title, start, end, w.time == null)

        val day = AgendaFormat.dayPhrase(w.date, today)
        val speech = when {
            w.time == null -> "Marquei ${AgendaFormat.spoken(title)} $day, o dia todo."
            w.durationMin != 60 && w.durationMin % 30 == 0 && w.durationMin > 60 ->
                "Marquei ${AgendaFormat.spoken(title)} $day ${AgendaFormat.rangePhrase(w.time, end.toLocalTime())}."
            else -> "Marquei ${AgendaFormat.spoken(title)} $day ${AgendaFormat.timePhrase(w.time)}."
        }
        return Answer(speech, Kind.DONE, changed = true)
    }

    private fun upcoming(from: LocalDateTime, to: LocalDateTime): List<CalEvent> {
        val ids = api.calendars().map { it.entityId }
        if (ids.isEmpty()) return emptyList()
        return api.events(ids, from, to).sortedWith(compareBy<CalEvent> { it.start }.thenBy { !it.allDay })
    }

    private fun query(q: CalendarParser.Query, now: LocalDateTime): Answer {
        val today = now.toLocalDate()
        if (api.calendars().isEmpty()) {
            return Answer("Ainda não há nenhuma agenda no Home Assistant. Adicione o Calendário local para eu poder anotar compromissos.", Kind.INFO)
        }
        if (q.nextOnly) {
            val next = upcoming(now, now.plusDays(30)).firstOrNull { it.end.isAfter(now) && (!it.allDay || !it.date.isBefore(today)) }
                ?: return Answer("Não tem nada marcado nos próximos 30 dias.", Kind.INFO)
            val whenTxt = if (next.allDay) AgendaFormat.dayPhrase(next.date, today) + ", o dia todo"
                else AgendaFormat.dayPhrase(next.date, today) + " " + AgendaFormat.timePhrase(next.start.toLocalTime())
            return Answer("O próximo compromisso é ${AgendaFormat.spoken(next.title)}, $whenTxt.", Kind.INFO)
        }
        val from = q.from.atStartOfDay()
        val to = q.from.plusDays(q.days.toLong()).atStartOfDay()
        val events = upcoming(from, to).filter { it.end.isAfter(from) && it.start.isBefore(to) }
        val label = when {
            q.days == 1 -> AgendaFormat.dayPhrase(q.from, today)
            q.days == 7 && q.from == today -> "esta semana"
            q.days == 7 -> "a semana que vem"
            else -> "hoje e amanhã"
        }
        if (events.isEmpty()) {
            return Answer(when (label) {
                "hoje" -> "Você não tem nada na agenda hoje."
                "amanhã" -> "Amanhã a agenda está livre."
                else -> "Nada na agenda para $label."
            }, Kind.INFO)
        }
        val shown = events.take(6)
        val extra = events.size - shown.size
        val speech = if (q.days == 1) {
            val items = joinPt(shown.map { AgendaFormat.eventPhrase(it) })
            val n = events.size
            (label.replaceFirstChar { it.uppercase() }) + (if (n == 1) " você tem " else " você tem $n compromissos: ") + items +
                (if (extra > 0) ", e mais $extra." else ".")
        } else {
            shown.groupBy { it.date }.entries.joinToString(" ") { (d, list) ->
                AgendaFormat.dayPhrase(d, today).replaceFirstChar { it.uppercase() } + ": " + joinPt(list.map { AgendaFormat.eventPhrase(it) }) + "."
            } + if (extra > 0) " E mais $extra." else ""
        }
        return Answer(speech, Kind.INFO)
    }

    private fun undo(now: LocalDateTime): Answer {
        val last = lastCreated ?: return Answer("Não marquei nada agora há pouco para desfazer.", Kind.INFO)
        var target = last
        if (target.uid.isEmpty()) {
            target = api.events(listOf(last.calendar), last.start.minusMinutes(1), last.end.plusMinutes(1))
                .firstOrNull { CalendarParser.norm(it.title) == CalendarParser.norm(last.title) }
                ?: return Answer("Não encontrei ${AgendaFormat.spoken(last.title)} na agenda.", Kind.ERROR)
        }
        api.delete(target.calendar, target.uid, target.recurrenceId)
        lastCreated = null
        return Answer("Desmarquei ${AgendaFormat.spoken(target.title)}.", Kind.DONE, changed = true)
    }

    private fun delete(d: CalendarParser.Delete, now: LocalDateTime): Answer {
        val today = now.toLocalDate()
        val from = d.date?.atStartOfDay() ?: now.minusHours(1)
        val to = d.date?.plusDays(1)?.atStartOfDay() ?: now.plusDays(30)
        val words = CalendarParser.norm(d.title).split(' ').filter { it.length > 2 }
        val match = upcoming(from, to).firstOrNull { e ->
            val t = CalendarParser.norm(e.title)
            words.isNotEmpty() && words.all { t.contains(it) }
        } ?: return Answer("Não encontrei ${d.title} na agenda" +
            (d.date?.let { " " + AgendaFormat.dayPhrase(it, today) } ?: "") + ".", Kind.ERROR)
        val cal = api.calendars().firstOrNull { it.entityId == match.calendar }
        if (cal != null && !cal.canDelete) return Answer("Essa agenda não permite desmarcar pelo Jarvis.", Kind.ERROR)
        api.delete(match.calendar, match.uid, match.recurrenceId)
        if (lastCreated?.uid == match.uid) lastCreated = null
        val whenTxt = AgendaFormat.dayPhrase(match.date, today) +
            (if (match.allDay) "" else " " + AgendaFormat.timePhrase(match.start.toLocalTime()))
        return Answer("Desmarquei ${AgendaFormat.spoken(match.title)} de $whenTxt.".replace("de hoje", "de hoje")
            .replace("de no ", "do ").replace("de na ", "da "), Kind.DONE, changed = true)
    }

    private fun joinPt(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " e " + items.last()
    }
}
