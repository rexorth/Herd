package app.herd.shared.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationRequestTest {
  @Test
  fun optionsCarryUnitsLanguageAndAvoidSettings() {
    assertEquals(
      """{"units":"miles","language":"en-US","costing_options":{"auto":{"use_highways":0.0,"use_tolls":0.0}}}""",
      valhallaNavigationOptions(RouteOptions(avoidHighways = true, avoidTolls = true), DistanceUnits.Miles),
    )
  }

  @Test
  fun noAvoidSettingsLeavesValhallasDefaults() {
    assertEquals(
      """{"units":"kilometers","language":"en-US","costing_options":{"auto":{}}}""",
      valhallaNavigationOptions(RouteOptions(), DistanceUnits.Kilometers),
    )
  }

  @Test
  fun readsValhallasErrorMessage() {
    assertEquals(
      "Exceeded max locations: 10",
      valhallaErrorMessage("""{"error_code":150,"error":"Exceeded max locations: 10","status_code":400}"""),
    )
    assertEquals(null, valhallaErrorMessage("<html>Bad gateway</html>"))
  }

  @Test
  fun milesOnlyWhereRoadSignsUseThem() {
    assertEquals(DistanceUnits.Miles, distanceUnitsFor("US"))
    assertEquals(DistanceUnits.Miles, distanceUnitsFor("gb"))
    assertEquals(DistanceUnits.Kilometers, distanceUnitsFor("CA"))
    assertEquals(DistanceUnits.Kilometers, distanceUnitsFor(""))
    assertEquals(DistanceUnits.Kilometers, distanceUnitsFor(null))
  }
}
