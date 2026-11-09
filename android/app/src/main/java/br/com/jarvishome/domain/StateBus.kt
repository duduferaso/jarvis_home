package br.com.jarvishome.domain

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fonte única de verdade do que a interface mostra. O serviço escreve,
 * a Activity e as configurações leem. Mesmo processo, sem IPC.
 */
object StateBus {
    interface Listener { fun onStateChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var state: AssistantState = AssistantState.IDLE; private set
    @Volatile var connection: ConnectionState = ConnectionState.UNKNOWN; private set
    @Volatile var message: String = ""; private set
    @Volatile var transcript: String = ""; private set
    @Volatile var stateSince: Long = System.currentTimeMillis(); private set

    /** Lidos a cada quadro pela animação, sem notificação. */
    @Volatile var mouthLevel: Float = 0f
    @Volatile var micLevel: Float = 0f

    @Volatile var wakeWordStatus: String = "Não iniciado"
    @Volatile var lastWakeWord: String = "—"
    /** Distância da última fala avaliada pelo detector pessoal (diagnóstico). */
    @Volatile var lastScore: Float = Float.NaN
    @Volatile var serviceRunning: Boolean = false

    fun setState(newState: AssistantState, msg: String? = null) {
        if (newState != state) stateSince = System.currentTimeMillis()
        state = newState
        if (msg != null) message = msg
        notifyListeners()
    }

    fun setConnection(c: ConnectionState) {
        if (c == connection) return
        connection = c
        notifyListeners()
    }

    fun setTranscript(text: String) {
        transcript = text
        notifyListeners()
    }

    fun notifyListeners() {
        main.post { for (l in listeners) l.onStateChanged() }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        main.post { l.onStateChanged() }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }
}
