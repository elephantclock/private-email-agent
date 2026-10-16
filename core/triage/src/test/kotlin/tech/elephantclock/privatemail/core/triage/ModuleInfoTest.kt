package tech.elephantclock.privatemail.core.triage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleInfoTest {
    @Test
    fun `module name is set`() {
        assertEquals("core-triage", ModuleInfo.NAME)
    }
}
