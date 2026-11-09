package br.com.jarvishome.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import br.com.jarvishome.domain.JLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Guarda segredos cifrados com chave AES-GCM do Android Keystore (nunca em texto simples). */
class SecureStore(context: Context) {
    private val sp = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun put(name: String, value: String) {
        try {
            if (value.isEmpty()) {
                sp.edit().remove(name).apply()
                return
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(enc, Base64.NO_WRAP)
            sp.edit().putString(name, packed).apply()
        } catch (e: Exception) {
            JLog.e("Falha ao cifrar segredo", e)
        }
    }

    fun get(name: String): String {
        val packed = sp.getString(name, null) ?: return ""
        return try {
            val parts = packed.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            JLog.e("Falha ao decifrar segredo", e)
            ""
        }
    }

    companion object {
        private const val ALIAS = "jarvis_terminal_key"
    }
}
