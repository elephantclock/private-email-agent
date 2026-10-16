package tech.elephantclock.privatemail

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Passwords, keys and the mail database must never leave the device. On Android 12+,
 * `allowBackup="false"` stops cloud backup but not device-to-device transfer; only the
 * `device-transfer` excludes do that, so both sections are checked.
 */
class BackupRulesTest {
    private fun xml(path: String) =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    @Test
    fun `backup is off and the extraction rules are wired up`() {
        val app = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun `cloud backup and device transfer exclude every domain`() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val node = rules.getElementsByTagName(section).item(0) as Element
            val excluded =
                (0 until node.childNodes.length)
                    .mapNotNull { node.childNodes.item(it) as? Element }
                    .filter { it.tagName == "exclude" }
                    .map { it.getAttribute("domain") }
                    .toSet()
            assertEquals(section, setOf("root", "file", "database", "sharedpref", "external"), excluded)
            assertEquals(section, 0, node.getElementsByTagName("include").length)
        }
    }
}
