package app.herd.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.spatialk.geojson.Position

class LocationSmoothingTest {
  @Test
  fun glidesForTheGapBetweenFixes() {
    assertEquals(900.milliseconds, smoothingDuration(900.milliseconds))
  }

  @Test
  fun clampsVeryShortAndVeryLongGaps() {
    assertEquals(250.milliseconds, smoothingDuration(50.milliseconds))
    // Coming out of a tunnel: jump most of the way rather than crawl for 20 seconds.
    assertEquals(1500.milliseconds, smoothingDuration(20.seconds))
  }

  @Test
  fun firstFixUsesTheUsualInterval() {
    assertEquals(1.seconds, smoothingDuration(null))
  }

  @Test
  fun positionMovesInAStraightLine() {
    val from = Position(longitude = -119.70, latitude = 34.40)
    val to = Position(longitude = -119.68, latitude = 34.44)
    val quarter = interpolatePosition(from, to, 0.25)
    assertEquals(-119.695, quarter.longitude, absoluteTolerance = 1e-9)
    assertEquals(34.41, quarter.latitude, absoluteTolerance = 1e-9)
    assertEquals(from.longitude, interpolatePosition(from, to, 0.0).longitude, absoluteTolerance = 1e-9)
    assertEquals(to.latitude, interpolatePosition(from, to, 1.0).latitude, absoluteTolerance = 1e-9)
  }

  @Test
  fun positionCrossesTheAntimeridianTheShortWay() {
    val halfway = interpolatePosition(Position(179.9, 0.0), Position(-179.9, 0.0), 0.5)
    assertEquals(180.0, kotlin.math.abs(halfway.longitude), absoluteTolerance = 1e-9)
  }

  @Test
  fun bearingTurnsTheShortWay() {
    assertEquals(0.0, interpolateBearing(350.0, 10.0, 0.5), absoluteTolerance = 1e-9)
    assertEquals(355.0, interpolateBearing(10.0, 340.0, 0.5), absoluteTolerance = 1e-9)
    assertEquals(45.0, interpolateBearing(0.0, 90.0, 0.5), absoluteTolerance = 1e-9)
    assertEquals(90.0, interpolateBearing(0.0, 90.0, 1.0), absoluteTolerance = 1e-9)
  }
}
