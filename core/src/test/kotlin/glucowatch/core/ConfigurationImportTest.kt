package glucowatch.core

import glucowatch.core.link.LinkSource
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationImportTest {
    @Test fun `filled development dotenv accepts literal secrets and unrelated desktop keys`() {
        val config = ImportedConfiguration.parse("""
            # developer defaults
            export DEXCOM_USERNAME=person
            DEXCOM_PASSWORD='literal ${'$'}(command) # secret'
            DEXCOM_REGION=eu # outside US
            NIGHTSCOUT_URL=
            GLUCOWATCH_SOURCE=
            HF_TOKEN="hf_example"
            HF_MODEL_ADDRESS=owner/model
            UNRELATED_DESKTOP_OPTION=value
        """.trimIndent())
        assertEquals(LinkSource.SHARE, config.source)
        assertEquals("literal ${'$'}(command) # secret", config["DEXCOM_PASSWORD"])
        assertEquals("eu", config["DEXCOM_REGION"])
        assertEquals("hf_example", config["HF_TOKEN"])
        assertFalse(config.toString().contains("hf_example"))
        assertNull(config.alsoFrom)
    }

    @Test fun `yaml maps all phone settings without changing omitted fields`() {
        val config = ImportedConfiguration.parse("""
            version: 1
            source: dexcom
            unit: mgdl
            also_from: [carelink]
            heart_track: true
            dexcom:
              username: person
              password: "001234"
              region: us
              notifications: false
            carelink:
              country: DE
            prediction:
              model: owner/model
              hf_token: hf_example
        """.trimIndent())
        assertEquals(LinkSource.SHARE, config.source)
        assertEquals(setOf(LinkSource.CARELINK), config.alsoFrom)
        assertEquals("001234", config["DEXCOM_PASSWORD"])
        assertEquals("true", config["GLUCOWATCH_HEART_TRACK"])
        assertNull(config["NIGHTSCOUT_TOKEN"])
        assertNull(config.carelinkToken)
    }

    @Test fun `nightscout inference and explicit empty pump list work`() {
        val config = ImportedConfiguration.parse("NIGHTSCOUT_URL=https://example.org\nGLUCOWATCH_ALSO_FROM=")
        assertEquals(LinkSource.NIGHTSCOUT, config.source)
        assertEquals(emptySet(), config.alsoFrom)
        assertEquals(LinkSource.DEMO, ImportedConfiguration.parse("source: demo").source)
        assertNull(ImportedConfiguration.parse("HF_TOKEN=hf_example").source)
    }

    @Test fun `bom is accepted and dotenv has no shell or escape expansion`() {
        val config = ImportedConfiguration.parse("\uFEFFDEXCOM_PASSWORD=\"a\\nb${'$'}HOME\"")
        assertEquals("a\\nb${'$'}HOME", config["DEXCOM_PASSWORD"])
    }

    @Test fun `duplicates malformed assignments and unclosed quotes are rejected`() {
        listOf("HF_TOKEN=a\nHF_TOKEN=b", "HF_TOKEN=\"unclosed", "HF_TOKEN=a\ninvalid assignment",
            "prediction:\n  hf_token: secret\n  hf_token: different").forEach { assertFails { ImportedConfiguration.parse(it) } }
    }

    @Test fun `invalid schema and settings report no secret values`() {
        listOf("DEXCOM_REGION=secret-value", "GLUCOWATCH_SOURCE=secret-value", "GLUCOWATCH_HEART_TRACK=secret-value",
            "CARELINK_COUNTRY=secret-value", "prediction:\n  token_typo: secret-value", "version: 2\nsource: demo",
            "dexcom:\n  password: 001234", "[secret-value]", "prediction: [secret-value]").forEach { text ->
            val failure = assertFails { ImportedConfiguration.parse(text) }
            assertFalse(failure.message.orEmpty().contains("secret-value"))
        }
    }

    @Test fun `yaml parser errors do not echo credential source lines`() {
        val failure = assertFails { ImportedConfiguration.parse("prediction:\n  hf_token: [secret-value\n") }
        assertFalse(failure.message.orEmpty().contains("secret-value"))
        assertNull(failure.cause)
    }

    @Test fun `java objects aliases multiple documents and excessive nesting are rejected`() {
        listOf("!!java.net.URL [https://example.org]", "dexcom: &login {username: person}\nnightscout: *login",
            "source: demo\n---\nsource: dexcom", "dexcom: [[[[[[[[[[person]]]]]]]]]]").forEach {
            assertFails { ImportedConfiguration.parse(it) }
        }
    }

    @Test fun `size limit counts utf8 bytes and empty unknown files are rejected`() {
        assertFails { ImportedConfiguration.parse("DEXCOM_PASSWORD=" + "é".repeat(ImportedConfiguration.MAX_BYTES / 2)) }
        assertFails { ImportedConfiguration.parse("# nothing") }
        assertFails { ImportedConfiguration.parse("UNRELATED_OPTION=value") }
        assertEquals("hf_example", ImportedConfiguration.parse("HF_TOKEN=hf_example")["HF_TOKEN"])
    }

    @Test fun `complete carelink session imports the same account identifier as browser login`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"test-person"}""".toByteArray())
        val config = ImportedConfiguration.parse("""
            CARELINK_COUNTRY=de
            CARELINK_CLIENT_ID=test-client
            CARELINK_ACCESS_TOKEN=header.$payload.signature
            CARELINK_REFRESH_TOKEN=test-refresh
            CARELINK_EXPIRES_AT=1791060000000
        """.trimIndent())
        assertEquals("test-person", config.carelinkToken?.subject)
        assertEquals("DE", config.carelinkToken?.country)
        assertEquals(1791060000000, config.carelinkToken?.expiresAt)
        assertTrue(config.carelinkToken?.toString()?.contains("test-refresh") == false)
        assertFails { ImportedConfiguration.parse("CARELINK_REFRESH_TOKEN=test-refresh") }
        assertFails { ImportedConfiguration.parse("CARELINK_CLIENT_ID=c\nCARELINK_ACCESS_TOKEN=invalid\nCARELINK_REFRESH_TOKEN=r\nCARELINK_EXPIRES_AT=123") }
    }
}
