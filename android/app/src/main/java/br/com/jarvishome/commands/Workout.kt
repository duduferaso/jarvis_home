package br.com.jarvishome.commands

import br.com.jarvishome.network.TextCorrector

/**
 * Treino intervalado: cada exercício é uma rodada de [workSec] de execução + [restSec] de
 * descanso; [count] exercícios formam uma série, repetida até completar [totalMin] minutos
 * (ex.: 25 min, 5 exercícios, 30 s + 30 s = 5 séries). Nomes dos exercícios são opcionais.
 * O estado é calculado pelo relógio (não acumula erro) e sobrevive a pausas.
 * Lógica pura; a tela e os avisos sonoros só leem [state].
 */
object Workout {

    data class Plan(val totalMin: Int, val workSec: Int, val restSec: Int, val exercises: List<String>,
                    val count: Int = 0, val series: Int = 0) {
        val cycle get() = workSec + restSec
        /** Exercícios por série (nomes, se houver; senão a quantidade). 0 = sem séries. */
        val perSeries get() = if (exercises.isNotEmpty()) exercises.size else count
        /** Rodadas (uma por exercício). Séries completas que cabem no tempo; a última termina na execução. */
        val rounds: Int get() {
            val n = perSeries
            if (series > 0 && n > 0) return n * series
            val raw = maxOf(1, (totalMin * 60 + restSec) / maxOf(1, cycle))
            return if (n > 0) maxOf(n, raw / n * n) else raw
        }
        val seriesCount get() = if (perSeries > 0) rounds / perSeries else 0
        val totalSec get() = rounds * cycle - restSec
    }

    enum class Phase { PREP, WORK, REST, DONE }

    class State(
        val phase: Phase,
        val round: Int,            // 1..rounds
        val rounds: Int,
        val exercise: String,      // vazio = sem lista
        val exerciseIndex: Int,    // 1..n
        val exerciseCount: Int,
        val lap: Int,              // série (1, 2, …)
        val laps: Int,             // total de séries
        val next: String,
        val phaseLeftMs: Long,
        val phaseLenMs: Long,
        val totalLeftMs: Long,
        val paused: Boolean
    )

    const val PREP_SEC = 10

    @Volatile var plan: Plan? = null; private set
    @Volatile private var startedAt = 0L
    @Volatile private var pausedAt = 0L
    @Volatile private var pausedTotal = 0L
    @Volatile var onChange: (() -> Unit)? = null
    /** Abre a tela do treino (o serviço define). */
    @Volatile var onOpenScreen: (() -> Unit)? = null

    val active get() = plan != null
    val paused get() = pausedAt != 0L

    fun start(p: Plan, now: Long = System.currentTimeMillis()) {
        plan = p.copy(totalMin = p.totalMin.coerceIn(1, 120), workSec = p.workSec.coerceIn(5, 600),
            restSec = p.restSec.coerceIn(0, 600), exercises = p.exercises.map { it.trim() }.filter { it.isNotEmpty() },
            count = p.count.coerceIn(0, 30), series = p.series.coerceIn(0, 30))
        startedAt = now; pausedAt = 0; pausedTotal = 0
        changed()
    }

    fun pause(now: Long = System.currentTimeMillis()) { if (active && pausedAt == 0L) { pausedAt = now; changed() } }

    fun resume(now: Long = System.currentTimeMillis()) {
        if (pausedAt != 0L) { pausedTotal += now - pausedAt; pausedAt = 0; changed() }
    }

    fun stop() { plan = null; pausedAt = 0; changed() }

    /** Pula para o começo da próxima fase. */
    fun skip(now: Long = System.currentTimeMillis()) {
        val s = state(now) ?: return
        if (s.phase == Phase.DONE) return
        startedAt -= s.phaseLeftMs
        changed()
    }

    private fun changed() { try { onChange?.invoke() } catch (_: Exception) {} }

    private fun elapsed(now: Long): Long = (if (pausedAt != 0L) pausedAt else now) - startedAt - pausedTotal

    fun exerciseAt(p: Plan, round: Int): String =
        if (p.exercises.isEmpty()) "" else p.exercises[(round - 1) % p.exercises.size]

