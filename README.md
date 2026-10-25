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
- OpenStreetMap doesn't have every US house number. When an address is missing, long-press its
  spot on the map instead.

### Search and routing servers

During development Herd uses free public servers with no API key:

| Service | Server | Notes |
|---|---|---|
| Search ([Photon](https://github.com/komoot/photon)) | `photon.komoot.io` | Komoot's public instance. Fair use only. |
| Routing ([Valhalla](https://github.com/valhalla/valhalla)) | `valhalla1.openstreetmap.de` | FOSSGIS's public instance. Refuses routes over 1,500 km. |

Both have usage limits, so the app debounces typing (300 ms, 3+ characters), cancels superseded
requests, and only routes when you pick a destination or change an option. Base URLs live in
`shared/.../net/HerdServices.kt`; pointing them at self-hosted servers needs no other change.

## Project layout

```
androidApp/   Android app: Compose UI (MapScreen, SearchBar, RouteCard), MapViewModel
shared/       Kotlin Multiplatform module (Android + iOS):
  net/          HTTP client setup, service URLs
  search/       Photon client and Place model
  routing/      Valhalla client, polyline decoding, Route model
  planner/      RoutePlanner: search-and-route state shared by both apps
  format/       distance and duration text
gradle/libs.versions.toml   all dependency versions
```

Shared tests run on the JVM: `./gradlew :shared:testAndroidHostTest`.

Key libraries: [maplibre-compose](https://github.com/maplibre/maplibre-compose) (map rendering,
location provider, location puck), Google Play Services fused location (through maplibre-compose's
`location-runtime-gms`).

## Build and run

Requirements: Android Studio (recent enough for Android Gradle Plugin 9.3), JDK 17+, Android SDK
platform 37.

```
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug   # with a phone connected (USB debugging on)
```

Or open the folder in Android Studio and run the `androidApp` configuration. Each push also builds
a debug APK in GitHub Actions ("Android build" workflow, `herd-debug-apk` artifact).

The iOS targets in `shared/` only build on macOS with Xcode; other hosts skip them.

## Attribution

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL), tiles by
OpenFreeMap, schema © OpenMapTiles. Search by Photon (komoot), routing by Valhalla (FOSSGIS
server). The map's attribution button shows the map attribution; keep it visible.
