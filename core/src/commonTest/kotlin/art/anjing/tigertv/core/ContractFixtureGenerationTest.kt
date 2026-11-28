package art.anjing.tigertv.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ContractFixtureGenerationTest {
    @Test
    fun generatedStringLiteralsPreserveEscapedCharacters() {
        assertEquals("\\\n\"\r\t${'$'}", contractFixtures.getValue("kotlin-literal.sample.txt"))
    }
}
