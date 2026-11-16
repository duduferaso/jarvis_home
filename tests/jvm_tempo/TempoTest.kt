import br.com.jarvishome.home.Forecast
import br.com.jarvishome.home.Forecast.Ask
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }

fun main() {
    val today = LocalDate.of(2026, 11, 15) // domingo
    val zone = ZoneId.of("America/Sao_Paulo")
    val resp = JSONObject("""{"changed_states":[],"service_response":{"weather.forecast_casa":{"forecast":[
      {"condition":"sunny","datetime":"2026-11-15T15:00:00+00:00","temperature":31.2,"templow":20.1,"precipitation_probability":5},
      {"condition":"rainy","datetime":"2026-11-16T15:00:00+00:00","temperature":26.0,"templow":19.4,"precipitation_probability":80,"precipitation":12.3},
      {"condition":"partlycloudy","datetime":"2026-11-17T15:00:00+00:00","temperature":28.0,"templow":18.0,"precipitation_probability":35},
      {"condition":"cloudy","datetime":"2026-11-18T15:00:00+00:00","temperature":27.0,"templow":18.0,"precipitation_probability":10},
      {"condition":"lightning-rainy","datetime":"2026-11-19T15:00:00+00:00","temperature":29.0,"templow":21.0,"precipitation_probability":70},
      {"condition":"sunny","datetime":"2026-11-20T15:00:00+00:00","temperature":32.0,"templow":22.0,"precipitation_probability":0},
      {"condition":"sunny","datetime":"2026-11-21T15:00:00+00:00","temperature":33.0,"templow":22.0,"precipitation_probability":10}
    ]}}}""")
    val days = Forecast.parse(resp, "weather.forecast_casa", zone)
    ok(days.size == 7 && days[0].date == today && days[1].condition == "Chuva" && days[1].rainChance == 80, "previsão lida: ${days.size} dias")
    val states = JSONArray("""[{"entity_id":"weather.forecast_casa","state":"partlycloudy","attributes":{"temperature":24.6}}]""")
    ok(Forecast.findEntity(states) == "weather.forecast_casa", "entidade de clima encontrada")
    val now = Forecast.nowFrom(states, "weather.forecast_casa")

    fun q(s: String) = Forecast.parseQuery(s, today)
    // perguntas de tempo
    ok(q("previsão do tempo para a semana")?.ask == Ask.Week, "semana")
    ok(q("qual a previsão do tempo")?.ask == Ask.Week, "previsão sem dia = semana")
    ok((q("vai chover amanhã?")?.ask as? Ask.Days)?.dates == listOf(today.plusDays(1)) && q("vai chover amanhã?")!!.rain, "chover amanhã")
    ok((q("como vai estar o tempo no sábado")?.ask as? Ask.Days)?.dates == listOf(LocalDate.of(2026, 11, 21)), "sábado")
    ok((q("previsão para o fim de semana")?.ask as? Ask.Days)?.dates?.size == 2, "fim de semana")
    ok(q("como está o tempo")?.ask == Ask.Now, "agora")
    ok((q("vai fazer frio amanhã")?.ask as? Ask.Days) != null, "frio amanhã")
    ok(q("vai chover essa semana")?.let { it.ask == Ask.Week && it.rain } == true, "chuva na semana")
    // não é tempo
    for (s in listOf("quanto tempo falta no timer", "liga a luz da sala", "qual a temperatura do quarto", "o que eu tenho amanhã",
            "timer de 5 minutos", "desliga tudo", "quanto tempo falta do treino"))
        ok(q(s) == null, "não é tempo: \"$s\"")

    fun a(s: String) = Forecast.answer(q(s)!!, days, now, today).also { println("   “$s” → “$it”") }
    ok(a("vai chover amanhã?").startsWith("Sim, amanhã tem 80%"), "resposta chuva amanhã")
    ok(a("vai chover hoje").startsWith("Provavelmente não"), "resposta chuva hoje")
    ok(a("como vai estar o tempo na terça").contains("máxima de 28"), "resposta terça")
    ok(a("previsão da semana").startsWith("Previsão: Hoje, ensolarado, 31 e 20"), "resposta semana")
    ok(a("vai chover essa semana").contains("amanhã (80%)") && a("vai chover essa semana").contains("quinta (70%)"), "chuva na semana")
    ok(a("como está o tempo").startsWith("Agora está 25 graus, parcialmente nublado."), "resposta agora")
    ok(Forecast.answer(Forecast.Query(Ask.Days(listOf(today.plusDays(20)), "dia 5"), false), days, now, today).contains("não chega"), "dia fora da previsão")
    val lines = Forecast.lines(days, today)
    println(lines.joinToString("\n") { "   $it" })
    ok(lines.size == 7 && lines[1].startsWith("Amanhã · Chuva · 26° / 19° · chuva 80%"), "linhas da tela")
    println(if (fl == 0) "TODOS OS TESTES PASSARAM" else "$fl FALHA(S)")
}
