package br.com.jarvishome.wakeword

import br.com.jarvishome.settings.Prefs

/**
 * Palavra de ativação. Só existe uma: “Hey Tony”, reconhecida pelo detector pessoal (exemplos de voz
 * da casa). O modelo em inglês (sherpa KWS) foi removido na 1.14.0.
 */
object WakeWords {

    class Profile(val id: String, val display: String)

    val TONY = Profile("tony", PersonalStore.PHRASE)

    fun active(@Suppress("UNUSED_PARAMETER") prefs: Prefs): Profile = TONY

    /** Grafias que a transcrição costuma dar para a chamada; são tiradas do começo da frase. */
    fun names(@Suppress("UNUSED_PARAMETER") prefs: Prefs): List<String> = listOf(
        "Hey Tony", "Tony", "Toni", "Tonie", "Tohny", "Toney", "Tonee", "Tawny", "Toh nee", "Dony", "Tonia"
    )
}
