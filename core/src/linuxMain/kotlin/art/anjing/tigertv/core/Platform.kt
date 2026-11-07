package art.anjing.tigertv.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

actual fun platformHttpClient(): HttpClient = configureClient(HttpClient(Curl))
