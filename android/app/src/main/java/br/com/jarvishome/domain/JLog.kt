package br.com.jarvishome.domain

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Log técnico em memória (tela de diagnóstico). Nunca registra áudio. */
object JLog {
    private const val TAG = "JarvisHome"
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized private fun add(level: String, msg: String) {
        lines.addLast(fmt.format(Date()) + " " + level + " " + msg)
        while (lines.size > MAX) lines.removeFirst()
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        add("I", msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        add("W", if (t != null) msg + " (" + t.javaClass.simpleName + ": " + t.message + ")" else msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        add("E", if (t != null) msg + " (" + t.javaClass.simpleName + ": " + t.message + ")" else msg)
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")
}
