package app.herd.shared.search

import app.herd.shared.Fixtures
import app.herd.shared.SeenRequest
import app.herd.shared.mockClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.Position

class CensusGeocoderTest {
  @Test
  fun sendsOneLineAddress() = runTest {
    val seen = mutableListOf<SeenRequest>()
    val census = CensusGeocoder(mockClient(Fixtures.CENSUS_NO_MATCH, seen = seen), "https://census.test")

    census.locate("123 main st, ventura, ca")

    val url = seen.single().data.url
    assertEquals("/geocoder/locations/onelineaddress", url.encodedPath)
    assertEquals("123 main st, ventura, ca", url.parameters["address"])
    assertEquals("Public_AR_Current", url.parameters["benchmark"])
    assertEquals("json", url.parameters["format"])
  }

  @Test
  fun everyMatchingStreetIsACandidate() = runTest {
    val census = CensusGeocoder(mockClient(Fixtures.CENSUS_TWO_MATCHES))

    val places = census.locate("123 main st, ventura, ca")

    assertEquals(
      listOf(
        Place(
          name = "123 W Main St",
          subtitle = "Ventura, CA",
          position = Position(longitude = -119.302510777455, latitude = 34.281306762183),
          houseNumber = "123",
          street = "W Main St",
          locality = "Ventura, CA",
          isApproximate = true,
        ),
        Place(
          name = "123 E Main St",
          subtitle = "Ventura, CA",
          position = Position(longitude = -119.298233173103, latitude = 34.28085537205),
          houseNumber = "123",
          street = "E Main St",
          locality = "Ventura, CA",
          isApproximate = true,
        ),
      ),
      places,
    )
  }

  @Test
  fun multiWordCity() = runTest {
    val place = CensusGeocoder(mockClient(Fixtures.CENSUS_ONE_MATCH)).locate("1200 state st 93101").single()

    assertEquals("1200 State St", place.name)
    assertEquals("Santa Barbara, CA", place.subtitle)
  }

  @Test
  fun noMatchIsEmpty() = runTest {
    assertTrue(CensusGeocoder(mockClient(Fixtures.CENSUS_NO_MATCH)).locate("4123 xyzzy blvd, ventura, ca").isEmpty())
  }

  @Test
  fun serverErrorThrows() = runTest {
    val census = CensusGeocoder(mockClient("oops", HttpStatusCode.InternalServerError))

    assertFailsWith<SearchException> { census.locate("123 main st, ventura, ca") }
  }
}
