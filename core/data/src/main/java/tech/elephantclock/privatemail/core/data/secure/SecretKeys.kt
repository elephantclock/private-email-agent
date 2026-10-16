package tech.elephantclock.privatemail.core.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Where per-account encryption keys live. Production: [AndroidKeystoreKeys]. */
interface SecretKeys {
    fun getOrCreate(alias: String): SecretKey

    fun get(alias: String): SecretKey?

    fun delete(alias: String)
}

/**
 * AES-256-GCM keys in the Android Keystore: generated inside it (hardware-backed where the
 * device has a TEE), never exportable. Usable after the first unlock since boot, also while
 * the screen is locked, so background sync and urgent alerts keep working (user's decision,
 * 16 Oct 2026). The Keystore insists on generating the GCM IV itself.
 */
class AndroidKeystoreKeys : SecretKeys {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun get(alias: String): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    override fun getOrCreate(alias: String): SecretKey =
        get(alias) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .setUnlockedDeviceRequired(false)
                        .build(),
                )
            }.generateKey()

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
