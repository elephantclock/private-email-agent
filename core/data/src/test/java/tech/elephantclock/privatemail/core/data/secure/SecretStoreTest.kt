package tech.elephantclock.privatemail.core.data.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.model.AccountId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Store logic with an in-memory key (Robolectric has no AndroidKeyStore). The same cases run
 * against the real Keystore in `SecretStoreKeystoreTest` (androidTest, on the emulator).
 */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {
    private class MemoryKeys : SecretKeys {
        val keys = mutableMapOf<String, SecretKey>()

        override fun get(alias: String) = keys[alias]

        override fun getOrCreate(alias: String) =
            keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }

        override fun delete(alias: String) {
            keys.remove(alias)
        }
    }

    private val prefs =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(
            SecretStore.PREFS_NAME,
            0,
        )
    private val keys = MemoryKeys()
    private val store = SecretStore(prefs, keys)
    private val a = AccountId("acc-a")
    private val b = AccountId("acc-b")

    @Test
    fun `round trip, and nothing readable at rest`() {
        store.put(a, Password("canary-Pa55w0rd-ÄÖ"))
        assertEquals(StoredSecret.Found(Password("canary-Pa55w0rd-ÄÖ")), store.get(a))
        assertFalse(
            prefs.all.values
                .joinToString()
                .contains("canary"),
        )
        assertEquals(StoredSecret.Missing, store.get(b))
    }

    @Test
    fun `each encryption uses a fresh IV`() {
        store.put(a, Password("same"))
        val first = prefs.getString(a.value, null)
        store.put(a, Password("same"))
        assertFalse(first == prefs.getString(a.value, null))
    }

    @Test
    fun `delete removes the blob and the key`() {
        store.put(a, Password("pw-123"))
        store.delete(a)
        assertEquals(StoredSecret.Missing, store.get(a))
        assertNull(keys.get("pea.secret.acc-a"))
    }

    @Test
    fun `swapped, corrupted or orphaned blobs ask for the password again`() {
        store.put(a, Password("pw-a"))
        store.put(b, Password("pw-b"))
        prefs.edit().putString(b.value, prefs.getString(a.value, null)).commit()
        assertEquals(StoredSecret.NeedsReentry, store.get(b))

        prefs.edit().putString(a.value, "not base64 !!").commit()
        assertEquals(StoredSecret.NeedsReentry, store.get(a))
        prefs.edit().putString(a.value, "AQ==").commit()
        assertEquals(StoredSecret.NeedsReentry, store.get(a))

        store.put(a, Password("pw-a"))
        keys.delete("pea.secret.acc-a")
        assertEquals(StoredSecret.NeedsReentry, store.get(a))
    }
}
