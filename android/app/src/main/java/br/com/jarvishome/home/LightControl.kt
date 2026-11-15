package br.com.jarvishome.home

import br.com.jarvishome.domain.JLog
import br.com.jarvishome.settings.Prefs

/**
 * Único caminho para ligar/desligar luzes a partir da tela (chips e planta).
 * Sempre turn_on/turn_off explícito, nunca "toggle": se a leitura da tela estiver atrasada,
 * o toggle faria o contrário do que aparece. A voz usa os mesmos serviços.
 */
object LightControl {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Mostra o novo estado na hora e manda para o Home Assistant em segundo plano. */
    fun set(prefs: Prefs, ids: List<String>, on: Boolean, label: String, onFail: (String) -> Unit) {
        if (ids.isEmpty()) return
        for (id in ids) HomeModel.setLightOptimistic(id, on)
        val url = prefs.serverUrl
        val token = prefs.token
        Thread {
            try {
                HomeApi(url, token).setPower(ids, on)
            } catch (t: Throwable) {
                JLog.w("Falha ao " + (if (on) "ligar " else "desligar ") + label, t)
                HomeModel.clearOptimistic(ids)
                onFail("Não consegui " + (if (on) "ligar " else "desligar ") + label)
            }
            HomeMonitor.refreshSoon()
            main.postDelayed({ HomeMonitor.refreshSoon() }, 2500) // relé lento: confere de novo
        }.start()
    }
}
