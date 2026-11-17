package com.hpu.timetable.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * remember 的账号密码，用 Android Keystore 里的 AES-GCM 密钥加密后存 SharedPreferences。
 *
 * 密钥本身存在系统安全区（Keystore），App 只能拿它加解密、导不出来；
 * 设备被 root 之外基本无法还原明文。用于会话过期后的自动重登。
 */
object CredentialStore {

    private const val PREFS = "hpu_cred"
    private const val KEY_ALIAS = "hpu_login_key"
    private const val K_USER = "u"
    private const val K_PASS = "p"
    private const val K_ENABLED = "enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + out, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        if (bytes.size < 13) return null
        val iv = bytes.copyOfRange(0, 12)
        val body = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply()
        if (!on) clear(ctx)
    }

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, true)

    fun save(ctx: Context, username: String, password: String) {
        val u = encrypt(username)
        val p = encrypt(password)
        if (u == null || p == null) return
        prefs(ctx).edit().putString(K_USER, u).putString(K_PASS, p).apply()
    }

    /** 返回 账号 到 密码；任一取不到就返回 null */
    fun load(ctx: Context): Pair<String, String>? {
        val pu = prefs(ctx).getString(K_USER, null) ?: return null
        val pp = prefs(ctx).getString(K_PASS, null) ?: return null
        val u = decrypt(pu) ?: return null
        val p = decrypt(pp) ?: return null
        if (u.isBlank() || p.isBlank()) return null
        return u to p
    }

    fun username(ctx: Context): String {
        val pu = prefs(ctx).getString(K_USER, null) ?: return ""
        return decrypt(pu).orEmpty()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_USER).remove(K_PASS).apply()
    }
}
