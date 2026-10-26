package app.herd.shared.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual val platformEngine: HttpClientEngineFactory<*> = Darwin
