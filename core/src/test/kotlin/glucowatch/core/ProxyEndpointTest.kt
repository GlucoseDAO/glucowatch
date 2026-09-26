package glucowatch.core

import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProxyEndpointTest {
    @Test
    fun `accepts only an explicit HTTP CONNECT host and port`() {
        assertEquals(ProxyEndpoint("proxy.example", 8080), ProxyEndpoint.parse("proxy.example:8080"))
        assertEquals(ProxyEndpoint("proxy.example", 8080), ProxyEndpoint.parse("http://proxy.example:8080/"))
        assertEquals(Proxy.Type.HTTP, ProxyEndpoint.parse("proxy.example:8080").javaProxy().type())
        listOf("https://proxy.example:443", "http://user:pass@proxy.example:8080", "proxy.example", "proxy.example:99999",
            "proxy.example:8080/path", "proxy.example:8080?x=1").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ProxyEndpoint.parse(it) }
        }
    }
}
