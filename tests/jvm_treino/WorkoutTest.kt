import br.com.jarvishome.commands.Workout
import br.com.jarvishome.commands.Workout.Phase

var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }
fun main() {
    val ex = listOf("Agachamento", "Flexão", "Prancha")
    val p = Workout.Plan(30, 30, 30, ex)
    ok(p.rounds == 30 && p.totalSec == 1770, "30 min com 30/30 = 30 rodadas (${p.rounds}), termina na última execução (${p.totalSec}s)")
    ok(Workout.Plan(60, 30, 30, ex).rounds == 60, "1 hora = 60 rodadas")
    ok(Workout.Plan(20, 40, 20, ex).rounds == 20, "20 min com 40/20 = 20 rodadas")
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
    ok(r == "Rodada 8 de 30, Flexão. Faltam 22 minutos de treino.", "status: $r")
    Workout.stop()
    val st = Workout.handle("começa o treino de 20 minutos", p, t0)!!
    ok(st.openScreen && Workout.plan!!.totalMin == 20, "começar pela voz abre a tela: ${st.speech}")
    println(if (fl == 0) "TODOS PASSARAM" else "$fl FALHARAM"); if (fl > 0) System.exit(1)
}
