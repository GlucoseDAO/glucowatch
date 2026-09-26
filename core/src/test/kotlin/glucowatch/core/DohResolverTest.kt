package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DohResolverTest {
    private val answer = """
        {"Status":0,"Answer":[
          {"name":"shareous1.dexcom.com","type":5,"TTL":60,"data":"shareous1.trafficmanager.net."},
          {"name":"shareous1.dexcom.com","type":1,"TTL":60,"data":"20.31.2.3"},
          {"name":"shareous1.dexcom.com","type":1,"TTL":60,"data":"20.31.2.4"}]}
    """.trimIndent()

    @Test
    fun `A records are taken and the CNAME row is skipped`() {
        assertEquals(listOf("20.31.2.3", "20.31.2.4"), DohResolver.parse(answer, DohResolver.A))
        assertEquals(emptyList(), DohResolver.parse(answer, DohResolver.AAAA))
    }

    @Test
    fun `a refusing or damaged answer resolves to nothing rather than throwing`() {
        assertEquals(emptyList(), DohResolver.parse("""{"Status":3}""", DohResolver.A))
        assertEquals(emptyList(), DohResolver.parse("not json", DohResolver.A))
        assertEquals(emptyList(), DohResolver.parse("""{"Status":0}""", DohResolver.A))
    }

    @Test
    fun `an answer that smuggles a name in place of an address is dropped`() {
        val hostile = """{"Status":0,"Answer":[{"type":1,"data":"attacker.example"},{"type":1,"data":"20.31.2.3"}]}"""
        assertEquals(listOf("20.31.2.3"), DohResolver.parse(hostile, DohResolver.A))
    }

    @Test
    fun `the resolver asks for both families and de-duplicates`() {
        val asked = mutableListOf<String>()
        val transport = HttpTransport { request ->
            asked += request.url
            HttpResponse(200, """{"Status":0,"Answer":[{"type":${request.url.substringAfter("type=")},"data":"20.31.2.3"}]}""")
        }
        val resolved = DohResolver("https://1.1.1.1/dns-query", transport).resolve("shareous1.dexcom.com")
        assertEquals(listOf("20.31.2.3"), resolved)
        assertEquals(2, asked.size)
        assertTrue(asked[0].endsWith("type=1") && asked[1].endsWith("type=28"))
        assertTrue(asked.all { it.startsWith("https://1.1.1.1/dns-query?name=shareous1.dexcom.com&") })
    }

    @Test
    fun `an endpoint that answers with an error contributes nothing`() {
        val resolved = DohResolver("https://1.1.1.1/dns-query", { HttpResponse(403, "blocked") }).resolve("host.example")
        assertEquals(emptyList(), resolved)
    }

    @Test
    fun `the default endpoints are IP literals, so they need no working DNS`() {
        DohResolver.PRESETS.forEach { (_, url) ->
            assertTrue(DohResolver.isAddress(url.removePrefix("https://").substringBefore('/')), url)
        }
    }
}
