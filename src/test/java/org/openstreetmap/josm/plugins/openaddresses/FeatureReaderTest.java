package org.openstreetmap.josm.plugins.openaddresses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.UploadPolicy;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class FeatureReaderTest {
    @BeforeAll
    static void preferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    private static final String LINES = String.join("\n",
        "{\"type\":\"Feature\",\"properties\":{\"hash\":\"a\",\"id\":\"\",\"name\":\"MAIN ST\",\"speed\":\"\"},"
            + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-108.5,39.0],[-108.4,39.0]]}}",
        "{\"type\":\"Feature\",\"properties\":{\"hash\":\"b\",\"name\":\"1ST AVE\"},"
            + "\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[-108.4,39.0],[-108.4,39.1]],[[-100.0,30.0],[-100.0,30.1]]]}}",
        "{\"type\":\"Feature\",\"properties\":{\"hash\":\"c\"},\"geometry\":null}",
        ""
    );

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void readsGzippedLinesAndSharesNodes() throws IOException {
        DataSet ds = FeatureReader.read(new ByteArrayInputStream(gzip(LINES)), null, null, null);

        assertEquals(3, ds.getWays().size());
        assertEquals(5, ds.getNodes().size());
        assertEquals(UploadPolicy.BLOCKED, ds.getUploadPolicy());

        Way main = ds.getWays().stream().filter(w -> "MAIN ST".equals(w.get("name"))).findFirst().orElseThrow();
        assertEquals("a", main.get("hash"));
        assertFalse(main.hasKey("speed"));
        assertNull(main.get("id"));
    }

    @Test
    void readsPlainTextAndFiltersByBounds() throws IOException {
        Bounds view = new Bounds(38.9, -108.6, 39.2, -108.3);
        DataSet ds = FeatureReader.read(
            new ByteArrayInputStream(LINES.getBytes(StandardCharsets.UTF_8)), view, null, null);

        assertEquals(2, ds.getWays().size());
        assertEquals(3, ds.getNodes().size());
        assertEquals(1, ds.getDataSources().size());
    }

    @Test
    void parsesSourceListing() {
        String json = "[{\"id\":5272,\"fabric\":false,\"source\":\"us/co/mesa\",\"updated\":\"2026-11-04T16:08:09.624Z\","
            + "\"layer\":\"centerlines\",\"name\":\"county\",\"job\":947899,\"latest_job\":947899,"
            + "\"output\":{\"cache\":false,\"output\":true,\"pmtiles\":true,\"preview\":true,\"validated\":false},\"size\":1840197},"
            + "{\"id\":1,\"fabric\":false,\"source\":\"us/co/broken\",\"updated\":\"2026-11-04T16:08:09.624Z\","
            + "\"layer\":\"centerlines\",\"name\":\"county\",\"job\":2,\"latest_job\":2,"
            + "\"output\":{\"cache\":false,\"output\":false,\"pmtiles\":false,\"preview\":false,\"validated\":false},\"size\":0}]";

        List<Source> sources = OpenAddressesApi.parseSources(new StringReader(json));

        assertEquals(1, sources.size());
        assertEquals(947899, sources.get(0).job());
        assertEquals(Layer.ROADS, sources.get(0).layer());
        assertEquals("OpenAddresses: us/co/mesa centerlines (county)", sources.get(0).layerName());
    }

    @Test
    void readsPointsAndPolygons() throws IOException {
        String lines = String.join("\n",
            "{\"type\":\"Feature\",\"properties\":{\"number\":\"1\",\"street\":\"MAIN ST\",\"unit\":\"A\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[-108.5,39.0]}}",
            "{\"type\":\"Feature\",\"properties\":{\"number\":\"1\",\"street\":\"MAIN ST\",\"unit\":\"B\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[-108.5,39.0]}}",
            "{\"type\":\"Feature\",\"properties\":{\"pid\":\"7\",\"owners\":[{\"name\":\"A SMITH\",\"address\":\"1 MAIN\"},{\"name\":\"B JONES\"}]},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[0,1],[1,1],[1,0],[0,0]]]}}",
            "{\"type\":\"Feature\",\"properties\":{\"pid\":\"8\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[10,10],[10,14],[14,14],[14,10],[10,10]],[[11,11],[11,12],[12,12],[12,11],[11,11]]]}}"
        );

        DataSet ds = FeatureReader.read(new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)), null, null, null);

        assertEquals(2, ds.getNodes().stream().filter(n -> n.hasKey("unit")).count());
        assertEquals(3, ds.getWays().size());

        Way parcel = ds.getWays().stream().filter(w -> "7".equals(w.get("pid"))).findFirst().orElseThrow();
        assertEquals(true, parcel.isClosed());
        assertEquals("A SMITH; B JONES", parcel.get("owners"));

        assertEquals(1, ds.getRelations().size());
        var relation = ds.getRelations().iterator().next();
        assertEquals("multipolygon", relation.get("type"));
        assertEquals("8", relation.get("pid"));
        assertEquals(List.of("outer", "inner"), relation.getMembers().stream().map(m -> m.getRole()).toList());
        assertFalse(FeatureReader.isEmpty(ds));
    }
}
