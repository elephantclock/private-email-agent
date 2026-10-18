package tech.elephantclock.privatemail.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tech.elephantclock.privatemail.feature.onboarding.cert

@RunWith(RobolectricTestRunner::class)
class CertificatePinStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun aPinMatchesOnlyItsHostPortAndFingerprint() {
        val store = CertificatePinStore(context)
        store.trust(cert("Mail.Corp.Example", 993, "AA:01"))

        assertTrue(store.isPinned("mail.corp.example", 993, "AA:01"))
        assertFalse(store.isPinned("mail.corp.example", 465, "AA:01"))
        assertFalse(store.isPinned("other.example", 993, "AA:01"))
        assertFalse(store.isPinned("mail.corp.example", 993, "AA:02"))
    }

    @Test
    fun pinsSurviveARestartAndANewPinReplacesTheOld() {
        CertificatePinStore(context).trust(cert("mail.corp.example", 993, "AA:01"))
        CertificatePinStore(context).trust(cert("mail.corp.example", 993, "AA:02"))

        val reloaded = CertificatePinStore(context)
        assertTrue(reloaded.isPinned("mail.corp.example", 993, "AA:02"))
        assertFalse(reloaded.isPinned("mail.corp.example", 993, "AA:01"))
    }
}
