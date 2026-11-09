package br.com.jarvishome.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import br.com.jarvishome.domain.JLog
import kotlin.math.PI
import kotlin.math.sin

/** Toque do timer/alarme: "bip-bip-bip … pausa", em loop, no volume de alarme. */
class AlarmBeeper {
    private var track: AudioTrack? = null

    val playing get() = track != null

    @Synchronized fun start() {
        if (track != null) return
        try {
            val rate = 22050
            val pattern = ArrayList<Short>()
            fun tone(ms: Int, hz: Double) {
                val n = rate * ms / 1000
                for (i in 0 until n) {
                    val env = minOf(1.0, minOf(i, n - i) / (rate * 0.01)) // sem estalos
                    pattern.add((sin(2 * PI * hz * i / rate) * 0.8 * env * Short.MAX_VALUE).toInt().toShort())
                }
            }
            fun silence(ms: Int) = repeat(rate * ms / 1000) { pattern.add(0) }
            repeat(3) { tone(160, 1046.5); silence(110) }
            silence(1100) // pausa: dá para falar "Hey Tony, para"
            val data = pattern.toShortArray()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            t.write(data, 0, data.size)
            t.setLoopPoints(0, data.size, -1)
            t.play()
            track = t
        } catch (e: Exception) {
            JLog.w("Não consegui tocar o alarme", e)
        }
    }

    @Synchronized fun stop() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (_: Exception) {}
        t.release()
    }
}
