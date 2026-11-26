package br.com.jarvishome.commands

/**
 * Regras da tela principal (lógica pura, testada em tests/jvm_treino/ScreenRulesTest.kt).
 *
 * Treino em andamento ou alarme tocando: a tela não escurece no modo noite e não apaga,
 * mesmo com "Manter tela ligada" desligado. Até a 1.15.11 o treino contava como "casa quieta"
 * (o assistente fica em espera) e a tela ia ao brilho mínimo no meio das séries.
 */
object ScreenRules {
    /** Ao terminar um treino, a tela fica acesa mais um pouco antes do modo noite. */
    const val AFTER_WORKOUT_MS = 60_000L

    fun dim(isNight: Boolean, now: Long, awakeUntil: Long, quiet: Boolean, workoutActive: Boolean, ringing: Boolean): Boolean =
        isNight && now > awakeUntil && quiet && !workoutActive && !ringing

    fun keepOn(keepScreenOnPref: Boolean, workoutActive: Boolean): Boolean = keepScreenOnPref || workoutActive
}