    fun state(now: Long = System.currentTimeMillis()): State? {
        val p = plan ?: return null
        val e = elapsed(now)
        val prepMs = PREP_SEC * 1000L
        val n = p.perSeries
        fun st(phase: Phase, round: Int, left: Long, len: Long): State {
            val nextRound = if (phase == Phase.WORK) round + 1 else round + (if (phase == Phase.PREP) 0 else 1)
            val next = if (phase == Phase.PREP) exerciseAt(p, 1) else if (nextRound <= p.rounds) exerciseAt(p, nextRound) else ""
            val totalLeft = maxOf(0L, prepMs + p.totalSec * 1000L - e)
            return State(phase, round, p.rounds, exerciseAt(p, round), if (n == 0) 0 else (round - 1) % n + 1, n,
                if (n == 0) 1 else (round - 1) / n + 1, p.seriesCount, next, left, len, totalLeft, pausedAt != 0L)
        }
        if (e < prepMs) return st(Phase.PREP, 1, prepMs - e, prepMs)
        val w = e - prepMs
        if (w >= p.totalSec * 1000L) return st(Phase.DONE, p.rounds, 0, 0)
        val cycle = p.cycle * 1000L
        val round = (w / cycle).toInt() + 1
        val within = w % cycle
        return if (within < p.workSec * 1000L) st(Phase.WORK, round, p.workSec * 1000L - within, p.workSec * 1000L)
        else st(Phase.REST, round, cycle - within, p.restSec * 1000L)
    }

    // ------------------------------------------------------------------ voz

    sealed class Cmd {
        data class Start(val minutes: Int?, val exercises: Int? = null, val series: Int? = null) : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        object Stop : Cmd()
        object Skip : Cmd()
        object Status : Cmd()
    }

