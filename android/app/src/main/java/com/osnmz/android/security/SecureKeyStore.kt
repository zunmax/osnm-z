package com.osnmz.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.osnmz.android.engine.WalletCreds
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The ONLY place a private key is ever stored on the device.
 *
 * - One AES-256-GCM key per job, generated inside the Android Keystore
 *   (key material never leaves the Keystore / TEE where available).
 * - Only the GCM ciphertext + IV are stored, in plain SharedPreferences
 *   (file shared_prefs/osnmz_keys.xml). That file is excluded from backup
 *   in res/xml/backup_rules.xml, so a backup never carries usable keys —
 *   and even a leaked ciphertext is useless without the Keystore key.
 * - Keys are never logged, never returned to the UI, never put into Job
 *   JSON, never sent over the network. Only the derived address (public)
 *   and locally-signed transactions leave the device.
 * - Deleting a job must call [deleteKey]; it deletes the Keystore key
 *   and the ciphertext, which is irreversible by design.
 *
 * No third-party crypto library is used here: this is framework
 * javax.crypto + AndroidKeyStore only.
 */
object SecureKeyStore {
    private const val FILE = "osnmz_keys"
    private const val PREFIX = "key_"
    private const val IV_PREFIX = "iv_"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keyAlias(jobId: String) = "osnmz_$jobId"

    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(alias, null)?.let { return it as SecretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** Validate + store. Returns the derived checksummed address, or throws. */
    fun storeKey(context: Context, jobId: String, keyHex: String): String {
        val creds = WalletCreds.fromHex(keyHex) // validates format, derives address
        val key = getOrCreateKey(keyAlias(jobId))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val clean = keyHex.trim().removePrefix("0x").removePrefix("0X")
        val ct = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(PREFIX + jobId, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(IV_PREFIX + jobId, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        return creds.address
    }

    fun hasKey(context: Context, jobId: String): Boolean =
        prefs(context).contains(PREFIX + jobId)

    /** Returns in-memory credentials for a run. Caller must not log or persist it. */
    fun credentials(context: Context, jobId: String): WalletCreds {
        val p = prefs(context)
        val ctB64 = p.getString(PREFIX + jobId, null)
            ?: throw IllegalStateException("Tidak ada private key tersimpan untuk job ini.")
        val ivB64 = p.getString(IV_PREFIX + jobId, null)
            ?: throw IllegalStateException("Data key job ini rusak (IV hilang).")
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (ks.getKey(keyAlias(jobId), null) as? SecretKey)
            ?: throw IllegalStateException("Key Keystore untuk job ini hilang.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        val plain = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return WalletCreds.fromHex(plain)
    }

    fun deleteKey(context: Context, jobId: String) {
        prefs(context).edit().remove(PREFIX + jobId).remove(IV_PREFIX + jobId).apply()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(keyAlias(jobId))) ks.deleteEntry(keyAlias(jobId))
        } catch (_: Exception) {
        }
    }
}
