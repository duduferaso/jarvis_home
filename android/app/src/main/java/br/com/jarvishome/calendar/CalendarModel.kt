package br.com.jarvishome.calendar

import java.time.LocalDate
import java.time.LocalDateTime

/** Uma agenda (entidade calendar.* do Home Assistant). */
data class CalendarInfo(
    val entityId: String,
    val name: String,
    val canCreate: Boolean,
    val canDelete: Boolean
)

/** Um compromisso. Dia inteiro: [start] à meia-noite e [end] exclusivo (dia seguinte). */
data class CalEvent(
    val calendar: String,
    val uid: String,
    val recurrenceId: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean
) {
    val date: LocalDate get() = start.toLocalDate()
}

/** O que a agenda precisa do Home Assistant (implementado por HomeApi; falso nos testes). */
interface CalendarApi {
    fun calendars(): List<CalendarInfo>
    fun events(calendars: List<String>, from: LocalDateTime, to: LocalDateTime): List<CalEvent>
    /** Com hora: [start]/[end]. Dia inteiro: [date] e [endDate] (exclusivo). */
    fun create(calendar: String, title: String, start: LocalDateTime?, end: LocalDateTime?, date: LocalDate?, endDate: LocalDate?)
    fun delete(calendar: String, uid: String, recurrenceId: String)
}
