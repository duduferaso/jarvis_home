package br.com.jarvishome.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import br.com.jarvishome.audio.AudioPlayer
import br.com.jarvishome.audio.VoiceActivityDetector
import br.com.jarvishome.domain.AssistantState
import br.com.jarvishome.domain.ConnectionState
import br.com.jarvishome.commands.Timers
import br.com.jarvishome.domain.CommandHistory
import br.com.jarvishome.domain.JLog
import br.com.jarvishome.domain.StateBus
import br.com.jarvishome.home.HomeModel
import br.com.jarvishome.home.HomeMonitor
import br.com.jarvishome.home.Night
import br.com.jarvishome.network.Backend
import br.com.jarvishome.network.HaDirectBackend
import br.com.jarvishome.network.Backends
import br.com.jarvishome.network.Session
import br.com.jarvishome.settings.Prefs
import br.com.jarvishome.settings.Provisioning
import br.com.jarvishome.ui.MainActivity
import br.com.jarvishome.wakeword.WakeWords
import br.com.jarvishome.wakeword.PersonalStore
import br.com.jarvishome.wakeword.PersonalWakeWord
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Coração do terminal. Mantém o microfone aberto em primeiro plano, roda o wake word
 * localmente e só envia áudio ao servidor depois da ativação.
 */
class JarvisForegroundService : Service() {

    private enum class Mode { IDLE, LISTENING, BUSY, SLEEPING }

    @Volatile private var mode = Mode.IDLE
    @Volatile private var running = false
    @Volatile private var manualTrigger = false
    /** Reaberturas seguidas por "só a chamada"/transcrição inventada: no máximo uma (TV não vira laço). */
    @Volatile private var reopenStreak = 0

    // ---------------------------------------------------------------- reforço (aprendizado)
    /** A chamada que abriu a conversa atual (vira exemplo se o comando der certo). */
    @Volatile private var learnCandidate: Array<FloatArray>? = null
    /** Se a ativação veio só de um exemplo aprendido: ele (para esquecer se foi à toa). */
    @Volatile private var activatedByLearned: Array<FloatArray>? = null
    /** Ativações pelo detector que não trouxeram fala nenhuma (prováveis ativações sozinho). */
    private val emptyActivations = ArrayDeque<Long>()
    @Volatile private var lastActivationByDetector = false
    private var strictUntil = 0L

    /** Duas ativações à toa em 10 min: detector 8% mais exigente por 30 min (TV ligada, conversa…). */
    private fun noteEmptyActivation() {
        val now = System.currentTimeMillis()
        emptyActivations.addLast(now)
        while (emptyActivations.isNotEmpty() && now - emptyActivations.first() > 10 * 60_000) emptyActivations.removeFirst()
        if (emptyActivations.size >= 2 && now > strictUntil) {
            strictUntil = now + 30 * 60_000
            personal?.strictness = 0.92f
            JLog.i("Várias ativações sem fala: detector mais exigente por 30 min")
            main.postDelayed({
                if (System.currentTimeMillis() >= strictUntil) { personal?.strictness = 1f; JLog.i("Detector voltou ao normal") }
            }, 30 * 60_000L + 1000)
        }
    }
    private class Learned(val kind: String, val at: Long, val what: String, val undo: () -> Unit)
    private val learnedStack = ArrayDeque<Learned>()

    private fun remember(kind: String, what: String, undo: () -> Unit) {
        synchronized(learnedStack) {
            learnedStack.addLast(Learned(kind, System.currentTimeMillis(), what, undo))
            while (learnedStack.size > 20) learnedStack.removeFirst()
        }
    }

    private fun setupLearning() {
        val L = br.com.jarvishome.commands.Learning
        L.onLearnFix = fix@{ heard, sent ->
            if (!prefs.learnFromSuccess) return@fix
            synchronized(learnedStack) {
                val list = L.decode(prefs.learnedFixes)
                val existed = list.any { L.key(it.heard) == L.key(heard) }
                prefs.learnedFixes = L.encode(L.addFix(list, heard, sent, System.currentTimeMillis()))
                JLog.i("Aprendi a correção: “$heard” → “$sent”")
                if (!existed) remember("correção", "“$heard” vira “$sent”") {
                    prefs.learnedFixes = L.encode(L.decode(prefs.learnedFixes).filter { L.key(it.heard) != L.key(heard) })
                }
            }
        }
        L.onLearnLight = light@{ heard, on, target ->
            if (!prefs.learnFromSuccess) return@light
            synchronized(learnedStack) {
                val list = br.com.jarvishome.commands.LightCommands.decode(prefs.trainedCommands).toMutableList()
                val entry = br.com.jarvishome.commands.LightCommands.Trained(heard.trim().trimEnd('.', '!', '?'), on, target)
                if (list.any { it == entry }) return@light
                list.add(entry)
                prefs.trainedCommands = br.com.jarvishome.commands.LightCommands.encode(list)
                JLog.i("Aprendi o comando de luz: “${entry.heard}” → " + (if (on) "ligar " else "desligar ") + target)
                remember("luz", "“${entry.heard}”") {
                    prefs.trainedCommands = br.com.jarvishome.commands.LightCommands.encode(
                        br.com.jarvishome.commands.LightCommands.decode(prefs.trainedCommands).filter { it != entry })
                }
            }
        }
        L.onUndo = {
            synchronized(learnedStack) {
                val last = learnedStack.lastOrNull()
                if (last == null || System.currentTimeMillis() - last.at > 10 * 60_000) null
                else {
                    learnedStack.removeLast()
                    try { last.undo() } catch (e: Exception) { JLog.w("Desfazer", e) }
                    last.what
                }
            }
        }
    }

