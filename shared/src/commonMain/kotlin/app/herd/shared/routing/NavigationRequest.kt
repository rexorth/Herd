package app.herd.shared.routing

import app.herd.shared.net.HerdJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Units for spoken and written directions ("In 900 feet" or "In 300 meters"). */
enum class DistanceUnits(internal val valhallaName: String) {
  Miles("miles"),
  Kilometers("kilometers"),
}

/** Countries whose road signs use miles. */
private val MILES_COUNTRIES = setOf("US", "GB", "LR", "MM")

/** [countryCode] is the device's region, such as "US". Unknown regions get kilometers. */
fun distanceUnitsFor(countryCode: String?): DistanceUnits =
  if (countryCode?.uppercase() in MILES_COUNTRIES) DistanceUnits.Miles else DistanceUnits.Kilometers

/**
 * The fields Herd adds to a turn-by-turn route request, as a JSON object. The navigation library
 * (Ferrostar) builds the rest: the OSRM response format with banner and voice instructions, and
 * the locations. Its reroutes reuse these, so they keep the trip's avoid settings.
 *
 * Directions are in English, like the rest of the app.
 */
fun valhallaNavigationOptions(options: RouteOptions, units: DistanceUnits): String =
  HerdJson.encodeToString(
    ValhallaNavigationOptions(
      units = units.valhallaName,
      language = NAVIGATION_LANGUAGE,
      costingOptions = options.toCostingOptions(),
    )
  )

private const val NAVIGATION_LANGUAGE = "en-US"

@Serializable
internal class ValhallaNavigationOptions(
  val units: String,
  val language: String,
  @SerialName("costing_options") val costingOptions: ValhallaCostingOptions,
)
