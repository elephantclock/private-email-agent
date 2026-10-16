package tech.elephantclock.privatemail.core.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun moduleNameIsSet() {
        assertEquals("core-mail", ModuleInfo.NAME)
    }
}
