package tech.elephantclock.privatemail.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleInfoTest {
    @Test
    fun moduleNameIsSet() {
        assertEquals("core-ai", ModuleInfo.NAME)
    }
}
