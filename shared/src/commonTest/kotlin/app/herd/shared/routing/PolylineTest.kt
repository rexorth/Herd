package app.herd.shared.routing

import app.herd.shared.Fixtures
import app.herd.shared.net.HerdJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.Position

class PolylineTest {
  @Test
  fun decodesValhallaShape() {
    val shape =
      HerdJson.decodeFromString<ValhallaResponse>(Fixtures.VALHALLA_ROUTE_SANTA_BARBARA_GOLETA)
        .trip
        .legs
        .single()
        .shape

    val points = decodePolyline6(shape)

    // Expected values come from an independent decoder, not this one.
    assertEquals(576, points.size)
    assertEquals(Position(longitude = -119.698268, latitude = 34.420737), points.first())
    assertEquals(Position(longitude = -119.82759, latitude = 34.4359), points.last())
  }

  @Test
  fun decodesSinglePoint() {
    // Google's documented example point (38.5, -120.2), encoded at 6-digit precision.
    assertEquals(listOf(Position(longitude = -120.2, latitude = 38.5)), decodePolyline6("_izlhA~rlgdF"))
  }

  @Test
  fun emptyShapeHasNoPoints() {
    assertTrue(decodePolyline6("").isEmpty())
  }

  @Test
  fun truncatedShapeFails() {
    assertFailsWith<IllegalArgumentException> { decodePolyline6("_izlhA") }
  }

  @Test
  fun encodingRoundTrips() {
    val shape = decodePolyline6(Fixtures.VALHALLA_ROUTE_SANTA_BARBARA_GOLETA.substringAfter("\"shape\":\"").substringBefore("\"").replace("\\\\", "\\"))
    assertEquals(shape, decodePolyline6(encodePolyline6(shape)))
  }

  @Test
  fun encodesNegativeAndZeroSteps() {
    val points = listOf(Position(-119.698200, 34.420800), Position(-119.698200, 34.420800), Position(0.0, -0.000001))
    assertEquals(points, decodePolyline6(encodePolyline6(points)))
    assertEquals("", encodePolyline6(emptyList()))
  }
}
