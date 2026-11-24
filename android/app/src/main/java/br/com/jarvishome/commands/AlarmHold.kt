package br.com.jarvishome.commands

/**
 * Cala o toque do alarme enquanto alguém fala, para o "Hey Tony" chegar limpo ao detector.
 *
 * O bipe sai do alto-falante colado no microfone, bem mais alto que a voz: se cai em cima da chamada,
 * o detector não reconhece. Na bancada (tests/jvm_timers/AlarmHoldTest.kt), com o bipe 20 dB acima da voz,
 * só 15% das chamadas paravam o alarme; com pausa maior entre os bipes e o toque calado ao ouvir fala, 71%.
 *
 * Proteção: depois de cada pausa o toque volta e toca pelo menos [minPlayMs] (uma rodada de bipes)
 * antes de poder calar de novo — TV falando sem parar não deixa o alarme mudo.
 */
class AlarmHold(private val holdMs: Long = 3500, private val minPlayMs: Long = 1200) {
    private var heldUntil = 0L
    private var resumedAt = Long.MIN_VALUE / 2

    /** Começou a tocar um alarme novo. */
    @Synchronized fun reset() { heldUntil = 0L; resumedAt = Long.MIN_VALUE / 2 }

    /** Ouviu fala agora. Devolve true se o toque deve calar (só uma vez por pausa). */
    @Synchronized fun speech(now: Long): Boolean {
        // ainda calado, ou a pausa acabou mas o toque não voltou (canPlay): não emenda outra pausa
        if (heldUntil != 0L) return false
        if (now - resumedAt < minPlayMs) return false
        heldUntil = now + holdMs
        return true
    }

    /** O toque pode tocar agora? (Ao fim da pausa, conta como "voltou a tocar".) */
    @Synchronized fun canPlay(now: Long): Boolean {
        if (heldUntil == 0L) return true
        if (now < heldUntil) return false
        resumedAt = now
        heldUntil = 0L
        return true
    }

    @get:Synchronized val holding: Boolean get() = heldUntil != 0L
}
