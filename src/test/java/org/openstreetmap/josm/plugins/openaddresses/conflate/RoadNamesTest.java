package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class RoadNamesTest {
    @BeforeAll
    static void preferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    private static Way osm(String... keyValues) {
        Way way = new Way();
        for (int i = 0; i < keyValues.length; i += 2) {
            way.put(keyValues[i], keyValues[i + 1]);
        }
        return way;
    }

    @Test
    void normalizes() {
        assertEquals(RoadNames.normalize("Patricia Court"), RoadNames.normalize("PATRICIA CT"));
        assertEquals(RoadNames.normalize("West 7th Street South"), RoadNames.normalize("W 7TH ST S"));
        assertEquals(RoadNames.normalize("Saint Vrain Road"), RoadNames.normalize("ST VRAIN RD"));
        assertEquals("346", RoadNames.routeNumber("N CORD 346"));
    }

    @Test
    void compares() {
        assertEquals(RoadNames.Comparison.SAME, RoadNames.compare("ARBOR LN", osm("name", "Arbor Lane")));
        assertEquals(RoadNames.Comparison.SAME,
            RoadNames.compare("OLD MILL RD", osm("name", "Mill Road", "alt_name", "Mill Rd;Old Mill Road")));
        assertEquals(RoadNames.Comparison.SAME, RoadNames.compare("LIONS PARK CIR", osm("name", "Lion's Park Circle")));
        assertEquals(RoadNames.Comparison.SAME, RoadNames.compare("N CORD 346", osm("name", "North County Road 346")));
        assertEquals(RoadNames.Comparison.SAME, RoadNames.compare("O BRIEN LN", osm("name", "O'Brien Lane")));
        assertEquals(RoadNames.Comparison.DIRECTION_ONLY, RoadNames.compare("12TH ST", osm("name", "East 12th Street")));
        assertEquals(RoadNames.Comparison.DIRECTION_ONLY, RoadNames.compare("E 16TH ST", osm("name", "West 16th Street")));
        assertEquals(RoadNames.Comparison.DIFFERENT, RoadNames.compare("ARBOR LN", osm("name", "Arpor Lane")));
        assertEquals(RoadNames.Comparison.DIFFERENT, RoadNames.compare("FIR CT", osm("name", "Fir Avenue")));
    }

    @Test
    void routes() {
        assertEquals("210", RoadNames.routeNumber("CORD 210"));
        assertEquals("13", RoadNames.routeNumber("STHY 13"));
        assertEquals("65", RoadNames.routeNumber("HIGHWAY 65"));
        assertEquals("70", RoadNames.routeNumber("I-70"));
        assertNull(RoadNames.routeNumber("HIGHWAY VIEW DR"));
        assertNull(RoadNames.routeNumber("7TH ST"));
        assertTrue(RoadNames.hasRouteNumber(osm("name", "Mile Pond Road", "ref", "CR 210"), "210"));
        assertTrue(RoadNames.hasRouteNumber(osm("ref", "US 6;US 24"), "24"));
    }

    @Test
    void suggests() {
        assertEquals("Patricia Court", RoadNames.suggest("PATRICIA CT"));
        assertEquals("West 7th Street South", RoadNames.suggest("W 7TH ST S"));
        assertEquals("Saint Vrain Road", RoadNames.suggest("ST VRAIN RD"));
        assertEquals("County Road 210", RoadNames.suggest("COUNTY RD 210"));
        assertEquals("McKinley Avenue", RoadNames.suggest("MCKINLEY AVE"));
        assertEquals("N", RoadNames.suggest("N"));
        assertEquals("North County Road 346", RoadNames.suggest("N CORD 346"));
        assertEquals("State Highway 13", RoadNames.suggest("STHY 13"));
    }
}