    private fun code0(c: String?) = c ?: ""

    /** Ativou à toa por causa de um exemplo aprendido: tira ele. */
    private fun forgetLearned(t: Array<FloatArray>) {
        personal?.forget(t)
        Thread {
            try {
                val l = PersonalStore.loadLearned(this)
                val n = l.size
                l.removeAll { it.size == t.size && it.contentDeepEquals(t) }
                if (l.size < n) PersonalStore.saveLearned(this, l)
                JLog.i("Ativou sem comando por um exemplo aprendido: esqueci esse exemplo (restam ${l.size})")
            } catch (e: Throwable) { JLog.w("Não consegui esquecer o exemplo", e) }
        }.start()
    }

    /** O comando deu certo: a chamada que abriu a conversa vira mais um exemplo de voz. */
    private fun learnWake(frames: Array<FloatArray>) {
        Thread {
            try {
                val current = PersonalStore.loadForDetection(this)
                val d = current.score(frames).distance
                if (d < 0.05f) { JLog.i("Chamada parecida com um exemplo que já existe (" + String.format(Locale.US, "%.3f", d) + "); não guardei"); return@Thread }
                val list = PersonalStore.loadLearned(this)
                list.add(frames)
                PersonalStore.saveLearned(this, list)
                personal?.learn(frames)
                JLog.i("Aprendi esta chamada do “Hey Tony” (" + list.size.coerceAtMost(PersonalStore.MAX_LEARNED) + " de " + PersonalStore.MAX_LEARNED + ")")
                remember("chamada", "a última chamada do “Hey Tony”") {
                    val l = PersonalStore.loadLearned(this)
                    l.removeAll { it.size == frames.size && it.contentDeepEquals(frames) }
                    PersonalStore.saveLearned(this, l)
                    personal?.forget(frames)
                }
            } catch (e: Throwable) { JLog.w("Não consegui aprender a chamada", e) }
        }.start()
    }
    @Volatile private var reloadWake = true
    @Volatile private var resetWake = false
    @Volatile private var cooldownUntil = 0L
    @Volatile private var session: Session? = null
    @Volatile private var wakeOk = false
    @Volatile private var paused = false

    private lateinit var prefs: Prefs
    private lateinit var player: AudioPlayer
    private val main = Handler(Looper.getMainLooper())
    private val beeper = AlarmBeeper()
    private val alarmHold = br.com.jarvishome.commands.AlarmHold()
    /** A ativação atual parou um alarme tocando (silêncio depois dela não é "ativou sozinho"). */
    @Volatile private var stoppedAlarm = false

    // ---------------------------------------------------------------- voz única
    /** Gera a voz do Home Assistant (Piper) em segundo plano, sem atrapalhar as conversas. */
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val voiceCache = java.util.Collections.synchronizedMap(LinkedHashMap<String, File>())

