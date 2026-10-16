package tech.elephantclock.privatemail.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleInfoTest {
    @Test
    fun `module name is set`() {
        assertEquals("core-model", ModuleInfo.NAME)
    }
}
