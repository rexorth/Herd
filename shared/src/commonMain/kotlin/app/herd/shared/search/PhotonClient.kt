package app.herd.shared.search

import app.herd.shared.net.HerdServices
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position

/** Finds places by name and by position. [PhotonClient] is the real implementation. */
interface PlaceSearch {
  suspend fun search(query: String, near: Position? = null, limit: Int = 6): List<Place>

  suspend fun reverse(position: Position): Place?
}

/** Thrown when the search server can't be reached or answers with an error. */
class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Search and reverse geocoding against a Photon server.
 *
 * Photon answers with GeoJSON: one point feature per result, with OpenStreetMap address fields as
 * properties.
 */
class PhotonClient(
  private val http: HttpClient,
  private val baseUrl: String = HerdServices.PHOTON_BASE_URL,
) : PlaceSearch {
  /** Places matching [query], ranked closer to [near] when given. */
  override suspend fun search(query: String, near: Position?, limit: Int): List<Place> {
    val response =
      http.get("$baseUrl/api") {
        parameter("q", query)
        parameter("limit", limit)
        parameter("lang", "en")
        near?.let {
          parameter("lat", it.latitude)
          parameter("lon", it.longitude)
          // Photon's default bias lets prominence win, which is right for "Las Vegas" or
          // "Portland" but puts "123 Main St" in another country. Street addresses lean on distance.
          if (isStreetAddress(query)) {
            parameter("zoom", ADDRESS_BIAS_ZOOM)
            parameter("location_bias_scale", ADDRESS_BIAS_SCALE)
          }
        }
      }
    return response.places()
  }

  /** The nearest named place or address to [position], or null if Photon knows nothing there. */
  override suspend fun reverse(position: Position): Place? {
    val response =
      http.get("$baseUrl/reverse") {
        parameter("lat", position.latitude)
        parameter("lon", position.longitude)
        parameter("limit", 1)
        parameter("lang", "en")
      }
    return response.places().firstOrNull()
  }

  private companion object {
    /** Bias radius for address searches, in map-zoom terms: wider than Photon's default of 12. */
    const val ADDRESS_BIAS_ZOOM = 10

    /** How much prominence still counts for address searches: 0 ignores it, Photon's default is 0.4. */
    const val ADDRESS_BIAS_SCALE = 0.2
  }

  private suspend fun HttpResponse.places(): List<Place> {
    if (!status.isSuccess()) throw SearchException("Search failed (HTTP ${status.value})")
    return body<PhotonResponse>().features.mapNotNull { it.toPlace() }
  }
}

internal fun PhotonFeature.toPlace(): Place? {
  val coordinates = geometry.coordinates
  if (coordinates.size < 2) return null
  val p = properties
  val streetAddress = listOfNotNull(p.housenumber, p.street).joinToString(" ").ifEmpty { null }
  val name = p.name ?: streetAddress ?: p.city ?: return null
  // Context under the name: the street (when the name isn't already the address), then the area.
  val subtitle =
    listOfNotNull(
        streetAddress.takeIf { p.name != null },
        (p.city ?: p.county).takeIf { it != name },
        p.state.takeIf { it != name },
      )
      .joinToString(", ")
      .ifEmpty { null }
  return Place(
    name = name,
    subtitle = subtitle,
    position = Position(longitude = coordinates[0], latitude = coordinates[1]),
    osmId = p.osmId,
    houseNumber = p.housenumber,
    street = p.street,
    locality = listOfNotNull(p.city ?: p.county, p.state).joinToString(", ").ifEmpty { null },
  )
}

@Serializable internal class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal class PhotonFeature(val geometry: PhotonGeometry, val properties: PhotonProperties)

@Serializable internal class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal class PhotonProperties(
  val name: String? = null,
  val housenumber: String? = null,
  val street: String? = null,
  val city: String? = null,
  val county: String? = null,
  val state: String? = null,
  @SerialName("osm_id") val osmId: Long? = null,
)
