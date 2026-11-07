package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpClient(): HttpClient = configureClient(HttpClient(Darwin))
