package br.com.jarvishome.settings

import android.content.Context
import br.com.jarvishome.domain.JLog
import org.json.JSONObject

/**
 * Configuração de fábrica: se o APK foi gerado com assets/provision.json
 * (build.sh com PROVISION_URL/PROVISION_TOKEN), aplica endereço e token uma única vez
 * por "id". Depois disso, o que for alterado nas configurações prevalece.
 * O arquivo nunca fica no código-fonte; é criado só durante o build.
 */
object Provisioning {
    fun apply(context: Context) {
        val text = try {
            context.assets.open("provision.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
        }
        try {
            val o = JSONObject(text)
            val id = o.optString("id")
            val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            if (id.isEmpty() || sp.getString("provision_id", "") == id) return
            val prefs = Prefs(context)
            prefs.directMode = o.optBoolean("direct", true) // antes da URL: define a porta padrão
            o.optString("url").takeIf { it.isNotEmpty() }?.let { prefs.serverUrl = it }
            o.optString("token").takeIf { it.isNotEmpty() }?.let { prefs.token = it }
            sp.edit().putString("provision_id", id).apply()
            JLog.i("Configuração de fábrica aplicada ($id)")
        } catch (e: Exception) {
            JLog.e("provision.json inválido", e)
        }
    }
}
