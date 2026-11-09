import br.com.jarvishome.commands.Workout
import br.com.jarvishome.commands.Workout.Phase

var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
fun main() {
    val ex = listOf("Agachamento", "Flexão", "Prancha")
    val p = Workout.Plan(30, 30, 30, ex)
    ok(p.rounds == 30 && p.totalSec == 1770, "30 min com 30/30 = 30 rodadas (${p.rounds}), termina na última execução (${p.totalSec}s)")
    ok(Workout.Plan(60, 30, 30, ex).rounds == 60, "1 hora = 60 rodadas")
    ok(Workout.Plan(20, 40, 20, ex).rounds == 18, "20 min com 40/20 e 3 exercícios = 6 séries completas (18 rodadas)")
    val t0 = 1_000_000L
    Workout.start(p, t0)
    fun s(sec: Double) = Workout.state(t0 + (sec * 1000).toLong())!!
    ok(s(0.0).phase == Phase.PREP && s(0.0).next == "Agachamento", "prepare-se 10 s, próximo: Agachamento")
    ok(s(10.0).let { it.phase == Phase.WORK && it.round == 1 && it.exercise == "Agachamento" && it.next == "Flexão" }, "0:10 execução, rodada 1, Agachamento, próximo Flexão")
    ok(s(39.5).let { it.phase == Phase.WORK && it.phaseLeftMs == 500L }, "0:39,5 ainda executa (meio segundo)")
    ok(s(40.0).let { it.phase == Phase.REST && it.round == 1 && it.next == "Flexão" }, "0:40 descanso, próximo Flexão")
    ok(s(70.0).let { it.phase == Phase.WORK && it.round == 2 && it.exercise == "Flexão" && it.exerciseIndex == 2 }, "1:10 rodada 2, Flexão (2 de 3)")
    ok(s(10.0 + 3 * 60).let { it.round == 4 && it.exercise == "Agachamento" && it.lap == 2 }, "rodada 4 volta ao Agachamento (volta 2)")
    ok(s(10.0 + 29 * 60 + 29).let { it.phase == Phase.WORK && it.round == 30 && it.next == "" }, "última rodada, sem próximo")
    ok(s(10.0 + 1770).phase == Phase.DONE, "acaba aos 29:30 de treino")
    // pausa
    Workout.start(p, t0)
    Workout.pause(t0 + 20_000)
    ok(Workout.state(t0 + 80_000)!!.let { it.paused && it.round == 1 && it.phaseLeftMs == 20_000L }, "pausado não anda")
    Workout.resume(t0 + 80_000)
    ok(Workout.state(t0 + 85_000)!!.phaseLeftMs == 15_000L, "continua de onde parou")
    Workout.skip(t0 + 85_000)
    ok(Workout.state(t0 + 85_000)!!.phase == Phase.REST, "pular vai para o descanso")
    println("-- exercícios e séries")
    val q = Workout.Plan(30, 30, 30, emptyList(), count = 5, series = 5)
    ok(q.rounds == 25 && q.seriesCount == 5 && q.totalSec == 1470, "5 exercícios × 5 séries = 25 rodadas, 24:30 (${q.rounds}, ${q.totalSec}s)")
    ok(Workout.Plan(30, 30, 30, emptyList(), count = 6).let { it.rounds == 30 && it.seriesCount == 5 }, "30 min com 6 exercícios = 5 séries")
    ok(Workout.Plan(20, 30, 30, emptyList(), count = 3).let { it.rounds == 18 && it.seriesCount == 6 }, "20 min com 3 exercícios = 6 séries completas (18 min)")
    Workout.start(q, t0)
    fun u(sec: Double) = Workout.state(t0 + (sec * 1000).toLong())!!
    ok(u(10.0).let { it.exerciseIndex == 1 && it.exerciseCount == 5 && it.lap == 1 && it.laps == 5 && it.exercise == "" }, "começa: exercício 1 de 5, série 1 de 5")
    ok(u(10.0 + 4 * 60 + 35).let { it.phase == Phase.REST && it.exerciseIndex == 5 && it.lap == 1 }, "fim da série 1 (descanso do exercício 5)")
    ok(u(10.0 + 5 * 60).let { it.phase == Phase.WORK && it.exerciseIndex == 1 && it.lap == 2 }, "série 2 começa no exercício 1")
    ok(u(10.0 + 13 * 60 + 5).let { it.exerciseIndex == 4 && it.lap == 3 }, "13:05 → exercício 4 de 5, série 3")
    ok(Workout.statusPhrase(u(10.0 + 13 * 60 + 5)) == "Exercício 4 de 5, série 3 de 5. Faltam 11 minutos de treino.", "status: ${Workout.statusPhrase(u(10.0 + 13 * 60 + 5))}")
    ok(u(10.0 + 1470).phase == Phase.DONE, "acaba aos 24:30")
    Workout.stop()

    println("-- voz")
    Workout.stop()
    ok(Workout.parse("começa o treino de 30 minutos") == Workout.Cmd.Start(30), "começa o treino de 30 minutos")
    ok(Workout.parse("inicia o treino") == Workout.Cmd.Start(null), "inicia o treino (tempo salvo)")
    ok(Workout.parse("bora treinar uma hora") == Workout.Cmd.Start(60), "bora treinar uma hora")
    ok(Workout.parse("começa o treino de vinte minutos") == Workout.Cmd.Start(20), "vinte minutos")
    ok(Workout.parse("pausa") == null, "sem treino, \"pausa\" segue adiante")
    ok(Workout.parse("liga a luz do quarto") == null, "luz não é treino")
    Workout.start(p, t0)
    ok(Workout.parse("pausa") == Workout.Cmd.Pause, "pausa")
    ok(Workout.parse("continua") == Workout.Cmd.Resume, "continua")
    ok(Workout.parse("para o treino") == Workout.Cmd.Stop, "para o treino")
    ok(Workout.parse("próximo exercício") == Workout.Cmd.Skip, "próximo exercício")
    ok(Workout.parse("em que exercício eu estou?") == Workout.Cmd.Status, "em que exercício eu estou")
    ok(Workout.parse("quanto falta?") == Workout.Cmd.Status, "quanto falta")
    ok(Workout.parse("liga a luz da sala") == null, "luz durante o treino segue adiante")
    val r = Workout.handle("quanto falta", p, t0 + (10 + 7 * 60 + 5) * 1000L)!!.speech
    ok(r == "Exercício 2 de 3, série 3 de 10, Flexão. Faltam 22 minutos de treino.", "status com nomes: $r")
    Workout.stop()
    val saved = Workout.Plan(30, 30, 30, emptyList(), count = 5, series = 5)
    fun start(t: String): String { Workout.stop(); val r2 = Workout.handle(t, saved, t0)?.speech ?: "(nada)"; return r2 }
    ok(start("treino de 5 exercícios e 5 séries") == "Treino de 25 minutos: 5 exercícios, 5 séries, 30 segundos de execução e 30 de descanso. Começa em 10 segundos.", "voz: ${start("treino de 5 exercícios e 5 séries")}")
    ok(start("começa um treino de 4 exercícios e 3 séries").startsWith("Treino de 12 minutos: 4 exercícios, 3 séries"), "4 × 3: ${start("começa um treino de 4 exercícios e 3 séries")}")
    ok(start("treino de 30 minutos com 6 exercícios").startsWith("Treino de 30 minutos: 6 exercícios, 5 séries"), "30 min com 6: ${start("treino de 30 minutos com 6 exercícios")}")
    ok(start("começa o treino").startsWith("Treino de 25 minutos: 5 exercícios, 5 séries"), "sem números usa o salvo")
    ok(start("cinco exercícios e cinco séries").startsWith("Treino de 25 minutos"), "por extenso, sem dizer treino: ${start("cinco exercícios e cinco séries")}")
    ok(start("treino 5 de 5").startsWith("Treino de 25 minutos: 5 exercícios, 5 séries"), "\"treino 5 de 5\": ${start("treino 5 de 5")}")
    Workout.stop()
    val st = Workout.handle("começa o treino de 20 minutos", p, t0)!!
    ok(st.openScreen && Workout.plan!!.totalMin == 20, "começar pela voz abre a tela: ${st.speech}")
    println(if (fl == 0) "TODOS PASSARAM" else "$fl FALHARAM"); if (fl > 0) System.exit(1)
}
