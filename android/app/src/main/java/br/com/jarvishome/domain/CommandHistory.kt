package br.com.jarvishome.domain

import java.time.LocalTime

/** Os últimos comandos: o que foi ouvido, o que foi enviado à casa e a resposta. Para diagnóstico. */
object CommandHistory {

    class Entry(
        val at: LocalTime,
        val heard: String,
        val sent: String,
        val retried: String?,
        val answer: String,
        val result: String,
        val local: String? = null
    )

    private val items = ArrayDeque<Entry>()

    @Synchronized fun add(e: Entry) {
        items.addFirst(e)
        while (items.size > 30) items.removeLast()
    }

    @Synchronized fun all(): List<Entry> = items.toList()

    fun describe(e: Entry): String = buildString {
        append("%02d:%02d:%02d".format(e.at.hour, e.at.minute, e.at.second)).append('\n')
        append("  ouviu:    ").append(e.heard.ifEmpty { "(nada)" }).append('\n')
        if (e.sent.isNotEmpty() && e.sent != e.heard) append("  enviou:   ").append(e.sent).append('\n')
        if (e.retried != null) append("  2ª tentativa: ").append(e.retried).append('\n')
        if (e.local != null) append("  pelo app: ").append(e.local).append('\n')
        append("  resultado: ").append(e.result)
        if (e.answer.isNotEmpty()) append(" · “").append(e.answer).append('”')
    }

    fun dump(): String = all().joinToString("\n\n") { describe(it) }
}