    /** Áudio desta frase na voz do Home Assistant (guardado para repetir sem gerar de novo), ou null. */
    private fun voiceFile(text: String): File? {
        voiceCache[text]?.takeIf { it.exists() }?.let { return it }
        val backend = Backends.create(prefs) as? HaDirectBackend ?: return null
        val url = backend.synthesize(text) ?: return null
        val dir = File(cacheDir, "voz").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(text.hashCode()) + "-" + text.length + ".audio")
        backend.download(url, f)
        voiceCache[text] = f
        if (voiceCache.size > 150) voiceCache.keys.firstOrNull()?.let { voiceCache.remove(it)?.delete() }
        return f
    }

    /**
     * Fala SEMPRE com a voz do Home Assistant. A voz do Android só entra se a casa
     * estiver fora do alcance, e isso fica registrado nos logs.
     */
    private fun speakHa(text: String, onDone: () -> Unit) {
        voiceExecutor.execute {
            val f = try { voiceFile(text) } catch (e: Throwable) { JLog.w("Voz do Home Assistant falhou", e); null }
            main.post {
                if (f != null) player.play(f, Night.volume(prefs), onStart = {}, onDone = { ok ->
                    if (ok) onDone() else { JLog.w("Áudio não tocou; usando a voz do Android"); player.speakLocal(text) { onDone() } }
                })
                else { JLog.w("Sem a voz do Home Assistant; usando a voz do Android: “$text”"); player.speakLocal(text) { onDone() } }
            }
        }
    }

    /** Frase do treino: toca na hora se já foi gerada; senão gera e toca. */
    private fun cue(text: String) {
        val cached = voiceCache[text]?.takeIf { it.exists() }
        if (cached != null) { player.play(cached, Night.volume(prefs), onStart = {}, onDone = {}); return }
        speakHa(text) {}
    }
    private var coach: WorkoutCoach? = null

    /** Timers e alarmes: confere a cada segundo; toca até alguém parar (voz, toque na tela) ou por 3 min. */
    private val timerLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (i in Timers.due(now)) {
                JLog.i("Timer acabou: " + Timers.name(i))
                if (prefs.pausedForOther) HomeModel.setAnnouncement(Timers.finishedPhrase(i)) // em pausa total: só na tela
                else announce(Timers.finishedPhrase(i), requested = true)
            }
            val ringing = Timers.ringing()
            when {
                ringing.isEmpty() -> { beeper.stop(); alarmHold.reset() }
                ringing.all { now - it.ringingSince > 3 * 60_000 } -> {
                    JLog.i("Timer: ninguém parou em 3 minutos, silenciando")
                    Timers.stopRinging(); beeper.stop()
                }
                prefs.pausedForOther -> beeper.stop()
                !beeper.playing && alarmHold.canPlay(now) && (mode == Mode.IDLE || mode == Mode.SLEEPING || now - ringing.minOf { it.ringingSince } > 8000) -> beeper.start()
            }
            main.postDelayed(this, 1000)
        }
    }
    private val vad = VoiceActivityDetector(900)
    private val lock = Any()
    private var audioThread: Thread? = null
    private var personal: PersonalWakeWord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var sessionExecutor: ExecutorService
    private lateinit var healthExecutor: ExecutorService
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    /** Avisos esperando a conversa atual terminar (só na thread principal). */
    private val pendingAnnouncements = java.util.ArrayDeque<Pair<String, Boolean>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        br.com.jarvishome.domain.CrashLog.install(this)
        Provisioning.apply(this)
        prefs = Prefs(this)
        player = AudioPlayer(this) { Night.volume(prefs) }
        sessionExecutor = Executors.newSingleThreadExecutor()
        healthExecutor = Executors.newSingleThreadExecutor()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisHome:listening").apply {
            setReferenceCounted(false)
        }
        if (!prefs.assistantEnabled) mode = Mode.SLEEPING
        StateBus.serviceRunning = true
        JLog.i("Serviço criado")
        main.post(healthLoop)
        HomeMonitor.onAnnouncement = { text -> main.post { announce(text) } }
        HomeMonitor.start(this)
        setupLearning()
        Timers.restore(prefs.timers)
        Timers.onChange = { prefs.timers = Timers.encode() }
        main.post(timerLoop)
        coach = WorkoutCoach(this) { text -> cue(text) }
        br.com.jarvishome.commands.Workout.onChange = {
            main.post { coach?.kick() }
            // gera a voz das frases do treino antes de precisar (mesma voz do Jarvis, sem atraso)
            br.com.jarvishome.commands.Workout.plan?.let { p ->
                voiceExecutor.execute { WorkoutCoach.phrases(p).forEach { try { voiceFile(it) } catch (_: Exception) {} } }
            }
        }
        br.com.jarvishome.commands.Workout.onOpenScreen = {
            main.post {
                try {
                    startActivity(Intent(this, br.com.jarvishome.ui.WorkoutActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (e: Exception) { JLog.w("Não abri a tela do treino", e) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_LISTEN -> requestListen()
            ACTION_SLEEP -> setSleeping(true)
            ACTION_WAKE -> setSleeping(false)
            ACTION_TOGGLE_SLEEP -> setSleeping(mode != Mode.SLEEPING)
            ACTION_PAUSE_TOTAL -> pauseTotal(true)
            ACTION_RESUME_TOTAL -> pauseTotal(false)
            ACTION_RELOAD -> reload()
            ACTION_TEST_VOICE -> {
                player.beep(prefs.beep, Night.beepVolume(prefs))
                speakHa("Teste de áudio. Esta é a voz que vai te responder.") {}
            }
            ACTION_PAUSE_MIC -> {
                // Calibração precisa do microfone só para ela.
                paused = true
                session?.cancel()
                session = null
                player.stop()
                stopAudio()
                JLog.i("Microfone liberado para a calibração")
            }
            ACTION_RESUME_MIC -> {
                paused = false
                reloadWake = true
                JLog.i("Microfone retomado")
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (mode == Mode.SLEEPING) {
            StateBus.setState(AssistantState.SLEEPING, sleepMessage())
        } else if (!running && !paused) {
            startAudio()
            goIdle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        JLog.i("Serviço encerrado")
        HomeMonitor.onAnnouncement = null
        HomeMonitor.stop()
        main.removeCallbacksAndMessages(null)
        session?.cancel()
        stopAudio()
        beeper.stop()
        Timers.onChange = null
        br.com.jarvishome.commands.Workout.onChange = null
        br.com.jarvishome.commands.Workout.onOpenScreen = null
        coach?.release()
        player.release()
        sessionExecutor.shutdownNow()
        voiceExecutor.shutdownNow()
        healthExecutor.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        StateBus.serviceRunning = false
        StateBus.micLevel = 0f
        super.onDestroy()
    }

    // ------------------------------------------------------------------ comandos

    private fun requestListen() {
        when (mode) {
            Mode.SLEEPING -> {
                setSleeping(false)
                manualTrigger = true
            }
            Mode.IDLE -> manualTrigger = true
            Mode.BUSY -> if (StateBus.state == AssistantState.SPEAKING) {
                // Interrupção: toque durante a fala corta a resposta e abre o microfone.
                player.stop()
                mode = Mode.IDLE
                manualTrigger = true
            }
            Mode.LISTENING -> endListening(true)
        }
    }

    private fun sleepMessage() =
        if (prefs.pausedForOther) "Em pausa: o Home Control está usando o microfone. Toque e segure no rosto para voltar."
        else "Toque e segure no rosto para acordar"

    /**
     * Pausa total, para o app novo (Home Control) usar o Redmi: solta o microfone, para de ler os avisos do HA,
     * não toca timers e não inicia sozinho com o celular. Voltar daqui pede ao outro app que pause.
     */
    private fun pauseTotal(on: Boolean) {
        if (on) {
            if (Timers.all().isNotEmpty()) HomeModel.setAnnouncement("Timers deste app ficam em silêncio durante a pausa")
            prefs.pausedForOther = true
            beeper.stop()
            setSleeping(true)
            JLog.i("Pausa total: o Home Control fica com o microfone")
        } else setSleeping(false)
    }

    private fun setSleeping(sleep: Boolean) {
        if (sleep) {
            prefs.assistantEnabled = false
            session?.cancel()
            session = null
            player.stop()
            mode = Mode.SLEEPING
            stopAudio()
            StateBus.setState(AssistantState.SLEEPING, sleepMessage())
            JLog.i("Assistente em repouso (microfone desligado)")
        } else {
            if (prefs.pausedForOther) {
                prefs.pausedForOther = false
                askOtherToPause(this)
                JLog.i("Saiu da pausa total; pedindo ao Home Control que pause")
            }
            prefs.assistantEnabled = true
            if (mode == Mode.SLEEPING) mode = Mode.IDLE
            startAudio()
            goIdle()
            JLog.i("Assistente ativo")
        }
        updateNotification()
    }

    private fun reload() {
        JLog.i("Recarregando configurações")
        vad.silenceMs = prefs.silenceMs
        reloadWake = true
        main.removeCallbacks(healthLoop)
        main.post(healthLoop)
        HomeMonitor.start(this)
        if (mode == Mode.IDLE) goIdle()
    }

    // ------------------------------------------------------------------ áudio

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startAudio() {
        if (running) return
        if (!hasMicPermission()) {
            StateBus.setState(AssistantState.ERROR, "Microfone sem permissão. Abra o app para permitir.")
            JLog.e("Sem permissão de microfone")
            return
        }
        running = true
        wakeLock?.acquire()
        audioThread = Thread({ audioLoop() }, "jarvis-audio").also { it.start() }
    }

    private fun stopAudio() {
        running = false
        audioThread?.let {
            it.interrupt()
            try { it.join(1500) } catch (_: InterruptedException) {}
        }
        audioThread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        StateBus.micLevel = 0f
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var failures = 0
        while (running) {
            var rec: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, SAMPLE_RATE * 2)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("AudioRecord não inicializou")
                rec.startRecording()
                JLog.i("Microfone aberto")
                failures = 0
                if (mode == Mode.IDLE) goIdle()
                captureFrames(rec)
            } catch (e: Throwable) {
                if (!running) break
                failures++
                JLog.e("Falha no microfone (tentativa $failures)", e)
                StateBus.setState(AssistantState.ERROR, "Falha ao abrir o microfone")
                try { Thread.sleep(minOf(30000L, 2000L * failures)) } catch (_: InterruptedException) {}
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
            }
        }
        try { personal?.release() } catch (_: Throwable) {}
        personal = null
        reloadWake = true
        JLog.i("Microfone fechado")
    }

    private fun captureFrames(rec: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        val floats = FloatArray(FRAME_SAMPLES)
        var lastBytes: ByteArray? = null
        var silentFrames = 0
        while (running) {
            if (reloadWake) initWakeWord()
            val n = rec.read(frame, 0, frame.size)
            if (n <= 0) throw IOException("read() retornou $n")
            val rms = VoiceActivityDetector.rms(frame, n)
            StateBus.micLevel = VoiceActivityDetector.level(rms)
            // Android 11+ entrega silêncio absoluto quando o app não pode usar o microfone em segundo plano.
            silentFrames = if (rms == 0.0) silentFrames + 1 else 0
            if (silentFrames == 100) {
                JLog.w("Microfone entregando silêncio absoluto há 10 s (restrição do Android?)")
                StateBus.wakeWordStatus = "Microfone mudo: abra a tela do Jarvis"
            }
            val bytes = toBytes(frame, n)

            when (mode) {
                Mode.IDLE -> {
                    // o bipe do alarme não é o barulho da sala (subia o piso e o comando depois não contava como fala)
                    if (!beeper.playing) vad.learnNoise(rms)
                    if (resetWake) {
                        resetWake = false
                        try { personal?.reset() } catch (_: Exception) {}
                    }
                    var trigger: String? = null
                    val p = personal
                    if (p != null) {
                        for (i in 0 until n) floats[i] = frame[i] / 32768f
                        val hit = p.accept(floats, n)
                        StateBus.lastScore = p.lastDistance
                        if (hit == null && beeper.playing && p.speaking() && alarmHold.speech(System.currentTimeMillis())) {
                            beeper.stop()   // alguém falando: cala o toque para o "Hey Tony" chegar limpo; volta sozinho
                            JLog.i("Alarme: alguém falando, toque em pausa")
                        }
                        if (hit != null && System.currentTimeMillis() >= cooldownUntil) {
                            StateBus.lastWakeWord = PersonalStore.PHRASE + " (distância " +
                                String.format(Locale.US, "%.3f", hit.distance) + ") às " + timeFmt.format(Date())
                            beginPersonal(hit)
                            lastBytes = bytes
                            continue
                        }
                    }
                    if (manualTrigger) {
                        manualTrigger = false
                        learnCandidate = null
                        activatedByLearned = null
                        lastActivationByDetector = false
                        trigger = "toque"
                    }
                    if (trigger != null) beginListening(trigger, lastBytes)
                }
                Mode.LISTENING -> {
                    session?.push(bytes)
                    when (vad.process(rms)) {
                        VoiceActivityDetector.Result.CONTINUE -> {}
                        VoiceActivityDetector.Result.END_OF_SPEECH,
                        VoiceActivityDetector.Result.MAX_LENGTH -> endListening(true)
                        VoiceActivityDetector.Result.NO_SPEECH -> endListening(false)
                    }
                }
                Mode.BUSY, Mode.SLEEPING -> { /* descarta: nada é enviado */ }
            }
            lastBytes = bytes
        }
    }

    private fun initWakeWord() {
        reloadWake = false
        try { personal?.release() } catch (_: Throwable) {}
        personal = null
        wakeOk = false
        if (!prefs.wakeWordEnabled) {
            StateBus.wakeWordStatus = "Desativado (toque no rosto para falar)"
            if (mode == Mode.IDLE) goIdle()
            return
        }
        run {
            try {
                StateBus.wakeWordStatus = "Carregando detector pessoal…"
                if (!prefs.learnedResetV2) {
                    // 1.12.0 aprendia demais (inclusive ativações da TV): recomeça com regras mais rígidas
                    PersonalStore.clearLearned(this)
                    prefs.learnedResetV2 = true
                    JLog.i("Chamadas aprendidas na 1.12.0 descartadas (regras novas, mais rígidas)")
                }
                if (!prefs.learnedResetV3) {
                    // até a 1.15.9 o reforço podia aprender conversa como "Hey Tony" (ver "confirms")
                    PersonalStore.clearLearned(this)
                    prefs.learnedResetV3 = true
                    JLog.i("Chamadas aprendidas no uso descartadas: podiam conter conversa (regra nova de reforço)")
                }
                if (!prefs.thresholdCapV1) {
                    // o treino até a 1.15.6 subia o limiar para até 0,26 (com TV, ~0,22): ativava sozinho
                    prefs.thresholdCapV1 = true
                    if (PersonalStore.hasCustom(this)) {
                        val s = PersonalStore.load(this)
                        if (s.threshold > br.com.jarvishome.wakeword.PersonalTrainer.MAX_AUTO) {
                            JLog.i(String.format(Locale.US, "Limiar %.3f (posto pelo treino) → %.2f: estava ativando sozinho",
                                s.threshold, br.com.jarvishome.wakeword.PersonalTrainer.MAX_AUTO))
                            s.threshold = br.com.jarvishome.wakeword.PersonalTrainer.MAX_AUTO
                            PersonalStore.save(this, s)
                        }
                    }
                }
                val trainedCount = PersonalStore.load(this).templates.size
                val set = PersonalStore.loadForDetection(this)
                val learned = set.templates.size - trainedCount
                personal = PersonalWakeWord(this, set, trainedCount).also { if (System.currentTimeMillis() < strictUntil) it.strictness = 0.92f }
                wakeOk = true
                StateBus.wakeWordStatus = "Pessoal · " + set.templates.size + " exemplos" +
                    (if (learned > 0) " ($learned aprendidos no uso)" else "") +
                    (if (PersonalStore.hasCustom(this)) "" else " (gravação da casa)") +
                    " · limiar " + String.format(Locale.US, "%.3f", set.threshold)
                JLog.i("Detector pessoal pronto: " + StateBus.wakeWordStatus)
            } catch (t: Throwable) {
                JLog.e("Detector pessoal não inicializou", t)
                StateBus.wakeWordStatus = "Pessoal falhou: " + (t.message ?: t.javaClass.simpleName)
            }
        }
        if (mode == Mode.IDLE) goIdle()
    }

    // ------------------------------------------------------------------ interação

    /** Detector pessoal: comando falado de uma vez já vem no áudio; com pausa, abre o microfone. */
    private fun beginPersonal(hit: PersonalWakeWord.Trigger) {
        learnCandidate = hit.learnFrames
        activatedByLearned = hit.viaLearned
        lastActivationByDetector = true
        // A chamada só vai junto quando foi tudo numa frase ("Hey Tony, liga a luz").
        // Com pausa, NÃO: o Whisper recebia "Hey Tony" + 1–2 s de silêncio + comando e devolvia só a
        // chamada ("Reitone.", "Heitori.", "Ei Tony."), o app reabria o microfone e só na 2ª vez
        // (sem a chamada na frente) entendia. "Obrigado" inventado já é descartado à parte.
        val audio = if (hit.oneBreath) hit.whole + hit.afterSegment else hit.afterSegment
        val pcm = if (audio.isNotEmpty()) floatsToBytes(audio) else null
        beginListening(
            when {
                hit.oneBreath -> "detector pessoal (frase única)"
                hit.speechAfter -> "detector pessoal (comando logo depois)"
                else -> "detector pessoal"
            }, pcm, alreadySpoke = hit.oneBreath, quietStart = hit.speechAfter)
    }

    private fun beginListening(reason: String, preRoll: ByteArray?, alreadySpoke: Boolean = false, quietStart: Boolean = false) {
        val stopped = Timers.stopRinging()
        if (stopped) {
            beeper.stop()
            JLog.i("Toque do timer parado ($reason)")
        }
        synchronized(lock) {
            if (mode != Mode.IDLE) return
            stoppedAlarm = stopped
            JLog.i("Ativado por: $reason")
            if (reason != "toque") reopenStreak = 0
            mode = Mode.LISTENING
            vad.silenceMs = prefs.silenceMs
            // com bipe, os primeiros 300 ms (o próprio bipe no alto-falante) não contam como fala
            val beeping = !alreadySpoke && !quietStart && prefs.beep
            if (alreadySpoke) vad.startAfterSpeech() else vad.start(if (beeping) 300 else 0)
        }
        StateBus.setTranscript("")
        StateBus.setState(AssistantState.LISTENING, if (alreadySpoke) "Entendido…" else "Pode falar…")
        if (!alreadySpoke && !quietStart) main.post { player.beep(prefs.beep, Night.beepVolume(prefs)) }
        if (!prefs.isConfigured) {
            mode = Mode.BUSY
            fail("not_configured", null)
            return
        }
        val backend = Backends.create(prefs)
        val s = backend.newSession()
        session = s
        if (preRoll != null) s.push(preRoll)
        sessionExecutor.execute { runSession(backend, s) }
    }

    private fun endListening(heard: Boolean) {
        val s: Session?
        synchronized(lock) {
            if (mode != Mode.LISTENING) return
            s = session
            mode = Mode.BUSY
        }
        if (!heard || !vad.heardSpeech) {
            s?.cancel()
            session = null
            JLog.i("Nenhuma fala detectada")
            StateBus.setState(AssistantState.CONFUSED, "Não ouvi nada")
            main.postDelayed({ goIdle() }, 1500)
            return
        }
        s?.finish()
        StateBus.setState(AssistantState.THINKING, "Pensando…")
    }

    private fun runSession(backend: Backend, s: Session) {
        var text = ""
        var responseType = ""
        var confirms = false
        var audioUrl: String? = null
        var errCode: String? = null
        var errMsg: String? = null
        var continueConversation = false
        var heard = ""
        var sent = ""
        var retried: String? = null
        var local: String? = null
        try {
            s.run { ev ->
                when (ev.optString("type")) {
                    "transcription" -> {
                        StateBus.setTranscript(ev.optString("text"))
                        heard = ev.optString("heard", heard)
                        if (ev.optBoolean("retry", false)) retried = ev.optString("text") else sent = ev.optString("text")
                        ev.optString("local").takeIf { it.isNotEmpty() }?.let { local = it }
                    }
                    "response" -> {
                        text = ev.optString("text")
                        responseType = ev.optString("response_type")
                        confirms = ev.optBoolean("confirms", false)
                        continueConversation = ev.optBoolean("continue_conversation", false)
                    }
                    "audio" -> audioUrl = ev.optString("url")
                    "error" -> {
                        errCode = ev.optString("code")
                        errMsg = ev.optString("message")
                    }
                }
            }
            StateBus.setConnection(ConnectionState.CONNECTED)
        } catch (e: Throwable) {
            if (s.cancelled) return
            JLog.w("Sessão falhou", e)
            errMsg = e.message
            errCode = when (e) {
                is java.net.SocketTimeoutException -> "timeout"
                is java.io.IOException -> "network"
                else -> "internal" // defeito do app, não da rede
            }
            if (errCode == "network") StateBus.setConnection(ConnectionState.DISCONNECTED)
        }
        if (s.cancelled) return
        if (session === s) session = null
        if (mode == Mode.LISTENING) mode = Mode.BUSY // servidor encerrou antes do VAD local
        CommandHistory.add(CommandHistory.Entry(java.time.LocalTime.now(), heard, sent, retried, text,
            when {
                errCode != null -> "erro: " + errCode + (errMsg?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
                responseType == "action_done" -> "executado"
                responseType == "error" -> "a casa não entendeu"
                responseType.isNotEmpty() -> "respondido"
                else -> "sem resposta"
            }, local))

        // reforço: só um comando EXECUTADO confirma que a chamada era de verdade
        val candidate = learnCandidate
        learnCandidate = null
        val byLearned = activatedByLearned
        activatedByLearned = null
        val executed = errCode == null && responseType == "action_done" && local != "aprendizado"
        // Aprender a chamada só quando uma LUZ/DISPOSITIVO foi acionado de verdade. Antes, qualquer
        // "action_done" valia: conversa transcrita como "comprei pão" (lista: "não achei"), "quanto falta"
        // (treino) etc. confirmava uma ativação sozinha e ensinava a conversa como "Hey Tony" — mais
        // ativações sozinhas a cada vez.
        if (candidate != null && prefs.learnFromSuccess && executed && confirms) learnWake(candidate)
        // reforço negativo: ativou por um exemplo aprendido e não virou comando → esquece esse exemplo
        if (byLearned != null && !stoppedAlarm && !executed && responseType != "query_answer" && code0(errCode) != "only-wake-word") {
            forgetLearned(byLearned)
        }

        val code = errCode
        if (code == "only-wake-word" && reopenStreak >= 1) {
            reopenStreak = 0
            JLog.i("De novo sem comando entendível: volta a esperar")
            main.post {
                StateBus.setState(AssistantState.CONFUSED, "Não entendi o comando")
                main.postDelayed({ goIdle() }, 1500)
            }
            return
        }
        reopenStreak = if (code == "only-wake-word") reopenStreak + 1 else 0
        if (code == "only-wake-word") {
            // Ouviu só a palavra de ativação (ou o Whisper inventou "Obrigado"): reabre o microfone para o comando.
            main.post {
                if (mode != Mode.SLEEPING) {
                    mode = Mode.IDLE
                    manualTrigger = true
                }
            }
            return
        }
        if (code != null && text.isEmpty()) {
            fail(code, errMsg)
            return
        }
        var file: File? = null
        val url = audioUrl
        if (url != null) {
            try {
                file = File(cacheDir, "resposta.audio")
                backend.download(url, file)
            } catch (e: Exception) {
                JLog.w("Falha ao baixar áudio da resposta", e)
                file = null
            }
        }
        val finalText = text
        val rtype = responseType
        val cont = continueConversation
        val f = file
        main.post { respond(finalText, rtype, cont, f) }
    }

    private fun respond(text: String, responseType: String, cont: Boolean, file: File?) {
        if (mode == Mode.SLEEPING) return
        val outcome = when (responseType) {
            "action_done" -> AssistantState.HAPPY
            "error" -> AssistantState.CONFUSED
            else -> null
        }
        val volume = Night.volume(prefs)
        when {
            file != null -> player.play(file, volume,
                onStart = { StateBus.setState(AssistantState.SPEAKING, text) },
                onDone = { ok ->
                    if (!ok && text.isNotEmpty()) {
                        player.speakLocal(text) { afterResponse(outcome, cont) }
                    } else afterResponse(outcome, cont)
                })
            text.isNotEmpty() -> {
                JLog.w("Resposta veio sem áudio; gerando com a voz do Home Assistant")
                StateBus.setState(AssistantState.SPEAKING, text)
                speakHa(text) { afterResponse(outcome, cont) }
            }
            else -> afterResponse(AssistantState.CONFUSED, false)
        }
    }

    private fun afterResponse(outcome: AssistantState?, cont: Boolean) {
        if (mode == Mode.SLEEPING) return
        cooldownUntil = System.currentTimeMillis() + 900
        resetWake = true
        if (cont) {
            // O agente fez uma pergunta: reabre o microfone sem exigir a palavra de ativação de novo.
            mode = Mode.IDLE
            manualTrigger = true
            return
        }
        if (outcome != null) {
            StateBus.setState(outcome)
            main.postDelayed({ goIdle() }, 1600)
        } else goIdle()
    }

    private fun fail(code: String, message: String?) {
        mode = Mode.BUSY
        val display: String
        var speech: String? = null
        var state = AssistantState.ERROR
        when {
            code == "network" -> {
                display = "Sem conexão com o Home Assistant"
                speech = "Estou sem conexão com o Home Assistant."
                JLog.w("Home Assistant fora do alcance: servidor desligado, reiniciando, ou Wi-Fi do celular/roteador com problema")
            }
            code == "not_configured" -> {
                display = "Configure o servidor (engrenagem)"
                speech = "Preciso ser configurado primeiro."
            }
            code == "unauthorized" -> display = "Token recusado: confira na engrenagem"
            code == "stt-provider-missing" || code == "tts-not-supported" || code == "pipeline-not-found" -> {
                display = "Pipeline de voz incompleta no Home Assistant"
                speech = "A pipeline de voz do Home Assistant não está configurada."
            }
            code.startsWith("ha-") -> {
                display = "Home Assistant indisponível"
                speech = "A casa não está respondendo."
            }
            code == "timeout" -> {
                display = "A casa demorou para responder"
                speech = "A casa demorou demais para responder."
            }
            code == "stt-no-text-recognized" -> {
                state = AssistantState.CONFUSED
                // "Hey Tony" só para parar o alarme: não é ativação à toa
                display = if (stoppedAlarm) "Alarme desligado" else "Não ouvi nenhum comando"
                // ativação pelo detector sem fala nenhuma = provavelmente acordou sozinho: volta em silêncio
                if (stoppedAlarm) Unit
                else if (lastActivationByDetector) noteEmptyActivation() else speech = "Desculpe, não ouvi."
            }
            code == "internal" -> {
                display = "Erro interno do app (veja os logs)"
                speech = "Tive um problema aqui. Pode repetir?"
            }
            else -> display = message?.takeIf { it.isNotBlank() } ?: "Algo deu errado ($code)"
        }
        JLog.w("Falha na interação: $code " + (message ?: ""))
        main.post {
            StateBus.setState(state, display)
            val s = speech
            // sem conexão a casa não fala: só aí a voz do Android
            if (s != null && code == "network") player.speakLocal(s) { main.postDelayed({ goIdle() }, 1200) }
            else if (s != null) speakHa(s) { main.postDelayed({ goIdle() }, 1200) }
            else main.postDelayed({ goIdle() }, 3000)
        }
    }

    // ------------------------------------------------------------------ avisos e lembretes

    /** Mostra e fala um aviso do Home Assistant. Chamar na thread principal. */
    private fun announce(text: String, requested: Boolean = false) {
        HomeModel.setAnnouncement(text)
        val night = !requested && Night.isNight(prefs) && !prefs.speakAtNight
        if (mode == Mode.SLEEPING || night) {
            JLog.i("Aviso só na tela (" + (if (night) "modo noite" else "repouso") + ")")
            return
        }
        if (mode != Mode.IDLE) {
            pendingAnnouncements.addLast(text to requested) // fala quando a conversa terminar
            return
        }
        mode = Mode.BUSY
        StateBus.setState(AssistantState.SPEAKING, text)
        player.beep(prefs.beep, Night.beepVolume(prefs))
        val backend = Backends.create(prefs)
        voiceExecutor.execute {
            var file: File? = null
            try {
                file = voiceFile(text)
                if (file == null && backend !is HaDirectBackend) JLog.w("Aviso: modo sem Home Assistant direto")
            } catch (e: Throwable) {
                JLog.w("Aviso sem a voz do servidor; usando a voz local", e)
            }
            val f = file
            main.post {
                if (mode == Mode.SLEEPING) return@post
                if (f != null) {
                    player.play(f, Night.volume(prefs),
                        onStart = { StateBus.setState(AssistantState.SPEAKING, text) },
                        onDone = { ok -> if (ok) afterAnnouncement() else player.speakLocal(text) { afterAnnouncement() } })
                } else {
                    player.speakLocal(text) { afterAnnouncement() }
                }
            }
        }
    }

    private fun afterAnnouncement() {
        cooldownUntil = System.currentTimeMillis() + 900
        resetWake = true
        if (mode == Mode.BUSY) mode = Mode.IDLE
        goIdle()
    }

    private fun goIdle() {
        if (mode == Mode.SLEEPING || mode == Mode.LISTENING) return
        mode = Mode.IDLE
        main.postDelayed({
            if (mode == Mode.IDLE && pendingAnnouncements.isNotEmpty()) pendingAnnouncements.removeFirst().let { announce(it.first, it.second) }
        }, 1200)
        val hint = when {
            !prefs.isConfigured -> "Toque na engrenagem para configurar"
            !running -> "Microfone desligado"
            wakeOk -> "Diga “" + PersonalStore.PHRASE + "” para começar"
            else -> "Toque no rosto para falar"
        }
        StateBus.setState(AssistantState.IDLE, hint)
    }

    // ------------------------------------------------------------------ conexão

    private val healthLoop = object : Runnable {
        override fun run() {
            healthExecutor.execute { StateBus.setConnection(Backends.create(prefs).health()) }
            HomeMonitor.watchdog(this@JarvisForegroundService)
            val next = if (StateBus.connection == ConnectionState.CONNECTED) 20000L else 6000L
            main.postDelayed(this, next)
        }
    }

    // ------------------------------------------------------------------ notificação

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Jarvis ativo", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Mostra que o Jarvis está escutando a palavra de ativação"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, JarvisForegroundService::class.java).setAction(ACTION_TOGGLE_SLEEP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sleeping = mode == Mode.SLEEPING
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis Home")
            .setContentText(if (sleeping) "Em repouso: microfone desligado" else "Aguardando “" + br.com.jarvishome.wakeword.WakeWords.active(prefs).display + "”")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (sleeping) "Ativar" else "Repousar", toggle).build())
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            JLog.e("startForeground com tipo microfone falhou", e)
            try { startForeground(NOTIFICATION_ID, n) } catch (_: Exception) {}
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_START = "br.com.jarvishome.START"
        const val ACTION_LISTEN = "br.com.jarvishome.LISTEN"
        const val ACTION_SLEEP = "br.com.jarvishome.SLEEP"
        const val ACTION_WAKE = "br.com.jarvishome.WAKE"
        const val ACTION_TOGGLE_SLEEP = "br.com.jarvishome.TOGGLE_SLEEP"
        const val ACTION_RELOAD = "br.com.jarvishome.RELOAD"
        const val ACTION_TEST_VOICE = "br.com.jarvishome.TEST_VOICE"
        const val ACTION_STOP = "br.com.jarvishome.STOP"
        const val ACTION_PAUSE_MIC = "br.com.jarvishome.PAUSE_MIC"
        const val ACTION_RESUME_MIC = "br.com.jarvishome.RESUME_MIC"
        const val ACTION_PAUSE_TOTAL = "br.com.jarvishome.PAUSE_TOTAL"
        const val ACTION_RESUME_TOTAL = "br.com.jarvishome.RESUME_TOTAL"

        /** App novo (ha_local_assistant): os dois convivem no Redmi, um ativo por vez. */
        const val OTHER_PACKAGE = "io.github.duduferaso.halocalassistant"
        /** Pedido do outro app para este pausar (recebido por PauseReceiver). */
        const val BROADCAST_PAUSE_ME = "br.com.jarvishome.PAUSAR"
        /** Pedido deste app para o outro pausar. */
        const val BROADCAST_PAUSE_OTHER = "io.github.duduferaso.halocalassistant.PAUSAR"

        fun askOtherToPause(context: Context) {
            try { context.sendBroadcast(Intent(BROADCAST_PAUSE_OTHER).setPackage(OTHER_PACKAGE)) } catch (_: Exception) {}
        }

        private const val CHANNEL_ID = "jarvis_service"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600 // 100 ms

        fun send(context: Context, action: String) {
            val i = Intent(context, JarvisForegroundService::class.java).setAction(action)
            context.startForegroundService(i)
        }

        private fun floatsToBytes(a: FloatArray): ByteArray {
            val out = ByteArray(a.size * 2)
            for (i in a.indices) {
                val v = (a[i].coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }

        private fun toBytes(buf: ShortArray, n: Int): ByteArray {
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = buf[i].toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
