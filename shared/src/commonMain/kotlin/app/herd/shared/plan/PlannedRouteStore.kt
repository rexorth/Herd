package app.herd.shared.plan

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.herd.shared.db.Plan_point
import app.herd.shared.db.PlannedRouteQueries
import app.herd.shared.routing.Route
import app.herd.shared.routing.RouteOptions
import app.herd.shared.routing.decodePolyline6
import app.herd.shared.routing.encodePolyline6
import app.herd.shared.search.Place
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** A saved route as the list of saved routes shows it. */
data class PlannedRouteSummary(
  val id: Long,
  val name: String,
  val distanceMeters: Double,
  val durationSeconds: Double,
  val pointCount: Int,
)

/** Planned routes kept on the phone. */
interface PlannedRouteStore {
  /** Every saved route, most recently saved first. Emits again whenever the list changes. */
  val summaries: Flow<List<PlannedRouteSummary>>

  /** Saves [plan] (a new one if its id is null) and returns its id. */
  suspend fun save(plan: PlannedRoute): Long

  suspend fun load(id: Long): PlannedRoute?

  suspend fun delete(id: Long)
}

/** [PlannedRouteStore] in the on-device database. */
internal class SqlPlannedRoutes(
  private val queries: PlannedRouteQueries,
  private val io: CoroutineContext,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PlannedRouteStore {
  override val summaries: Flow<List<PlannedRouteSummary>> =
    queries.summaries().asFlow().mapToList(io).map { rows ->
      rows.map { PlannedRouteSummary(it.id, it.name, it.distance_m, it.duration_s, it.point_count.toInt()) }
    }

  override suspend fun save(plan: PlannedRoute): Long =
    withContext(io) {
      queries.transactionWithResult {
        val geometry = encodePolyline6(plan.route.geometry)
        val highways = plan.options.avoidHighways.toLong()
        val tolls = plan.options.avoidTolls.toLong()
        val id =
          plan.id?.also {
            queries.updateRoute(plan.name, highways, tolls, geometry, plan.route.distanceMeters, plan.route.durationSeconds, now(), it)
            queries.deletePoints(it)
          }
            ?: run {
              queries.insertRoute(plan.name, highways, tolls, geometry, plan.route.distanceMeters, plan.route.durationSeconds, now())
              queries.lastInsertedId().executeAsOne()
            }
        plan.points.forEachIndexed { i, point -> queries.insertPoint(point.toRow(id, i)) }
        id
      }
    }

  override suspend fun load(id: Long): PlannedRoute? =
    withContext(io) {
      val row = queries.route(id).executeAsOneOrNull() ?: return@withContext null
      val geometry = decodePolyline6(row.geometry)
      PlannedRoute(
        id = row.id,
        name = row.name,
        points = queries.points(id).executeAsList().map { it.toPlanPoint() },
        options = RouteOptions(avoidHighways = row.avoid_highways != 0L, avoidTolls = row.avoid_tolls != 0L),
        route = Route(geometry, row.distance_m, row.duration_s, boundsOf(geometry)),
      )
    }

  override suspend fun delete(id: Long) {
    withContext(io) {
      queries.transaction {
        queries.deletePoints(id)
        queries.deleteRoute(id)
      }
    }
  }
}

private fun Boolean.toLong() = if (this) 1L else 0L

private fun PlanPoint.toRow(routeId: Long, position: Int) =
  Plan_point(
    route_id = routeId,
    position = position.toLong(),
    name = place.name,
    subtitle = place.subtitle,
    latitude = place.position.latitude,
    longitude = place.position.longitude,
    pass_through = passThrough.toLong(),
  )

private fun Plan_point.toPlanPoint() =
  PlanPoint(
    Place(name = name, subtitle = subtitle, position = Position(longitude = longitude, latitude = latitude)),
    passThrough = pass_through != 0L,
  )

private fun boundsOf(geometry: List<Position>) =
  BoundingBox(
    west = geometry.minOf { it.longitude },
    south = geometry.minOf { it.latitude },
    east = geometry.maxOf { it.longitude },
    north = geometry.maxOf { it.latitude },
  )
