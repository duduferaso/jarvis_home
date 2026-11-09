import br.com.jarvishome.calendar.*
import br.com.jarvishome.calendar.CalendarParser.Create
import br.com.jarvishome.calendar.CalendarParser.Delete
import br.com.jarvishome.calendar.CalendarParser.NeedWhen
import br.com.jarvishome.calendar.CalendarParser.Query
import br.com.jarvishome.calendar.CalendarParser.UndoLast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

var falhas = 0
// quinta-feira, 5 de novembro de 2026, 13:20
val NOW: LocalDateTime = LocalDateTime.of(2026, 11, 5, 13, 20)
fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

fun c(text: String, title: String, date: LocalDate, time: String?, dur: Int = 60) {
    val r = CalendarParser.parse(text, NOW)
    val exp = Create(title, CalendarParser.When(date, time?.let { LocalTime.parse(it) }, dur))
    val ok = r == exp
    if (!ok) falhas++
    println((if (ok) "PASSOU " else "FALHOU ") + "\"$text\" -> $r" + if (ok) "" else "\n        esperado $exp")
}
fun x(text: String, exp: CalendarParser.Command?) {
    val r = CalendarParser.parse(text, NOW)
    val ok = r == exp
    if (!ok) falhas++
    println((if (ok) "PASSOU " else "FALHOU ") + "\"$text\" -> $r" + if (ok) "" else "   (esperado $exp)")
}

class FakeApi : CalendarApi {
    val cals = mutableListOf(CalendarInfo("calendar.casa", "Casa", true, true), CalendarInfo("calendar.feriados", "Feriados", false, false))
    val events = mutableListOf<CalEvent>()
    var n = 0
    override fun calendars() = cals.toList()
    override fun events(calendars: List<String>, from: LocalDateTime, to: LocalDateTime) =
        events.filter { it.calendar in calendars && it.end.isAfter(from) && it.start.isBefore(to) }
    override fun create(calendar: String, title: String, start: LocalDateTime?, end: LocalDateTime?, date: LocalDate?, endDate: LocalDate?) {
        events.add(CalEvent(calendar, "uid${++n}", "", title, start ?: date!!.atStartOfDay(), end ?: endDate!!.atStartOfDay(), start == null))
    }
    override fun delete(calendar: String, uid: String, recurrenceId: String) { events.removeIf { it.uid == uid } }
}

fun s(a: CalendarAssistant, text: String, expected: String?, now: LocalDateTime = NOW) {
    val r = a.handle(text, now)
    val ok = r?.speech == expected
    if (!ok) falhas++
    println((if (ok) "PASSOU " else "FALHOU ") + "\"$text\" -> \"${r?.speech}\"" + if (ok) "" else "\n        esperado \"$expected\"")
}

