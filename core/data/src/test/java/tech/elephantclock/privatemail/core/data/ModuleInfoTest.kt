package tech.elephantclock.privatemail.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun moduleNameIsSet() {
        assertEquals("core-data", ModuleInfo.NAME)
    }
}
