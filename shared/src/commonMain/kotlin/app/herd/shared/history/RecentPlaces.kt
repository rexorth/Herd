package app.herd.shared.history

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.herd.shared.db.RecentPlaceQueries
import app.herd.shared.db.Recent_place
import app.herd.shared.search.Place
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.Position

/** Destinations the user picked from search, so they can pick them again without typing. */
interface RecentPlaceStore {
  /** The most recent places, newest first. Emits again whenever the list changes. */
  val recent: Flow<List<Place>>

  /** Adds [place], or moves it to the top if it's already there. */
  suspend fun record(place: Place)

  suspend fun remove(place: Place)
}

/** [RecentPlaceStore] in the on-device database. Shows [SHOWN] places and keeps [KEPT]. */
internal class SqlRecentPlaces(
  private val queries: RecentPlaceQueries,
  private val io: CoroutineContext,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : RecentPlaceStore {
  override val recent: Flow<List<Place>> =
    queries.latest(SHOWN.toLong()).asFlow().mapToList(io).map { rows -> rows.map { it.toPlace() } }

  override suspend fun record(place: Place) {
    withContext(io) {
      queries.transaction {
        queries.upsert(place.toRow(now()))
        queries.keepNewest(KEPT.toLong())
      }
    }
  }

  override suspend fun remove(place: Place) {
    withContext(io) { queries.delete(place.recentKey) }
  }

  companion object {
    const val SHOWN = 8
    const val KEPT = 25
  }
}

/**
 * The same place picked twice should be one entry. OpenStreetMap places have a stable ID; anything
 * else (addresses from the Census geocoder) matches by name and position to about a meter.
 */
internal val Place.recentKey: String
  get() =
    osmId?.let { "osm:$it" }
      ?: "$name@${(position.latitude * 1e5).roundToLong()},${(position.longitude * 1e5).roundToLong()}"

private fun Place.toRow(usedAt: Long) =
  Recent_place(
    key = recentKey,
    name = name,
    subtitle = subtitle,
    latitude = position.latitude,
    longitude = position.longitude,
    osm_id = osmId,
    house_number = houseNumber,
    street = street,
    locality = locality,
    is_approximate = if (isApproximate) 1L else 0L,
    used_at = usedAt,
  )

private fun Recent_place.toPlace() =
  Place(
    name = name,
    subtitle = subtitle,
    position = Position(longitude = longitude, latitude = latitude),
    osmId = osm_id,
    houseNumber = house_number,
    street = street,
    locality = locality,
    isApproximate = is_approximate != 0L,
  )
