package art.anjing.tigertv.core

import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

// HTTP handlers and operation deadlines must use the same virtual clock.
fun TestScope.testEngine(handler: MockRequestHandler): MockEngine = MockEngine(MockEngineConfig().apply {
    dispatcher = StandardTestDispatcher(testScheduler)
    addHandler(handler)
})
