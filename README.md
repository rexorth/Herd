# Herd

Navigation for road trips and group drives: live location sharing within a group, and planned
routes that guide you back on course when you leave them. Android first (Kotlin), iOS later
(SwiftUI on the same shared Kotlin module).

## v0.1: map and my location

- Renders an OpenStreetMap vector map ([OpenFreeMap](https://openfreemap.org) "liberty" style, no API key).
- Shows your position (with accuracy circle) and follows it. Panning stops following; the
  location button brings it back.
- Asks for foreground location once. If denied, the map still works and a banner offers to fix it.
- Location updates run only while the map is on screen.

## v0.2: search and a single route

- Search bar with suggestions as you type, ranked near your position.
- Pick a result, or long-press anywhere on the map, to get a driving route from where you are.
  A dropped pin gets its address from reverse geocoding.
- The route card shows distance and drive time, with "Avoid highways" and "Avoid tolls" toggles.
- Enter searches exactly what's typed and goes to the top result. Searches that start with a house
  number are ranked mostly by distance; place names keep their usual ranking so far-away
  destinations ("Las Vegas") still come first.
- OpenStreetMap doesn't have every US house number. When search doesn't have the exact house you
  typed (in your town, or the town you typed), a "Find" row and Enter look it up in the US Census
  geocoder, which estimates the spot along the street. Those destinations are marked
  "approximate location". If the address could be on more than one street (W vs E Main St), you
  pick. Long-pressing the spot on the map always works too.

## v0.3 (part 1): turn-by-turn

- **Start** on the route card navigates there: the next turn at the top (tap it for the full list),
  arrival time and distance left at the bottom, and spoken directions ("In 900 feet, turn right onto
  Chapala Street"). The mute button silences the voice.
- Leaving the route gets a new one automatically.
- The trip keeps going with the screen off or in another app: a notification shows the next turn
  and has a stop button. Back sends Herd to the background rather than ending the trip; the X in the
  bottom bar ends it.
- Debug builds have **Simulate** next to Start: a simulated car drives the route at about 45 mph,
  for testing at a desk.
- Tapping the empty search box lists recent destinations (up to 8, saved on the phone). The × next
  to one removes it. Dropped pins aren't saved.
- The location dot and the camera following it glide between GPS fixes instead of jumping once a
  second.

Planned multi-stop routes, and getting back onto them when you leave them, come in part 2.

### Search and routing servers

During development Herd uses free public servers with no API key:

| Service | Server | Notes |
|---|---|---|
| Search ([Photon](https://github.com/komoot/photon)) | `photon.komoot.io` | Komoot's public instance. Fair use only. |
| Routing ([Valhalla](https://github.com/valhalla/valhalla)) | `valhalla1.openstreetmap.de` | FOSSGIS's public instance. Refuses routes over 1,500 km. |
| Exact US addresses ([Census Geocoder](https://geocoding.geo.census.gov/geocoder/)) | `geocoding.geo.census.gov` | US Census Bureau, public-domain data. Only on Enter/Find, never while typing. |

They have usage limits, so the app debounces typing (300 ms, 3+ characters), cancels superseded
requests, and only routes when you pick a destination, change an option, start a trip, or leave the
route while navigating. Navigation asks Valhalla for the same route again with turn instructions
(OSRM format, in miles where road signs use them). Base URLs live in
`shared/.../net/HerdServices.kt`; pointing them at self-hosted servers needs no other change.

## Project layout

```
androidApp/   Android app
  ui/           Compose UI (MapScreen, SearchBar, RouteCard, NavigationOverlay), MapViewModel
  nav/          Navigator: turn-by-turn on Ferrostar (Android-only, so it lives in the app)
shared/       Kotlin Multiplatform module (Android + iOS):
  net/          HTTP client setup, service URLs
  search/       Photon and Census clients, address matching, Place model
  routing/      Valhalla client, polyline decoding, Route model, navigation request options
  planner/      RoutePlanner: search-and-route state shared by both apps
  history/      recent destinations
  db/           the on-device SQLite database (queries in src/commonMain/sqldelight)
  format/       distance and duration text
gradle/libs.versions.toml   all dependency versions
```

Shared tests run on the JVM: `./gradlew :shared:testAndroidHostTest`. Database tests use an
in-memory SQLite. Ferrostar's core is native code built for Android, so navigation itself is tested
on a phone (Simulate helps).

Key libraries: [maplibre-compose](https://github.com/maplibre/maplibre-compose) (map rendering,
location provider, location puck), Google Play Services fused location (through maplibre-compose's
`location-runtime-gms`), [Ferrostar](https://github.com/stadiamaps/ferrostar) (turn-by-turn
guidance, maneuver banner, navigation notification), [SQLDelight](https://github.com/sqldelight/sqldelight)
(on-device database).

## Build and run

Requirements: Android Studio (recent enough for Android Gradle Plugin 9.3), JDK 17+, Android SDK
platform 37. The app runs on Android 7.1 (API 25) and up.

```
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug   # with a phone connected (USB debugging on)
```

Or open the folder in Android Studio and run the `androidApp` configuration.

To install on a phone without Android Studio, open the
[Herd dev build](https://github.com/rexorth/Herd/releases/tag/dev-build) release and tap
`herd-debug.apk`. CI replaces it with a fresh debug build on every push. Each CI run also keeps a
zipped copy as the `herd-debug-apk` artifact.

The iOS targets in `shared/` only build on macOS with Xcode; other hosts skip them.

## Attribution

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL), tiles by
OpenFreeMap, schema © OpenMapTiles. Search by Photon (komoot), address estimates from the US
Census Bureau geocoder, routing by Valhalla (FOSSGIS server), turn-by-turn by Ferrostar (Stadia Maps,
BSD-3). The map's attribution button shows the map attribution; keep it visible.
