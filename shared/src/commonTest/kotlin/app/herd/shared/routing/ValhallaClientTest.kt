package app.herd.shared.routing

import app.herd.shared.Fixtures
import app.herd.shared.SeenRequest
import app.herd.shared.mockClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Position

class ValhallaClientTest {
  private val santaBarbara = Position(longitude = -119.6982, latitude = 34.4208)
  private val goleta = Position(longitude = -119.8276, latitude = 34.4358)

  @Test
  fun requestCarriesLocationsAndAvoidOptions() = runTest {
    val seen = mutableListOf<SeenRequest>()
    val valhalla =
      ValhallaClient(mockClient(Fixtures.VALHALLA_ROUTE_SANTA_BARBARA_GOLETA, seen = seen), "https://valhalla.test")

    valhalla.route(santaBarbara, goleta, RouteOptions(avoidHighways = true, avoidTolls = true))

    val request = seen.single()
    assertEquals(HttpMethod.Post, request.data.method)
    assertEquals("/route", request.data.url.encodedPath)
    val body = Json.parseToJsonElement(request.body).jsonObject
    assertEquals("auto", body.string("costing"))
    assertEquals("kilometers", body.string("units"))
    assertEquals("none", body.string("directions_type"))
    val locations = body["locations"]!!.jsonArray.map { it.jsonObject }
    assertEquals(34.4208, locations[0]["lat"]!!.jsonPrimitive.double)
    assertEquals(-119.8276, locations[1]["lon"]!!.jsonPrimitive.double)
    val auto = body["costing_options"]!!.jsonObject["auto"]!!.jsonObject
    assertEquals(0.0, auto["use_highways"]!!.jsonPrimitive.double)
    assertEquals(0.0, auto["use_tolls"]!!.jsonPrimitive.double)
  }

  @Test
  fun defaultOptionsLeaveValhallaDefaults() = runTest {
    val seen = mutableListOf<SeenRequest>()
    val valhalla = ValhallaClient(mockClient(Fixtures.VALHALLA_ROUTE_SANTA_BARBARA_GOLETA, seen = seen))

    valhalla.route(santaBarbara, goleta)

    val auto = Json.parseToJsonElement(seen.single().body).jsonObject["costing_options"]!!.jsonObject["auto"]!!.jsonObject
    assertFalse("use_highways" in auto)
    assertFalse("use_tolls" in auto)
  }

  @Test
  fun parsesRoute() = runTest {
    val valhalla = ValhallaClient(mockClient(Fixtures.VALHALLA_ROUTE_SANTA_BARBARA_GOLETA))

    val route = valhalla.route(santaBarbara, goleta)

    assertEquals(576, route.geometry.size)
    assertEquals(13_589.0, route.distanceMeters, absoluteTolerance = 0.01)
    assertEquals(1213.171, route.durationSeconds, absoluteTolerance = 0.001)
    assertEquals(-119.82759, route.bounds.west)
    assertEquals(34.417603, route.bounds.south)
    assertEquals(-119.696916, route.bounds.east)
    assertEquals(34.440642, route.bounds.north)
  }

  @Test
  fun errorResponseThrowsWithValhallaMessage() = runTest {
    val valhalla = ValhallaClient(mockClient(Fixtures.VALHALLA_ERROR_TOO_FAR, HttpStatusCode.BadRequest))

    val error = assertFailsWith<RoutingException> { valhalla.route(santaBarbara, goleta) }
    assertEquals("Path distance exceeds the max distance limit: 1500000 meters", error.message)
  }

  @Test
  fun nonJsonErrorThrowsGenericMessage() = runTest {
    val valhalla = ValhallaClient(mockClient("<html>Bad gateway</html>", HttpStatusCode.BadGateway))

    val error = assertFailsWith<RoutingException> { valhalla.route(santaBarbara, goleta) }
    assertEquals("Routing failed (HTTP 502)", error.message)
  }

  private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

  @Test
  fun passThroughPointsAreThroughAndEndsAreAlwaysStops() = runTest {
    val seen = mutableListOf<SeenRequest>()
    val valhalla = ValhallaClient(mockClient(Fixtures.VALHALLA_ROUTE_THREE_POINTS, seen = seen))
    val via = Position(longitude = -119.7110, latitude = 34.4262)
    val end = Position(longitude = -119.7240, latitude = 34.4300)

    valhalla.route(
      listOf(RoutePoint(santaBarbara, passThrough = true), RoutePoint(via, passThrough = true), RoutePoint(end, passThrough = true))
    )

    val types = Json.parseToJsonElement(seen.single().body).jsonObject["locations"]!!.jsonArray.map {
      it.jsonObject["type"]!!.jsonPrimitive.content
    }
    assertEquals(listOf("break", "through", "break"), types)
  }

  @Test
  fun parsesRouteThroughSeveralPoints() = runTest {
    val valhalla = ValhallaClient(mockClient(Fixtures.VALHALLA_ROUTE_THREE_POINTS))

    val route =
      valhalla.route(listOf(RoutePoint(santaBarbara), RoutePoint(Position(-119.7110, 34.4262), passThrough = true), RoutePoint(Position(-119.7240, 34.4300))))

    assertEquals(3_649.0, route.distanceMeters, absoluteTolerance = 0.5)
    assertEquals(508.129, route.durationSeconds, absoluteTolerance = 0.001)
    assertEquals(santaBarbara.latitude, route.geometry.first().latitude, absoluteTolerance = 0.001)
  }
}
