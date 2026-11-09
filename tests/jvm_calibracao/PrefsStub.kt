package br.com.jarvishome.settings
object PrefsStub
class Prefs { companion object { fun thresholdFor(s: Int): Float = 0.40f - (s - 1) * (0.32f / 9f) } }
