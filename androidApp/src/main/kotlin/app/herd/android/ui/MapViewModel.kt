package app.herd.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.herd.android.nav.Navigator
import app.herd.shared.db.openHerdStorage
import app.herd.shared.net.herdHttpClient
import app.herd.shared.planner.RoutePlanner
import app.herd.shared.routing.ValhallaClient
import app.herd.shared.search.CensusGeocoder
import app.herd.shared.search.PhotonClient

/**
 * Keeps search, routing and navigation alive across rotation, and owns the HTTP client and the
 * on-device database.
 */
class MapViewModel(application: Application) : AndroidViewModel(application) {
  private val http = herdHttpClient()
  private val storage = openHerdStorage(application)

  val planner =
    RoutePlanner(
      viewModelScope,
      PhotonClient(http),
      ValhallaClient(http),
      CensusGeocoder(http),
      storage.recentPlaces,
    )

  val navigator = Navigator(application, viewModelScope)

  override fun onCleared() {
    navigator.stop()
    storage.close()
    http.close()
  }
}
