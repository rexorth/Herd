package app.herd.shared.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual val platformEngine: HttpClientEngineFactory<*> = OkHttp