fun main() {
    println("-- marcar")
    c("marca dentista amanhã às 15h", "Dentista", d(11, 6), "15:00")
    c("Marca dentista amanhã, às 15h.", "Dentista", d(11, 6), "15:00")
    c("marca uma consulta com o cardiologista sexta às 9 e meia", "Consulta com o cardiologista", d(11, 6), "09:30")
    c("me lembra de ligar para o banco segunda às 10", "Ligar para o banco", d(11, 9), "10:00")
    c("coloca na agenda aniversário da Ana dia 20", "Aniversário da Ana", d(11, 20), null)
    c("agenda reunião com o João dia 12 de dezembro às 14:30", "Reunião com o João", d(12, 12), "14:30")
    c("marca academia hoje às 7 da noite", "Academia", d(11, 5), "19:00")
    c("marca jantar com a família hoje à noite", "Jantar com a família", d(11, 5), "20:00")
    c("marca futebol às 3 da tarde", "Futebol", d(11, 5), "15:00")
    c("marca corrida às 6 da manhã", "Corrida", d(11, 6), "06:00")
    c("agendar vacina do cachorro dia 3", "Vacina do cachorro", d(12, 3), null)
    c("marca reunião das 14 às 16 amanhã", "Reunião", d(11, 6), "14:00", 120)
    c("cria um lembrete pagar a conta de luz dia 10 às 9h", "Pagar a conta de luz", d(11, 10), "09:00")
    c("marca almoço com o Pedro amanhã ao meio-dia", "Almoço com o Pedro", d(11, 6), "12:00")
    c("marca consulta dia 15/11 às 16h30", "Consulta", d(11, 15), "16:30")
    c("marca reunião sexta que vem às 10", "Reunião", d(11, 13), "10:00")
    c("marca cinema no sábado às oito da noite", "Cinema", d(11, 7), "20:00")
    c("agenda pilates quarta-feira às dezoito horas", "Pilates", d(11, 11), "18:00")
    c("me lembra de tirar o lixo amanhã de manhã", "Tirar o lixo", d(11, 6), "09:00")
    c("Marca às 15h reunião.", "Reunião", d(11, 5), "15:00")
    c("me lembra de tomar o remédio daqui a 2 horas", "Tomar o remédio", d(11, 5), "15:20", 30)
    c("marca levar as duas meninas na escola amanhã às 7", "Levar as duas meninas na escola", d(11, 6), "07:00")
    c("marca consulta dia 31", "Consulta", d(12, 31), null)
    c("marca reunião às 15h por duas horas", "Reunião", d(11, 5), "15:00", 120)
    c("adiciona na agenda churrasco no domingo ao meio-dia", "Churrasco", d(11, 8), "12:00")
    c("marca dentista dia quinze de dezembro às dez e meia", "Dentista", d(12, 15), "10:30")
    c("marca reunião hoje às 13h", "Reunião", d(11, 5), "13:00")   // "hoje" explícito vale, mesmo já passado
    c("marca reunião às 13h", "Reunião", d(11, 6), "13:00")          // sem dia: 13h já passou → amanhã
    println("-- pedir para quando")
    x("marca dentista", NeedWhen("Dentista"))
    x("me lembra de comprar pão", NeedWhen("Comprar pão"))
    println("-- consultar")
    x("o que eu tenho hoje", Query(d(11, 5), 1, false))
    x("O que temos amanhã?", Query(d(11, 6), 1, false))
    x("qual é o meu próximo compromisso", Query(d(11, 5), 30, true))
    x("como está a agenda da semana", Query(d(11, 5), 7, false))
    x("tenho algum compromisso amanhã", Query(d(11, 6), 1, false))
    x("minha agenda de hoje", Query(d(11, 5), 1, false))
    x("o que tenho na agenda", Query(d(11, 5), 2, false))
    println("-- desmarcar")
    x("desmarca o último compromisso", UndoLast)
    x("desmarca o dentista de amanhã", Delete("dentista", d(11, 6)))
    x("cancela o compromisso do dentista", Delete("dentista", null))
    println("-- não é agenda (vai para o Home Assistant)")
    x("adiciona leite na lista de compras", null)
    x("liga a luz do quarto", null)
    x("desfazer último lançamento", null)
    x("me avisa que a máquina terminou", null)
    x("lançar 50 reais de mercado no financeiro", null)
    x("qual a temperatura do servidor", null)
    x("você lembra da senha do wifi", null)
    println("-- respostas faladas")
    val api = FakeApi()
    CalendarAssistant.clearState()
    val a = CalendarAssistant(api, "")
    s(a, "o que eu tenho amanhã", "Amanhã a agenda está livre.")
    s(a, "marca dentista amanhã às 15h", "Marquei dentista amanhã às 15 horas.")
    s(a, "coloca na agenda aniversário da Ana dia 20", "Marquei aniversário da Ana no dia 20 de novembro, o dia todo.")
    s(a, "marca reunião das 14 às 16 amanhã", "Marquei reunião amanhã das 14 às 16 horas.")
    s(a, "marca cinema no sábado às oito da noite", "Marquei cinema depois de amanhã às 20 horas.")
    s(a, "o que eu tenho amanhã", "Amanhã você tem 2 compromissos: reunião às 14 horas e dentista às 15 horas.")
    s(a, "qual é o meu próximo compromisso", "O próximo compromisso é reunião, amanhã às 14 horas.")
    s(a, "desmarca o último compromisso", "Desmarquei cinema.")
    s(a, "desmarca o dentista de amanhã", "Desmarquei dentista de amanhã às 15 horas.")
    s(a, "marca academia", "Certo, academia. Para quando?")
    s(a, "amanhã às 7 da noite", "Marquei academia amanhã às 19 horas.")
    s(a, "liga a luz do quarto", null)
    ok(api.events.any { it.title == "Academia" && it.start == LocalDateTime.of(2026, 11, 6, 19, 0) }, "evento Academia criado após o \"Para quando?\"")
    ok(api.events.none { it.title == "Cinema" || it.title == "Dentista" }, "desmarcados de fato")
    val noCal = FakeApi().apply { cals.removeAt(0) }
    s(CalendarAssistant(noCal, ""), "marca dentista amanhã às 15h",
        "Não encontrei uma agenda que aceite compromissos. Adicione o Calendário local no Home Assistant.")
    println(if (falhas == 0) "TODOS OS TESTES PASSARAM" else "$falhas FALHAS")
}
fun ok(c: Boolean, m: String) { println((if (c) "PASSOU " else "FALHOU ") + m); if (!c) falhas++ }
