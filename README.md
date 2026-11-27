# josm-openaddresses

JOSM plugin that loads [OpenAddresses](https://batch.openaddresses.io) addresses, parcels,
roads and buildings as reference layers alongside OpenStreetMap data.

## Usage

1. Zoom the map to the area of interest.
2. **Data → Download OpenAddresses data...**
3. Log in with your OpenAddresses account the first time.
4. Narrow the list with the **Addresses / Parcels / Roads / Buildings** toggles or the name
   filter, pick a source and download.

The data arrives as a new upload-blocked layer named `OpenAddresses: <source> <layer> (<name>)`.

| Layer | Geometry in JOSM |
|---|---|
| Addresses | Tagged nodes |
| Roads | Ways sharing nodes at common vertices |
| Parcels, Buildings | Closed ways; multipolygon relations when a feature has holes or several parts |

## Tag mapping

With **Convert attributes to OSM tags** ticked (the default), the OpenAddresses schema is
translated on import. Anything without an OSM equivalent is kept under an `oa:` prefix so it is
easy to spot and strip before data reaches OSM. Untick the box to keep the raw properties.

| Layer | OpenAddresses | OSM |
|---|---|---|
| Addresses | `number` | `addr:housenumber` |
| | `street` | `addr:street` |
| | `unit` | `addr:unit` |
| | `city` | `addr:city` |
| | `district` | `addr:county` (US), `addr:district` elsewhere |
| | `region` | `addr:state` (US, AU, BR, MX, IN, ...), `addr:province` elsewhere |
| | `postcode` | `addr:postcode` |
| | `id`, `accuracy`, `hash` | `oa:id`, `oa:accuracy`, `oa:hash` |
| Buildings | (always) | `building=yes` |
| | `height` (m) | `height` |
| | `levels` | `building:levels` |
| Parcels | `pid` | `ref` |
| | `owners` | `oa:owners` (names joined with `; `) |
| Roads | `classification` | `highway=*`; `highway=road` when missing or not an OSM class |
| | `name` | `name` |
| | `oneway` | `yes` → `oneway=yes`, `reverse` → `oneway=-1`, `no` → omitted |
| | `speed` (km/h) | `maxspeed`; converted back to `NN mph` in mph countries |
| | `surface` | `surface` (`paved` / `unpaved`) |
| | `zip_left`, `zip_right` | `postal_code` when equal, else `postal_code:left` / `postal_code:right` |
| | `addr_from_*`, `addr_to_*`, `id` | `oa:addr_from_*`, `oa:addr_to_*`, `oa:id` (zero ranges dropped) |

Values are not otherwise rewritten: street names keep the source's case and abbreviations.

## Comparing roads with OSM

With an OpenAddresses roads layer and an OSM data layer loaded, run
**Data → Compare OpenAddresses roads with OSM** (also on the roads layer's right-click menu).
The **OpenAddresses roads** side panel lists what it found:

| Group | Meaning |
|---|---|
| Missing from OSM | Less than 20% of the OpenAddresses road has an OSM road alongside it |
| Partly missing from OSM | 20–80% has an OSM road alongside it |
| Name missing in OSM | The matching OSM road has no name |
| Name differs | Names disagree after normalising case, abbreviations and punctuation |
| Geometry differs | The two lines are more than 5 m apart on average |

Selecting an entry selects the ways in both layers and highlights the OpenAddresses geometry.
Double-click (or **Zoom**) to zoom to it. **Copy to OSM** adds a missing road to the OSM layer as
a new, unconnected way without its `oa:` tags; **Apply name** sets the OSM name after letting you
edit the suggested spelling; **Dismiss** removes the entry. Edits go through undo/redo.

How matching works: each OpenAddresses segment is sampled every 10 m and matched to the nearest
drivable OSM highway within 15 m running within 30° of the same direction. A same-named OSM road
up to 40 m away also counts, so displaced roads show up under geometry, not as missing. Only the
parts inside the OSM layer's downloaded area are considered. Route labels such as `CORD 210` are
compared with the OSM `ref`, and are not reported as name differences.

Geometry entries say which side has more vertices and whether the OSM way is unreviewed TIGER
data, but which line is correct needs checking against imagery.

Downloads require an OpenAddresses account. The login popup exchanges your credentials for a
two-day session token, stored in the `openaddresses.token` preference; the password itself is
never stored. The popup reappears when the session expires.

Downloaded job outputs are kept in the JOSM cache directory under `openaddresses/`.

## Build

```sh
./gradlew build            # compiles against josm-tested.jar, fetched on first run
./gradlew installPlugin    # copies build/libs/openaddresses.jar into ~/.local/share/JOSM/plugins
```

| Property | Default | Purpose |
|---|---|---|
| `-PjosmJar=` | downloaded `josm-tested.jar` | JOSM jar to compile against |
| `-PjosmPluginDir=` | `~/.local/share/JOSM/plugins` | `installPlugin` destination |
| `-PjavaToolchain=` | `25` | JDK used to build (bytecode targets Java 17) |

After installing, enable `openaddresses` under Preferences → Plugins and restart JOSM.

## Layout

| File | Role |
|---|---|
| `OpenAddressesPlugin` | Entry point, registers the menu action |
| `DownloadAction` | Login, source listing and download orchestration |
| `SourceDialog` | Source picker with layer toggles and name filter |
| `LoginDialog` | Login popup and session token handling |
| `DownloadTask` | Background download + layer creation |
| `OpenAddressesApi` | `/api/login`, `/api/data` listing and `/api/job/:job/output/source.geojson.gz` download |
| `FeatureReader` | Line-delimited GeoJSON → `DataSet`, clipped to the view |
| `TagMapper` | OpenAddresses schema → OSM tags |
| `Layer`, `Source` | Layer types and listed datasets |
| `OpenAddressesLayer` | Upload-blocked layer that remembers its source |
| `conflate/RoadMatcher` | Samples OpenAddresses roads against OSM highways and builds the issue list |
| `conflate/RoadNames` | Name normalisation, route labels and OSM-style name suggestions |
| `conflate/ConflationDialog` | Side panel listing the issues |
| `conflate/Fixes` | Copy-to-OSM and apply-name commands |
| `conflate/CompareRoadsAction` | Menu entry that runs the comparison |
