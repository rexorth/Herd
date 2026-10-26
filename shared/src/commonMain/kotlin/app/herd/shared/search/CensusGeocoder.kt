package app.herd.shared.search

import app.herd.shared.net.HerdServices
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position

/** Finds a full street address that the main search doesn't know. */
interface AddressLocator {
  /** All streets [address] could be on; empty when none matched. */
  suspend fun locate(address: String): List<Place>
}

/**
 * The US Census Bureau's geocoder. It knows the house-number range of every street segment in
 * the US (TIGER data) and places a number proportionally along its segment, so it finds houses
 * OpenStreetMap hasn't mapped. Positions are estimates, typically within a few houses.
 *
 * It needs a town or ZIP code in the address, has no autocomplete, and covers the US only.
 */
class CensusGeocoder(
  private val http: HttpClient,
  private val baseUrl: String = HerdServices.CENSUS_GEOCODER_BASE_URL,
) : AddressLocator {
  override suspend fun locate(address: String): List<Place> {
    val response =
      http.get("$baseUrl/geocoder/locations/onelineaddress") {
        parameter("address", address)
        parameter("benchmark", "Public_AR_Current")
        parameter("format", "json")
      }
    if (!response.status.isSuccess()) {
      throw SearchException("Address lookup failed (HTTP ${response.status.value})")
    }
    return response.body<CensusResponse>().result.addressMatches.mapNotNull { it.toPlace() }
  }
}

/** "123 W MAIN ST, VENTURA, CA, 93001" → "123 W Main St" in "Ventura, CA". */
internal fun CensusMatch.toPlace(): Place? {
  val parts = matchedAddress.split(",").map { it.trim() }
  val streetAddress = parts.firstOrNull()?.let(::titleCaseAddress) ?: return null
  val houseNumber = streetAddress.substringBefore(' ')
  val locality = parts.drop(1).take(2).filter { it.isNotEmpty() }.joinToString(", ") { titleCaseCity(it) }
  return Place(
    name = streetAddress,
    subtitle = locality.ifEmpty { null },
    position = Position(longitude = coordinates.x, latitude = coordinates.y),
    houseNumber = houseNumber,
    street = streetAddress.substringAfter(' ', ""),
    locality = locality.ifEmpty { null },
    isApproximate = true,
  )
}

/** Census returns upper case: "123 W MAIN ST" → "123 W Main St". Compass directions stay upper. */
private fun titleCaseAddress(text: String): String =
  text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
    if (word in COMPASS) word else capitalize(word)
  }

/** "SANTA BARBARA" → "Santa Barbara"; two-letter state codes stay upper. */
private fun titleCaseCity(text: String): String =
  if (text.length == 2) text else text.split(' ').joinToString(" ", transform = ::capitalize)

private fun capitalize(word: String) = word.lowercase().replaceFirstChar { it.uppercase() }

private val COMPASS = setOf("N", "S", "E", "W", "NE", "NW", "SE", "SW")

@Serializable internal class CensusResponse(val result: CensusResult = CensusResult())

@Serializable internal class CensusResult(val addressMatches: List<CensusMatch> = emptyList())

@Serializable
internal class CensusMatch(val matchedAddress: String, val coordinates: CensusCoordinates)

@Serializable internal class CensusCoordinates(val x: Double, val y: Double)
