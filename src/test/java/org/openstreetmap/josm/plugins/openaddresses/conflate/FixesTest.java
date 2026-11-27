package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class FixesTest {
    @BeforeAll
    static void preferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    private static Way way(DataSet ds, Node from, Node to, String name) {
        for (Node node : List.of(from, to)) {
            if (node.getDataSet() == null) {
                ds.addPrimitive(node);
            }
        }
        Way way = new Way();
        way.setNodes(List.of(from, to));
        way.setKeys(Map.of("highway", "residential", "name", name, "oa:hash", "abc", "maxspeed", "25 mph"));
        ds.addPrimitive(way);
        return way;
    }

    private static Issue missing(Way... ways) {
        return new Issue(Issue.Type.MISSING, List.of(ways), List.of(), ways[0].get("name"), 100, 0, "");
    }

    @Test
    void copiesRoadsAndSharesNodesAcrossCopies() {
        DataSet oa = new DataSet();
        Node a = new Node(new LatLon(39.0, -108.0));
        Node b = new Node(new LatLon(39.0, -108.001));
        Node c = new Node(new LatLon(39.0, -108.002));
        b.put("oa:junk", "x");
        Way first = way(oa, a, b, "ASPEN AVE");
        Way second = way(oa, b, c, "ASPEN AVE");

        DataSet osm = new DataSet();
        Command copyFirst = Fixes.copyToOsm(missing(first), osm);
        copyFirst.executeCommand();
        Fixes.copyToOsm(missing(second), osm).executeCommand();

        assertEquals(2, osm.getWays().size());
        assertEquals(3, osm.getNodes().size());
        assertFalse(osm.getNodes().stream().anyMatch(Node::isTagged));
        Way copied = osm.getWays().stream().filter(w -> w.getPrimitiveId().equals(first.getPrimitiveId())).findFirst().orElseThrow();
        assertEquals(Map.of("highway", "residential", "name", "Aspen Avenue", "maxspeed", "25 mph"), copied.getKeys());
        Way other = osm.getWays().stream().filter(w -> w != copied).findFirst().orElseThrow();
        assertSame(copied.lastNode(), other.firstNode());
        assertEquals(2, oa.getWays().size());
        assertEquals("ASPEN AVE", first.get("name"));

        assertNull(Fixes.copyToOsm(missing(first), osm));

        copyFirst.undoCommand();
        assertEquals(1, osm.getWays().size());
    }

    @Test
    void appliesNameToEveryOsmWay() {
        DataSet osm = new DataSet();
        Way one = way(osm, new Node(new LatLon(39.0, -108.0)), new Node(new LatLon(39.0, -108.001)), "Arpor Lane");
        Way two = way(osm, new Node(new LatLon(39.0, -108.001)), new Node(new LatLon(39.0, -108.002)), "Arpor Lane");
        Issue issue = new Issue(Issue.Type.NAME_DIFFERENT, List.of(), List.of(one, two), "ARBOR LN", 100, 0, "");

        Fixes.applyName(issue, RoadNames.suggest(issue.oaName())).executeCommand();

        assertEquals("Arbor Lane", one.get("name"));
        assertEquals("Arbor Lane", two.get("name"));
    }
}
