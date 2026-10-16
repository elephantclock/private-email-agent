package tech.elephantclock.privatemail.core.data.secure

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.model.AccountId

/** The real Android Keystore: key generation, Keystore-generated IVs, deletion. */
@RunWith(AndroidJUnit4::class)
class SecretStoreKeystoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("pea_secrets_test", Context.MODE_PRIVATE)
    private val keys = AndroidKeystoreKeys()
    private val store = SecretStore(prefs, keys)
    private val a = AccountId("it-acc-a")
    private val b = AccountId("it-acc-b")

    @After
    fun tearDown() {
        store.delete(a)
        store.delete(b)
    }

    @Test
    fun roundTripWithKeystoreKey() {
        store.put(a, Password("canary-Pa55w0rd"))
        assertEquals(StoredSecret.Found(Password("canary-Pa55w0rd")), store.get(a))
        assertFalse(
            prefs.all.values
                .joinToString()
                .contains("canary"),
        )
    }

    @Test
    fun deleteRemovesKeystoreKey() {
        store.put(a, Password("pw"))
        store.delete(a)
        assertNull(keys.get("pea.secret.it-acc-a"))
        assertEquals(StoredSecret.Missing, store.get(a))
    }

    @Test
    fun swappedOrCorruptedBlobNeedsReentry() {
        store.put(a, Password("pw-a"))
        store.put(b, Password("pw-b"))
        prefs.edit().putString(b.value, prefs.getString(a.value, null)).commit()
        assertEquals(StoredSecret.NeedsReentry, store.get(b))
        prefs.edit().putString(a.value, "AQxBQUFB").commit()
        assertEquals(StoredSecret.NeedsReentry, store.get(a))
    }
}
