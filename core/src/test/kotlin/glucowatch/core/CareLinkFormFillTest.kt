package glucowatch.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CareLinkFormFillTest {
    @Test
    fun `credentials stay inside javascript strings`() {
        val script = CareLinkFormFill.script("a</script>", "p\"a\\ss")
        assertTrue(script.contains(""""a</script>""""))
        assertTrue(script.contains(""""p\"a\\ss""""))
        assertFalse(script.contains("p\"a\\ss\""))
        assertTrue(script.contains("input#password"))
        assertTrue(script.contains("input[autocomplete=\"username\"]"))
    }
}
