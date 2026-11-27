package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class RoadMatcherTest {
    private static final double LAT = 39.0;
    private static final double LON = -108.0;
    private static final double M_LAT = 1 / 110_540.0;
    private static final double M_LON = 1 / (111_320 * Math.cos(Math.toRadians(LAT)));

    @BeforeAll
    static void preferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    /** A way through points given in metres east/north of the origin. */
    private static Way way(DataSet ds, String name, String highway, double... metres) {
        Way way = new Way();
        for (int i = 0; i < metres.length; i += 2) {
            Node node = new Node(new LatLon(LAT + metres[i + 1] * M_LAT, LON + metres[i] * M_LON));
            ds.addPrimitive(node);
            way.addNode(node);
        }
        if (name != null) {
            way.put("name", name);
        }
        if (highway != null) {
            way.put("highway", highway);
        }
        ds.addPrimitive(way);
        return way;
    }

    private static Issue only(RoadMatcher.Result result, Issue.Type type) {
        List<Issue> issues = result.issues().stream().filter(i -> i.type() == type).toList();
        assertEquals(1, issues.size(), type + " in " + result.issues());
        return issues.get(0);
    }

    @Test
    void flagsMissingPartialNamesAndGeometry() {
        DataSet osm = new DataSet();
        DataSet oa = new DataSet();

        way(osm, "Main Street", "residential", 0, 0, 500, 0);
        way(oa, "MAIN ST", "residential", 0, 3, 250, 3);
        way(oa, "MAIN ST", "residential", 250, 3, 500, 3);

        Way aspenA = way(oa, "ASPEN AVE", "residential", 0, 300, 200, 300);
        Way aspenB = way(oa, "ASPEN AVE", "residential", 200, 300, 400, 300);
        Node duplicate = aspenB.firstNode();
        aspenB.setNodes(List.of(aspenA.lastNode(), aspenB.lastNode()));
        oa.removePrimitive(duplicate);

        way(osm, null, "residential", 0, 600, 100, 600);
        way(oa, "S 10TH ST", "residential", 0, 600, 400, 600);

        way(osm, null, "residential", 0, 900, 300, 900);
        way(oa, "BARON LN", "residential", 0, 902, 300, 902);

        way(osm, "Arpor Lane", "residential", 0, 1200, 300, 1200);
        way(oa, "ARBOR LN", "residential", 0, 1201, 300, 1201);

        way(osm, "Mile Pond Road", "tertiary", 0, 1500, 300, 1500).put("ref", "CR 210");
        way(oa, "CORD 210", "tertiary", 0, 1501, 300, 1501);

        way(osm, "Western Avenue", "residential", 0, 1800, 300, 1800).put("tiger:reviewed", "no");
        way(oa, "WESTERN AVE", "residential", 0, 1809, 100, 1811, 200, 1808, 300, 1809);

        way(osm, "Far Road", "residential", 0, 2100, 300, 2100);
        way(oa, "FAR RD", "residential", 0, 2130, 300, 2130);

        way(osm, "Footpath", "footway", 0, 2400, 300, 2400);
        way(oa, "CREEK TRL", "residential", 0, 2400, 300, 2400);

        RoadMatcher.Result result = RoadMatcher.match(oa.getWays(), osm, null);

        List<Issue> missing = result.issues().stream().filter(i -> i.type() == Issue.Type.MISSING).toList();
        assertEquals(List.of("ASPEN AVE", "CREEK TRL"), missing.stream().map(Issue::oaName).sorted().toList());
        Issue aspen = missing.stream().filter(i -> i.oaName().equals("ASPEN AVE")).findFirst().orElseThrow();
        assertEquals(2, aspen.oaWays().size());
        assertEquals(400, aspen.length(), 1);

        Issue partial = only(result, Issue.Type.PARTIAL);
        assertEquals("S 10TH ST", partial.oaName());
        assertEquals(0.25, partial.value(), 0.05);

        assertEquals("BARON LN", only(result, Issue.Type.NAME_MISSING).oaName());

        Issue different = only(result, Issue.Type.NAME_DIFFERENT);
        assertEquals("ARBOR LN", different.oaName());
        assertEquals("Arpor Lane", different.osmName());

        List<Issue> geometry = result.issues().stream().filter(i -> i.type() == Issue.Type.GEOMETRY).toList();
        assertEquals(List.of("FAR RD", "WESTERN AVE"), geometry.stream().map(Issue::oaName).sorted().toList());
        Issue western = geometry.stream().filter(i -> i.oaName().equals("WESTERN AVE")).findFirst().orElseThrow();
        assertEquals(9, western.value(), 1.5);
        assertTrue(western.note().contains("TIGER"), western.note());
    }

    @Test
    void ignoresRoadsOutsideTheDownloadedArea() {
        DataSet osm = new DataSet();
        osm.addDataSource(new DataSource(
            new Bounds(LAT - 100 * M_LAT, LON - 100 * M_LON, LAT + 100 * M_LAT, LON + 100 * M_LON), "test"));
        DataSet oa = new DataSet();
        way(oa, "INSIDE RD", null, -50, 50, 50, 50);
        way(oa, "OUTSIDE RD", null, 500, 500, 800, 500);
        way(oa, "CROSSING RD", null, 0, -50, 0, 400);

        RoadMatcher.Result result = RoadMatcher.match(oa.getWays(), osm, null);

        assertEquals(2, result.compared());
        assertEquals(List.of("CROSSING RD", "INSIDE RD"),
            result.issues().stream().map(Issue::oaName).sorted().toList());
        Issue crossing = result.issues().stream().filter(i -> i.oaName().equals("CROSSING RD")).findFirst().orElseThrow();
        assertEquals(150, crossing.length(), 15);
    }
}
