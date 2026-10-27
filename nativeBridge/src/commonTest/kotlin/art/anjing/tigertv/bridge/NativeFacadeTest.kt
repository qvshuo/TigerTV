package art.anjing.tigertv.bridge

import art.anjing.tigertv.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeFacadeTest {
    @Test fun resultsAreConsumedAndCancelledRequestsAreReleased() = runBlocking {
        val store = object : Storage {
            private val files = mutableMapOf<String, String>()
            override fun read(path: String) = files[path]
            override fun writeAtomic(path: String, text: String) { files[path] = text }
        }
        val facade = NativeFacade(CoreService("cache", storage = store))
        try {
            val save = facade.begin("""{"version":1,"operation":"saveHistory","items":["逐玉🎬"]}""")
            withTimeout(5000) { while (facade.takeResult(save) == null) delay(10) }
            val id = facade.begin("""{"version":1,"operation":"history"}""")
            val result = withTimeout(5000) {
                var text: String? = null
                while (text == null) { text = facade.takeResult(id); if (text == null) delay(10) }
                text
            }
            assertTrue(result.contains("逐玉🎬"))
            assertNull(facade.takeResult(id))
            val cancelled = facade.begin("""{"version":1,"operation":"history"}""")
            facade.cancel(cancelled); delay(30)
            assertNull(facade.takeResult(cancelled))
            val invalid = facade.begin("""{"version":99,"operation":"history"}""")
            val error = withTimeout(5000) {
                var text: String? = null
                while (text == null) { text = facade.takeResult(invalid); if (text == null) delay(10) }
                text
            }
            assertTrue(error.contains("error"))
        } finally { facade.close() }
    }
}
