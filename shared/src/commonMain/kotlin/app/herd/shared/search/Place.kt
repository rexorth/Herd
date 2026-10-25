package app.herd.shared.search

import org.maplibre.spatialk.geojson.Position

/**
 * A searchable place: a town, a business, an address, or a pin the user dropped.
 *
 * [subtitle] is the location context shown under the name ("Morro Bay, California"), or null when
 * there is none.
 */
data class Place(
  val name: String,
  val subtitle: String?,
  val position: Position,
  val osmId: Long? = null,
)
