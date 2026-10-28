package app.herd.shared.routing

import kotlin.math.round
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

/** Encodes [positions] as a polyline with 6 digits of precision; the inverse of [decodePolyline6]. */
fun encodePolyline6(positions: List<Position>): String {
  val out = StringBuilder(positions.size * 8)
  var lastLat = 0L
  var lastLon = 0L
  for (position in positions) {
    val lat = round(position.latitude * 1e6).toLong()
    val lon = round(position.longitude * 1e6).toLong()
    writeSignedValue(out, lat - lastLat)
    writeSignedValue(out, lon - lastLon)
    lastLat = lat
    lastLon = lon
  }
  return out.toString()
}

private fun writeSignedValue(out: StringBuilder, value: Long) {
  var rest = if (value < 0) (value shl 1).inv() else value shl 1
  while (rest >= 0x20) {
    out.append(((0x20 or (rest and 0x1f).toInt()) + 63).toChar())
    rest = rest shr 5
  }
  out.append((rest.toInt() + 63).toChar())
}
