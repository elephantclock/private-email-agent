package tech.elephantclock.privatemail.core.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.model.AccountId
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

sealed interface StoredSecret {
    data class Found(
        val password: Password,
    ) : StoredSecret

    data object Missing : StoredSecret

    /** The stored password can't be decrypted (key gone, data corrupted or from another device). Ask again. */
    data object NeedsReentry : StoredSecret
}

/**
 * Account passwords, encrypted with one Keystore key per account (AES-GCM, the account id as
 * associated data so blobs can't be swapped between accounts). Removing an account deletes the
 * blob and its key. The prefs file is excluded from backups and device transfer.
 */
class SecretStore(
    private val prefs: SharedPreferences,
    private val keys: SecretKeys,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        AndroidKeystoreKeys(),
    )

    fun put(
        account: AccountId,
        password: Password,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate(alias(account)))
        cipher.updateAAD(aad(account))
        val sealed = cipher.doFinal(password.reveal().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val blob =
            ByteBuffer
                .allocate(2 + iv.size + sealed.size)
                .put(VERSION)
                .put(iv.size.toByte())
                .put(iv)
                .put(sealed)
                .array()
        check(prefs.edit().putString(account.value, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "could not save the password"
        }
    }

    fun get(account: AccountId): StoredSecret {
        val encoded = prefs.getString(account.value, null) ?: return StoredSecret.Missing
        val key = keys.get(alias(account)) ?: return StoredSecret.NeedsReentry
        return try {
            val blob = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
            if (blob.get() != VERSION) return StoredSecret.NeedsReentry
            val iv = ByteArray(blob.get().toInt() and 0xFF).also { blob.get(it) }
            val sealed = ByteArray(blob.remaining()).also { blob.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(account))
            StoredSecret.Found(Password(String(cipher.doFinal(sealed), Charsets.UTF_8)))
        } catch (_: GeneralSecurityException) {
            StoredSecret.NeedsReentry
        } catch (_: RuntimeException) {
            // Truncated blob (BufferUnderflow), bad Base64, empty password.
            StoredSecret.NeedsReentry
        }
    }

    /** On account removal: the blob and the key both go, so any copy of the blob is useless. */
    fun delete(account: AccountId) {
        check(prefs.edit().remove(account.value).commit()) { "could not delete the password" }
        keys.delete(alias(account))
    }

    private fun alias(account: AccountId) = "pea.secret.${account.value}"

    private fun aad(account: AccountId) = "pea-secret-v1:${account.value}".toByteArray(Charsets.UTF_8)

    companion object {
        /** Listed in the backup exclusion rules' `sharedpref` domain (excluded entirely). */
        const val PREFS_NAME = "pea_secrets"
        private const val VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
