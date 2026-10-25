package app.herd.shared

import org.maplibre.spatialk.geojson.Position

/** Map settings shared by the Android and iOS apps. */
object MapDefaults {
  /**
   * OpenFreeMap's "liberty" style: free OpenStreetMap vector tiles, no API key. The style JSON
   * points MapLibre at the tiles, fonts and icons. Swapping tile hosts later means changing this URL.
   */
  const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

  /** Camera before the first location fix: all of California. */
  val initialCenter = Position(longitude = -119.4, latitude = 36.8)
  const val INITIAL_ZOOM = 5.0

  /** Zoom used when the map jumps to the user's location. About a neighborhood across. */
  const val USER_ZOOM = 15.0
}
