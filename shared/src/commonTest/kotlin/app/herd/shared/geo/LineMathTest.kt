package app.herd.shared.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.Position

class LineMathTest {
  // Hoover Street, running north for about 220 m.
  private val hoover = listOf(Position(-118.2845, 34.0300), Position(-118.2845, 34.0320))

  @Test
  fun pointOnTheLineIsZero() {
    assertEquals(0.0, distanceToLineMeters(Position(-118.2845, 34.0310), hoover), absoluteTolerance = 0.01)
  }

  @Test
  fun measuresStraightAcrossToTheLine() {
    // 0.001° of longitude at 34° N is about 92 m.
    assertEquals(92.2, distanceToLineMeters(Position(-118.2835, 34.0310), hoover), absoluteTolerance = 0.5)
  }

  @Test
  fun pastTheEndMeasuresToTheEndPoint() {
    // 0.001° of latitude past the north end: about 111 m.
    assertEquals(111.2, distanceToLineMeters(Position(-118.2845, 34.0330), hoover), absoluteTolerance = 0.5)
  }

  @Test
  fun singlePointAndEmptyLines() {
    assertEquals(111.2, distanceToLineMeters(Position(-118.2845, 34.0310), listOf(Position(-118.2845, 34.0300))), absoluteTolerance = 0.5)
    assertTrue(distanceToLineMeters(Position(0.0, 0.0), emptyList()).isInfinite())
  }

  @Test
  fun linesAcrossTheAntimeridian() {
    val line = listOf(Position(179.999, 0.0), Position(-179.999, 0.0))
    assertEquals(0.0, distanceToLineMeters(Position(180.0, 0.0), line), absoluteTolerance = 0.01)
  }
}
