package app.herd.shared.search

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.maplibre.spatialk.geojson.Position

/** A typed street address: "123 main st, ventura" → house number "123", street "main st". */
internal data class TypedAddress(val houseNumber: String, val street: String) {
  /** The word that identifies the street: "main" in "W Main St", skipping directions. */
  val keyStreetWord: String? =
    street.lowercase().split(' ', '.').firstOrNull { it.isNotEmpty() && it !in DIRECTIONS }
}

private val DIRECTIONS =
  setOf("n", "s", "e", "w", "ne", "nw", "se", "sw", "north", "south", "east", "west")

private val TYPED_ADDRESS = Regex("""^\s*(\d+[A-Za-z]?)\s+([^,]+)""")
private val ZIP_CODE = Regex("""\b\d{5}(-\d{4})?\b""")

/** "1200 state st", "421 San Andres": queries that start with a house number. */
internal fun isStreetAddress(query: String): Boolean = query.trimStart().firstOrNull()?.isDigit() == true

internal fun parseStreetAddress(query: String): TypedAddress? =
  TYPED_ADDRESS.find(query)?.let { TypedAddress(it.groupValues[1], it.groupValues[2].trim()) }

/** Whether the query already says where: a city after a comma, or a ZIP code after the street. */
internal fun hasLocality(query: String): Boolean {
  if (',' in query) return true
  val afterHouseNumber = query.trimStart().substringAfter(' ', "")
  return ZIP_CODE.containsMatchIn(afterHouseNumber)
}

/** The town typed after the street: "ventura" in "123 main st, ventura, ca"; null if none. */
internal fun typedTown(query: String): String? =
  query.split(',').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

/** Great-circle distance in kilometers. */
internal fun distanceKm(a: Position, b: Position): Double {
  val earthRadiusKm = 6371.0
  val dLat = (b.latitude - a.latitude) * PI / 180
  val dLon = (b.longitude - a.longitude) * PI / 180
  val h =
    sin(dLat / 2).pow(2) +
      cos(a.latitude * PI / 180) * cos(b.latitude * PI / 180) * sin(dLon / 2).pow(2)
  return 2 * earthRadiusKm * asin(sqrt(h))
}

/** Whether this place is the typed house on the typed street, not just a similar address. */
internal fun Place.isAddress(typed: TypedAddress): Boolean {
  val word = typed.keyStreetWord ?: return false
  return houseNumber.equals(typed.houseNumber, ignoreCase = true) &&
    street?.lowercase()?.split(' ', '.')?.contains(word) == true
}
