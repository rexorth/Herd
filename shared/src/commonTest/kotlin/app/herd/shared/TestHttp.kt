package app.herd.shared

import app.herd.shared.net.herdHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** A request the mock server saw, with its body read as text. */
internal class SeenRequest(val data: HttpRequestData, val body: String)

/** An [HttpClient] with Herd's real configuration that answers every request with [body]. */
internal fun mockClient(
  body: String,
  status: HttpStatusCode = HttpStatusCode.OK,
  seen: MutableList<SeenRequest> = mutableListOf(),
): HttpClient =
  herdHttpClient(
    MockEngine { request ->
      seen += SeenRequest(request, request.body.toByteArray().decodeToString())
      respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
  )
