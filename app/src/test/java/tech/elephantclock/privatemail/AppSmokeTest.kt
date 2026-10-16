package tech.elephantclock.privatemail

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSmokeTest {
    @Test
    fun applicationIdMatchesPackage() {
        assertEquals("tech.elephantclock.privatemail", BuildConfig.APPLICATION_ID)
    }
}
