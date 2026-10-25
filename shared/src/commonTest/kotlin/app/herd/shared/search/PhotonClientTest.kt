package app.herd.shared.search

import app.herd.shared.Fixtures
import app.herd.shared.SeenRequest
import app.herd.shared.mockClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.geojson.Position

class PhotonClientTest {
  @Test
  fun searchSendsQueryAndBias() = runTest {
    val seen = mutableListOf<SeenRequest>()
    val photon = PhotonClient(mockClient(Fixtures.PHOTON_SEARCH_MORRO_BAY, seen = seen), "https://photon.test")

    photon.search("morro bay", near = Position(longitude = -119.7, latitude = 34.42), limit = 3)

    val url = seen.single().data.url
    assertEquals("photon.test", url.host)
    assertEquals("/api", url.encodedPath)
    assertEquals("morro bay", url.parameters["q"])
    assertEquals("3", url.parameters["limit"])
    assertEquals("34.42", url.parameters["lat"])
    assertEquals("-119.7", url.parameters["lon"])
  }

  @Test
  fun searchBuildsNamesAndSubtitles() = runTest {
    val photon = PhotonClient(mockClient(Fixtures.PHOTON_SEARCH_MORRO_BAY))

    val places = photon.search("morro bay")

    assertEquals(
      listOf(
        Place(
          name = "Morro Bay",
          subtitle = "San Luis Obispo, California",
          position = Position(longitude = -120.849901, latitude = 35.3658075),
          osmId = 5193369,
        ),
        Place(
          name = "Morro Bay State Park",
          subtitle = "Morro Bay, California",
          position = Position(longitude = -120.8260162, latitude = 35.3427682),
          osmId = 186175,
        ),
        Place(
          name = "Morro Bay Lane",
          subtitle = "Ventura, California",
          position = Position(longitude = -119.2139985, latitude = 34.2620524),
          osmId = 479210048,
        ),
      ),
      places,
    )
  }

  @Test
  fun reverseUsesStreetAddressWhenUnnamed() = runTest {
    val seen = mutableListOf<SeenRequest>()
    val photon = PhotonClient(mockClient(Fixtures.PHOTON_REVERSE_ADDRESS, seen = seen))

    val place = photon.reverse(Position(longitude = -119.6982, latitude = 34.4208))

    assertEquals("/reverse", seen.single().data.url.encodedPath)
    assertEquals("738 Anacapa Street", place?.name)
    assertEquals("Santa Barbara, California", place?.subtitle)
  }

  @Test
  fun emptyResults() = runTest {
    val photon = PhotonClient(mockClient(Fixtures.PHOTON_EMPTY))

    assertEquals(emptyList(), photon.search("zzzzqqq"))
    assertNull(photon.reverse(Position(longitude = 0.0, latitude = 0.0)))
  }

  @Test
  fun serverErrorThrows() = runTest {
    val photon = PhotonClient(mockClient("busy", HttpStatusCode.ServiceUnavailable))

    assertFailsWith<SearchException> { photon.search("morro bay") }
  }
}
