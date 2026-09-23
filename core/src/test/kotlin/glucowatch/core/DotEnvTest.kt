package glucowatch.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DotEnvTest {
    @Test
    fun `parses comments, quotes and export prefix`() {
        val env = DotEnv.parse(
            """
            # Dexcom account
            DEXCOM_USERNAME=me@example.com
            export DEXCOM_REGION = eu   # outside US
            DEXCOM_PASSWORD="p@ss #1 = ok"
            SINGLE='it''s'
            PLAIN=a#b
            EMPTY=
            not a pair
            """.trimIndent(),
        )
        assertEquals("me@example.com", env["DEXCOM_USERNAME"])
        assertEquals("eu", env["DEXCOM_REGION"])
        assertEquals("p@ss #1 = ok", env["DEXCOM_PASSWORD"])
        assertEquals("it''s", env["SINGLE"])
        assertEquals("a#b", env["PLAIN"])
        assertEquals("", env["EMPTY"])
        assertEquals(6, env.size)
    }

    @Test
    fun `finds the file in a parent directory and lets the environment win`() {
        val root = Files.createTempDirectory("dotenv").toFile()
        File(root, ".env").writeText("DEXCOM_REGION=us\nDEXCOM_USERNAME=file\n")
        val nested = File(root, "core/build").apply { mkdirs() }
        val env = DotEnv.load(nested, environment = mapOf("DEXCOM_USERNAME" to "shell"))
        assertEquals("us", env["DEXCOM_REGION"])
        assertEquals("shell", env["DEXCOM_USERNAME"])
        root.deleteRecursively()
    }

    @Test
    fun `parses region and unit aliases`() {
        assertEquals(Region.OUS, Region.parse("EU"))
        assertEquals(Region.OUS, Region.parse("ous"))
        assertEquals(Region.JP, Region.parse(" jp "))
        assertFailsWith<IllegalArgumentException> { Region.parse("de") }
        assertEquals(GlucoseUnit.MGDL, GlucoseUnit.parse("mg/dL"))
        assertEquals(GlucoseUnit.MMOL, GlucoseUnit.parse("mmol"))
    }
}
