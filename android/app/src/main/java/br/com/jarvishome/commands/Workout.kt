package br.com.jarvishome.commands

import br.com.jarvishome.network.TextCorrector

/**
 * Treino intervalado: rodadas de [workSec] de execução + [restSec] de descanso até completar
 * [totalMin] minutos, com os exercícios em sequência (repetem quando acaba a lista).
 * O estado é calculado pelo relógio (não acumula erro) e sobrevive a pausas.
 * Lógica pura; a tela e os avisos sonoros só leem [state].
 */
object Workout {

    data class Plan(val totalMin: Int, val workSec: Int, val restSec: Int, val exercises: List<String>) {
        val cycle get() = workSec + restSec
        /** Rodadas que cabem no tempo (a última termina na execução, sem descanso no fim). */
        val rounds get() = maxOf(1, (totalMin * 60 + restSec) / maxOf(1, cycle))
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
        val lap: Int,              // volta na lista de exercícios (1, 2, …)
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
            restSec = p.restSec.coerceIn(0, 600), exercises = p.exercises.map { it.trim() }.filter { it.isNotEmpty() })
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
        val n = p.exercises.size
        fun st(phase: Phase, round: Int, left: Long, len: Long): State {
            val nextRound = if (phase == Phase.WORK) round + 1 else round + (if (phase == Phase.PREP) 0 else 1)
            val next = if (phase == Phase.PREP) exerciseAt(p, 1) else if (nextRound <= p.rounds) exerciseAt(p, nextRound) else ""
            val totalLeft = maxOf(0L, prepMs + p.totalSec * 1000L - e)
            return State(phase, round, p.rounds, exerciseAt(p, round), if (n == 0) 0 else (round - 1) % n + 1, n,
                if (n == 0) 1 else (round - 1) / n + 1, next, left, len, totalLeft, pausedAt != 0L)
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
        data class Start(val minutes: Int?) : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        object Stop : Cmd()
        object Skip : Cmd()
        object Status : Cmd()
    }

    private val NUMS = mapOf("dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30, "quarenta" to 40,
        "cinquenta" to 50, "sessenta" to 60, "uma" to 60, "um" to 60)

    fun parse(text: String, active: Boolean = this.active): Cmd? {
        val n = TextCorrector.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() }
        if (n.isEmpty()) return null
        val j = n.joinToString(" ")
        val treino = n.any { it.startsWith("trein") || it == "exercicio" || it == "exercicios" || it == "circuito" }
        if (Regex("\\b(comeca|comece|comecar|inicia|inicie|iniciar|bora|vamos|liga|ligar|abre|abrir|quero|faz|fazer)\\b").containsMatchIn(j) && treino ||
            Regex("^treino de \\d+").containsMatchIn(j)) {
            var min: Int? = null
            val m = Regex("\\b(\\d+) ?(min|minutos?)\\b").find(j)
            if (m != null) min = m.groupValues[1].toInt()
            else Regex("\\b(dez|quinze|vinte|trinta|quarenta|cinquenta|sessenta) minutos\\b").find(j)?.let { min = NUMS[it.groupValues[1]] }
            if (min == null && Regex("\\b(uma|1) hora\\b").containsMatchIn(j)) min = 60
            if (min == null && Regex("\\bmeia hora\\b").containsMatchIn(j)) min = 30
            return Cmd.Start(min?.coerceIn(1, 120))
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
        val where = "Rodada ${s.round} de ${s.rounds}" + if (s.exercise.isNotEmpty()) ", ${s.exercise}" else ""
        return when (s.phase) {
            Phase.PREP -> "Começando em ${(s.phaseLeftMs + 999) / 1000} segundos. Primeiro: ${s.exercise.ifEmpty { "execução" }}."
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
                val p = if (cmd.minutes != null) defaults.copy(totalMin = cmd.minutes) else defaults
                start(p, now)
                val first = exerciseAt(plan!!, 1)
                Reply("Treino de ${p.totalMin} minutos, ${p.workSec} segundos de execução e ${p.restSec} de descanso. " +
                    "Começa em $PREP_SEC segundos" + (if (first.isNotEmpty()) ", com $first." else "."), openScreen = true)
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