    private val NUMS = mapOf("dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30, "quarenta" to 40,
        "cinquenta" to 50, "sessenta" to 60, "uma" to 60, "um" to 60)
    private val SMALL = mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "quinze" to 15, "vinte" to 20)
    private fun count(j: String, words: String): Int? {
        val m = Regex("\\b(\\d+|um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|quinze|vinte) ($words)\\b").find(j) ?: return null
        return m.groupValues[1].toIntOrNull() ?: SMALL[m.groupValues[1]]
    }

    fun parse(text: String, active: Boolean = this.active): Cmd? {
        val n = TextCorrector.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }
        if (n.isEmpty()) return null
        val j = n.joinToString(" ")
        val treino = n.any { it.startsWith("trein") || it == "exercicio" || it == "exercicios" || it == "circuito" }
        val numbersForWorkout = count(j, "exercicios?") != null && count(j, "series?|voltas?|ciclos?|circuitos?") != null
        val startVerb = Regex("\\b(comeca|comece|comecar|inicia|inicie|iniciar|bora|vamos|liga|ligar|abre|abrir|quero|faz|fazer|monta|montar)\\b").containsMatchIn(j)
        val sizes = count(j, "exercicios?|series?|voltas?|minutos?") != null ||
            Regex("\\b(uma hora|meia hora)\\b|\\b\\d+ (de|por|x|vezes) \\d+\\b").containsMatchIn(j)
        if ((treino && (startVerb || sizes)) || numbersForWorkout) {
            var min: Int? = null
            val m = Regex("\\b(\\d+) ?(min|minutos?)\\b").find(j)
            if (m != null) min = m.groupValues[1].toInt()
            else Regex("\\b(dez|quinze|vinte|trinta|quarenta|cinquenta|sessenta) minutos\\b").find(j)?.let { min = NUMS[it.groupValues[1]] }
            if (min == null && Regex("\\b(uma|1) hora\\b").containsMatchIn(j)) min = 60
            if (min == null && Regex("\\bmeia hora\\b").containsMatchIn(j)) min = 30
            var ex = count(j, "exercicios?")
            var se = count(j, "series?|voltas?|ciclos?|circuitos?")
            // "5 de 5", "5 por 5", "5 x 5": exercícios × séries
            if (ex == null && se == null) Regex("\\b(\\d+) (de|por|x|vezes) (\\d+)\\b").find(j)?.let {
                ex = it.groupValues[1].toInt(); se = it.groupValues[3].toInt()
            }
            return Cmd.Start(min?.coerceIn(1, 120), ex?.coerceIn(1, 30), se?.coerceIn(1, 30))
        }
        if (!active) return null
        return when {
            Regex("\\b(para|pare|parar|encerra|encerrar|termina|terminar|cancela|cancelar|finaliza|finalizar)\\b").containsMatchIn(j) &&
                (treino || n.size <= 2) -> Cmd.Stop
            Regex("\\b(pausa|pausar|pause|espera|segura)\\b").containsMatchIn(j) -> Cmd.Pause
            Regex("\\b(continua|continuar|continue|retoma|retomar|volta|voltar|segue|seguir|despausa)\\b").containsMatchIn(j) -> Cmd.Resume
            Regex("\\b(pula|pular|pule|proximo|avanca|avancar)\\b").containsMatchIn(j) -> Cmd.Skip
            Regex("\\b(quanto|qual|que|onde|em que)\\b").containsMatchIn(j) &&
                Regex("\\b(falta|faltam|rodada|exercicio|treino|estou|to|tou)\\b").containsMatchIn(j) -> Cmd.Status
            else -> null
        }
    }

    fun mmss(ms: Long): String {
        val s = (ms + 999) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun minutesPhrase(ms: Long): String {
        val s = (ms + 999) / 1000
        val m = s / 60
        return when {
            m >= 2 -> "$m minutos"
            m == 1L -> "1 minuto" + if (s % 60 >= 10) " e ${s % 60} segundos" else ""
            else -> "$s segundos"
        }
    }

    fun statusPhrase(s: State): String {
        val where = if (s.exerciseCount > 0) "Exercício ${s.exerciseIndex} de ${s.exerciseCount}, série ${s.lap} de ${s.laps}" +
                (if (s.exercise.isNotEmpty()) ", ${s.exercise}" else "")
            else "Rodada ${s.round} de ${s.rounds}"
        return when (s.phase) {
            Phase.PREP -> "Começando em ${(s.phaseLeftMs + 999) / 1000} segundos."
            Phase.DONE -> "O treino terminou."
            else -> "$where. Faltam ${minutesPhrase(s.totalLeftMs)} de treino." + if (s.paused) " Está pausado." else ""
        }
    }

    class Reply(val speech: String, val openScreen: Boolean = false)

    /** Comandos de voz do treino. [defaults] é o plano salvo nas configurações. */
    fun handle(text: String, defaults: Plan, now: Long = System.currentTimeMillis()): Reply? {
        val cmd = parse(text) ?: return null
        return when (cmd) {
            is Cmd.Start -> {
                var p = defaults
                if (cmd.exercises != null) p = p.copy(exercises = emptyList(), count = cmd.exercises)
                if (cmd.minutes != null) p = p.copy(totalMin = cmd.minutes, series = 0)
                else if (cmd.series != null) p = p.copy(series = cmd.series)
                start(p, now)
                val q = plan!!
                val min = (q.totalSec + 59) / 60
                val parts = if (q.perSeries > 0) "${q.perSeries} exercícios, ${q.seriesCount} " + (if (q.seriesCount == 1) "série" else "séries")
                    else "${q.rounds} rodadas"
                Reply("Treino de $min minutos: $parts, ${q.workSec} segundos de execução e ${q.restSec} de descanso. " +
                    "Começa em $PREP_SEC segundos.", openScreen = true)
            }
            Cmd.Pause -> { pause(now); Reply("Treino pausado.") }
            Cmd.Resume -> { resume(now); Reply("Continuando.") }
            Cmd.Stop -> {
                val s = state(now)
                stop()
                Reply(if (s != null && s.phase != Phase.PREP) "Treino encerrado na rodada ${s.round} de ${s.rounds}." else "Treino cancelado.")
            }
            Cmd.Skip -> { skip(now); Reply("Pulando.") }
            Cmd.Status -> Reply(state(now)?.let { statusPhrase(it) } ?: "Nenhum treino em andamento.")
        }
    }
}
