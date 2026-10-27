package com.moa.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.moa.app.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("moa_session", Context.MODE_PRIVATE)
    private val alias = "${BuildConfig.APPLICATION_ID}.session"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun load(): Session? {
        val encrypted = preferences.getString("encrypted", null) ?: return null
        return try {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(BuildConfig.APPLICATION_ID.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            Session(json.getString("serverUrl"), json.getString("token"), json.getString("expiresAt"), json.getJSONObject("member").member())
        } catch (_: Exception) {
            clear()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
            null
        }
    }

    fun save(session: Session) {
        val json = JSONObject().put("serverUrl", session.serverUrl).put("token", session.token).put("expiresAt", session.expiresAt)
            .put("member", JSONObject().put("id", session.member.id).put("displayName", session.member.displayName))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(BuildConfig.APPLICATION_ID.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("serverUrl", session.serverUrl).commit()) { "로그인을 저장하지 못했어요." }
    }

    fun lastServer(): String = preferences.getString("serverUrl", null) ?: BuildConfig.API_BASE_URL.let {
        if (it.contains(".invalid")) "" else it
    }
    fun clear() { preferences.edit().remove("encrypted").commit() }
}
