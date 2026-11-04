# Offline map data

© OpenStreetMap contributors. The extracted database in
`app/src/main/res/raw/pangyo_map.json` is licensed under the
[Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Attribution and copyright: https://www.openstreetmap.org/copyright

Source: https://api.openstreetmap.org/api/0.6/map?bbox=127.088,37.408,127.100,37.417
Retrieved 2026-11-01. Build with `scripts/build-demo-map.py <source.osm>`.
Ways, geometry, classifications and names are retained in the JSON extract.
Illustrative paths follow connected mapped streets; they are NOT truck routes,
live navigation, live GPS, customer destinations or verified site entrances.
Demo dispatch names and package counts are fictional. No traffic, clearance,
vehicle access, turn restrictions or current road closures are evaluated.

The app loads this small vector extract offline. It makes no tile requests and
requires no map key. The public Pleos NaviHelper integration remains separate.
