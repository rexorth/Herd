package app.herd.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.herd.android.nav.Navigator
import app.herd.shared.db.openHerdStorage
import app.herd.shared.net.herdHttpClient
import app.herd.shared.plan.PlanEditor
import app.herd.shared.planner.RoutePlanner
import app.herd.shared.routing.ValhallaClient
import app.herd.shared.search.CensusGeocoder
import app.herd.shared.search.PhotonClient
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps search, routing, route planning and navigation alive across rotation, and owns the HTTP
 * client and the on-device database.
 */
class MapViewModel(application: Application) : AndroidViewModel(application) {
  private val http = herdHttpClient()
  private val storage = openHerdStorage(application)
  private val search = PhotonClient(http)
  private val router = ValhallaClient(http)

  val planner = RoutePlanner(viewModelScope, search, router, CensusGeocoder(http), storage.recentPlaces)

  val planEditor = PlanEditor(viewModelScope, router, storage.plannedRoutes, search)

  val navigator = Navigator(application, viewModelScope)

  init {
    // While a plan is open, places picked from search become its stops.
    viewModelScope.launch {
      planEditor.state.map { it != null }.distinctUntilChanged().collect { open ->
        planner.pickHandler = if (open) planEditor::addStop else null
      }
    }
  }

  override fun onCleared() {
    navigator.stop()
    storage.close()
    http.close()
  }
}
