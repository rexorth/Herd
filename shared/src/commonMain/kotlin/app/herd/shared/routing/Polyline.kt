package app.herd.shared.routing

import org.maplibre.spatialk.geojson.Position

/**
 * Decodes an encoded polyline with 6 digits of precision, the format Valhalla returns route shapes
 * in. Same algorithm as Google's encoded polylines, which use 5 digits.
 */
fun decodePolyline6(encoded: String): List<Position> {
  val positions = ArrayList<Position>(encoded.length / 4)
  var index = 0
  var lat = 0L
  var lon = 0L
  while (index < encoded.length) {
    lat += readSignedValue(encoded, index).also { index = it.second }.first
    lon += readSignedValue(encoded, index).also { index = it.second }.first
    positions += Position(longitude = lon / 1e6, latitude = lat / 1e6)
  }
  return positions
}

/** Reads one zig-zag encoded value starting at [start]. Returns the value and the next index. */
private fun readSignedValue(encoded: String, start: Int): Pair<Long, Int> {
  var index = start
  var result = 0L
  var shift = 0
  do {
    require(index < encoded.length) { "Truncated polyline" }
    val chunk = encoded[index++].code - 63
    result = result or ((chunk and 0x1f).toLong() shl shift)
    shift += 5
  } while (chunk >= 0x20)
  val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
  return value to index
}
