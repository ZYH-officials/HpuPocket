package com.hpu.timetable.data.hw

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
 * 作业平台的账号密码与登录态，**按平台分开存**。
 *
 * 这些平台都是第三方 SaaS，密码自己设、和统一认证不一定相同，所以：
 *  - 存的数据全部用 AndroidKeyStore 的 AES-GCM 加密；
 *  - 键名带平台 id（如 `cred.smartestu.u`），以后接第二个平台互不影响。
 */
object HwStore {

    private const val PREFS = "hpu_hw"
    private const val KEY_ALIAS = "hpu_hw_key"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(platformId: String, field: String) = "$platformId.$field"

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

    fun saveCredentials(ctx: Context, platformId: String, account: String, password: String) {
        val u = encrypt(account) ?: return
        val p = encrypt(password) ?: return
        prefs(ctx).edit()
            .putString(key(platformId, "u"), u)
            .putString(key(platformId, "p"), p)
            .apply()
    }

    /** 账号 → 密码；任一取不到返回 null */
    fun credentials(ctx: Context, platformId: String): Pair<String, String>? {
        val pu = prefs(ctx).getString(key(platformId, "u"), null) ?: return null
        val pp = prefs(ctx).getString(key(platformId, "p"), null) ?: return null
        val u = decrypt(pu) ?: return null
        val p = decrypt(pp) ?: return null
        if (u.isBlank() || p.isBlank()) return null
        return u to p
    }

    fun account(ctx: Context, platformId: String): String {
        val pu = prefs(ctx).getString(key(platformId, "u"), null) ?: return ""
        return decrypt(pu).orEmpty()
    }

    fun saveSession(ctx: Context, platformId: String, sessionContext: String, csrfToken: String) {
        val c = encrypt(sessionContext) ?: return
        val s = encrypt(csrfToken) ?: return
        prefs(ctx).edit()
            .putString(key(platformId, "ctx"), c)
            .putString(key(platformId, "csrf"), s)
            .apply()
    }

    /** sessionContext → csrfToken */
    fun session(ctx: Context, platformId: String): Pair<String, String>? {
        val pc = prefs(ctx).getString(key(platformId, "ctx"), null) ?: return null
        val ps = prefs(ctx).getString(key(platformId, "csrf"), null) ?: return null
        val c = decrypt(pc) ?: return null
        val s = decrypt(ps) ?: return null
        if (c.isBlank() || s.isBlank()) return null
        return c to s
    }

    /** 清掉某个平台的账号密码和登录态 */
    fun clear(ctx: Context, platformId: String) {
        prefs(ctx).edit()
            .remove(key(platformId, "u"))
            .remove(key(platformId, "p"))
            .remove(key(platformId, "ctx"))
            .remove(key(platformId, "csrf"))
            .apply()
    }
}
