package app.herd.shared.search

import org.maplibre.spatialk.geojson.Position

/**
 * A searchable place: a town, a business, an address, or a pin the user dropped.
 *
 * [subtitle] is the location context shown under the name ("Morro Bay, California"), or null when
 * there is none. [houseNumber] and [street] are set when the place is a street address; [locality]
 * is its town and state ("Santa Barbara, California"). [isApproximate] marks positions estimated
 * along a street rather than taken from a mapped building.
 */
data class Place(
  val name: String,
  val subtitle: String?,
  val position: Position,
  val osmId: Long? = null,
  val houseNumber: String? = null,
  val street: String? = null,
  val locality: String? = null,
  val isApproximate: Boolean = false,
)
