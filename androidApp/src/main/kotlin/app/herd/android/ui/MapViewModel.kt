package app.herd.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.herd.shared.net.herdHttpClient
import app.herd.shared.planner.RoutePlanner
import app.herd.shared.routing.ValhallaClient
import app.herd.shared.search.CensusGeocoder
import app.herd.shared.search.PhotonClient

/** Keeps the search-and-route state alive across rotation and owns the HTTP client. */
class MapViewModel : ViewModel() {
  private val http = herdHttpClient()

  val planner =
    RoutePlanner(viewModelScope, PhotonClient(http), ValhallaClient(http), CensusGeocoder(http))

  override fun onCleared() {
    http.close()
  }
}
