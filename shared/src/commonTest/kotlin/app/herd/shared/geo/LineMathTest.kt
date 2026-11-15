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

  // Hoover north for about 222 m, then east along Adams for about 415 m.
  private val lShape =
    MeasuredLine(listOf(Position(-118.2845, 34.0300), Position(-118.2845, 34.0320), Position(-118.2800, 34.0320)))

  @Test
  fun measuresAlongTheLine() {
    assertEquals(637.0, lShape.length, absoluteTolerance = 1.0)
    val corner = lShape.pointAt(222.4)
    assertEquals(-118.2845, corner.longitude, absoluteTolerance = 1e-6)
    assertEquals(34.0320, corner.latitude, absoluteTolerance = 1e-6)
    assertEquals(34.0310, lShape.pointAt(111.2).latitude, absoluteTolerance = 1e-5)
    assertEquals(lShape.points.last(), lShape.pointAt(1e9))
  }

  @Test
  fun nearestReportsHowFarAlongAndHowFarAway() {
    // 46 m east of Hoover, halfway up the block.
    val match = lShape.nearest(Position(-118.2840, 34.0310))!!
    assertEquals(111.2, match.along, absoluteTolerance = 1.0)
    assertEquals(46.1, match.distance, absoluteTolerance = 0.5)
  }

  @Test
  fun nearestCanBeLimitedToAStretch() {
    val nearHoover = Position(-118.2846, 34.0310)
    // Searching only along Adams finds its start, the corner.
    val ahead = lShape.nearest(nearHoover, fromAlong = 300.0)!!
    assertEquals(300.0, ahead.along, absoluteTolerance = 1.0)
    assertEquals(null, lShape.nearest(nearHoover, fromAlong = 500.0, toAlong = 400.0))
  }

  @Test
  fun slicesBetweenDistances() {
    val slice = lShape.slice(111.2, 400.0)
    assertEquals(34.0310, slice.first().latitude, absoluteTolerance = 1e-5)
    assertEquals(lShape.points[1], slice[1])
    assertEquals(3, slice.size)
  }
}
