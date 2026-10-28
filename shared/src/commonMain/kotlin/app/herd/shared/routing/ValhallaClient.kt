package app.herd.shared.routing

import app.herd.shared.net.HerdJson
import app.herd.shared.net.HerdServices
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Thrown when a route can't be computed. [message] is ready to show the user: Valhalla's own
 * explanation when it gives one ("No path could be found for input"), otherwise a generic one.
 */
class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A point a route visits. The first and last are always stops; in between, a [passThrough] point
 * only shapes the route (keep to this road), without an arrival there.
 */
data class RoutePoint(val position: Position, val passThrough: Boolean = false)

/** Computes driving routes. [ValhallaClient] is the real implementation. */
interface Router {
  /** A route through [points] (at least two), in order. Throws [RoutingException] when none can be found. */
  suspend fun route(points: List<RoutePoint>, options: RouteOptions = RouteOptions()): Route
}

/** A route straight from [from] to [to]. */
suspend fun Router.route(from: Position, to: Position, options: RouteOptions = RouteOptions()): Route =
  route(listOf(RoutePoint(from), RoutePoint(to)), options)

/**
 * Driving routes from a Valhalla server's `/route` endpoint. The public FOSSGIS server takes at most
 * [MAX_LOCATIONS] points per request.
 */
class ValhallaClient(
  private val http: HttpClient,
  private val baseUrl: String = HerdServices.VALHALLA_BASE_URL,
) : Router {
  override suspend fun route(points: List<RoutePoint>, options: RouteOptions): Route {
    require(points.size >= 2) { "A route needs at least two points" }
    val request =
      ValhallaRequest(
        locations =
          points.mapIndexed { i, point ->
            // Valhalla's "through" keeps to the road without stopping or turning around there.
            val passThrough = point.passThrough && i != 0 && i != points.lastIndex
            ValhallaLocation(point.position, if (passThrough) "through" else "break")
          },
        costingOptions = options.toCostingOptions(),
      )
    val response =
      http.post("$baseUrl/route") {
        contentType(ContentType.Application.Json)
        setBody(request)
      }
    // Valhalla reports problems (no road nearby, route too long) as a JSON error with a 4xx status.
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
      throw RoutingException(valhallaErrorMessage(text) ?: "Routing failed (HTTP ${response.status.value})")
    }
    val trip =
      try {
        HerdJson.decodeFromString<ValhallaResponse>(text).trip
      } catch (e: SerializationException) {
        throw RoutingException("Unexpected routing response", e)
      }
    return trip.toRoute()
  }
}

/** Valhalla's explanation in an error response ("No path could be found for input"), if it gave one. */
fun valhallaErrorMessage(responseBody: String): String? =
  runCatching { HerdJson.decodeFromString<ValhallaError>(responseBody).error }.getOrNull()

/** The most points the public FOSSGIS Valhalla server accepts in one request. */
const val MAX_LOCATIONS = 10

internal fun ValhallaTrip.toRoute(): Route {
  // Each leg's shape starts where the previous one ended; drop the repeated point.
  val geometry =
    legs.flatMapIndexed { i, leg -> decodePolyline6(leg.shape).let { if (i > 0) it.drop(1) else it } }
  if (geometry.size < 2) throw RoutingException("Route has no geometry")
  return Route(
    geometry = geometry,
    distanceMeters = summary.length * 1000.0,
    durationSeconds = summary.time,
    bounds =
      BoundingBox(
        west = summary.minLon ?: geometry.minOf { it.longitude },
        south = summary.minLat ?: geometry.minOf { it.latitude },
        east = summary.maxLon ?: geometry.maxOf { it.longitude },
        north = summary.maxLat ?: geometry.maxOf { it.latitude },
      ),
  )
}

/** Valhalla's preference scale runs 0 to 1; 0 avoids a road type as much as possible. */
internal fun RouteOptions.toCostingOptions() =
  ValhallaCostingOptions(
    ValhallaAutoOptions(
      useHighways = if (avoidHighways) 0.0 else null,
      useTolls = if (avoidTolls) 0.0 else null,
    )
  )

@Serializable
internal class ValhallaRequest(
  val locations: List<ValhallaLocation>,
  val costing: String = "auto",
  @SerialName("costing_options") val costingOptions: ValhallaCostingOptions,
  val units: String = "kilometers",
  // The preview only draws the line. Navigation asks for its own route with instructions
  // (valhallaNavigationOptions).
  @SerialName("directions_type") val directionsType: String = "none",
)

@Serializable
internal class ValhallaLocation(val lat: Double, val lon: Double, val type: String = "break") {
  constructor(position: Position, type: String) : this(position.latitude, position.longitude, type)
}

@Serializable internal class ValhallaCostingOptions(val auto: ValhallaAutoOptions)

@Serializable
internal class ValhallaAutoOptions(
  @SerialName("use_highways") val useHighways: Double? = null,
  @SerialName("use_tolls") val useTolls: Double? = null,
)

@Serializable internal class ValhallaResponse(val trip: ValhallaTrip)

@Serializable
internal class ValhallaTrip(val legs: List<ValhallaLeg>, val summary: ValhallaSummary)

@Serializable internal class ValhallaLeg(val shape: String)

@Serializable
internal class ValhallaSummary(
  val length: Double,
  val time: Double,
  @SerialName("min_lat") val minLat: Double? = null,
  @SerialName("min_lon") val minLon: Double? = null,
  @SerialName("max_lat") val maxLat: Double? = null,
  @SerialName("max_lon") val maxLon: Double? = null,
)

@Serializable internal class ValhallaError(val error: String? = null)
