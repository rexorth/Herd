package app.herd.shared.net

/**
 * Base URLs of the map data services Herd calls. Moving to self-hosted Photon or Valhalla means
 * changing these, nothing else.
 *
 * Both are free public servers with fair-use rules: the app debounces search, cancels stale
 * requests, and never routes on every location update.
 */
object HerdServices {
  /** Komoot's public Photon instance: OpenStreetMap search and reverse geocoding. */
  const val PHOTON_BASE_URL = "https://photon.komoot.io"

  /** FOSSGIS's public Valhalla instance. Refuses routes longer than 1,500 km. */
  const val VALHALLA_BASE_URL = "https://valhalla1.openstreetmap.de"

  /** Identifies the app to the public servers, as their usage policies ask. */
  const val USER_AGENT = "Herd/0.2 (+https://github.com/rexorth/Herd)"
}
