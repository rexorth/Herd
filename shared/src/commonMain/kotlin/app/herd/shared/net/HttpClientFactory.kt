package app.herd.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * JSON settings for every service. Unknown response fields are ignored so server additions don't
 * break us; requests include default values but leave out nulls (unset options).
 */
internal val HerdJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

/** OkHttp on Android, NSURLSession (Darwin) on iOS. */
internal expect val platformEngine: HttpClientEngineFactory<*>

/**
 * The HTTP client the app shares across services. Closing it also closes the platform engine, so
 * close it when the screen that owns it goes away.
 */
fun herdHttpClient(): HttpClient = HttpClient(platformEngine) { herdDefaults() }

/** Same configuration over a given engine; tests pass a mock. */
internal fun herdHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { herdDefaults() }

internal fun HttpClientConfig<*>.herdDefaults() {
  install(ContentNegotiation) { json(HerdJson) }
  install(HttpTimeout) {
    connectTimeoutMillis = 10_000
    requestTimeoutMillis = 15_000
  }
  defaultRequest { headers.append(HttpHeaders.UserAgent, HerdServices.USER_AGENT) }
}
