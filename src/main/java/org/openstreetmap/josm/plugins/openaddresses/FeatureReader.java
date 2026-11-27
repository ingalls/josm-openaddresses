package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.UploadPolicy;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Reads OpenAddresses line-delimited GeoJSON into a {@link DataSet}.
 */
public final class FeatureReader {
    private static final int MAX_TAG_LENGTH = 255;
    private static final int PROGRESS_INTERVAL = 5000;
    private static final JsonReaderFactory JSON = Json.createReaderFactory(null);

    private final Bounds filter;
    private final TagMapper mapper;
    private final DataSet ds = new DataSet();
    private final Map<LatLon, Node> vertices = new HashMap<>();

    private FeatureReader(Bounds filter, TagMapper mapper) {
        this.filter = filter;
        this.mapper = mapper;
        ds.setUploadPolicy(UploadPolicy.BLOCKED);
    }

    /**
     * @param in     plain or gzipped line-delimited GeoJSON
     * @param filter only keep features whose envelope intersects these bounds, or null for all
     * @param mapper converts properties to OSM tags, or null to keep them as they are
     * @return the data, or null if cancelled
     */
    public static DataSet read(InputStream in, Bounds filter, TagMapper mapper, ProgressMonitor monitor)
            throws IOException {
        FeatureReader reader = new FeatureReader(filter, mapper);

        try (BufferedReader lines = new BufferedReader(new InputStreamReader(gunzip(in), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = lines.readLine()) != null) {
                if (monitor != null && monitor.isCanceled()) {
                    return null;
                }
                if (!line.isBlank()) {
                    reader.feature(line);
                }
                if (monitor != null && ++count % PROGRESS_INTERVAL == 0) {
                    monitor.setCustomText(tr("{0} features read", count));
                }
            }
        }

        if (filter != null) {
            reader.ds.addDataSource(new DataSource(filter, "OpenAddresses"));
        }
        return reader.ds;
    }

    private static InputStream gunzip(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in);
        buffered.mark(2);
        int b0 = buffered.read();
        int b1 = buffered.read();
        buffered.reset();
        return b0 == 0x1f && b1 == 0x8b ? new GZIPInputStream(buffered) : buffered;
    }

    private void feature(String line) {
        JsonObject feature;
        try (JsonReader reader = JSON.createReader(new StringReader(line))) {
            feature = reader.readObject();
        }

        JsonValue geomValue = feature.get("geometry");
        if (geomValue == null || geomValue.getValueType() != JsonValue.ValueType.OBJECT) {
            return;
        }
        JsonObject geometry = geomValue.asJsonObject();
        JsonArray coordinates = geometry.getJsonArray("coordinates");
        if (coordinates == null || coordinates.isEmpty()) {
            return;
        }

        Map<String, String> properties = tags(feature.getJsonObject("properties"));
        Map<String, String> tags = mapper == null ? properties : mapper.map(properties);

        switch (geometry.getString("type", "")) {
            case "Point" -> point(coordinates, tags);
            case "MultiPoint" -> coordinates.getValuesAs(JsonArray.class).forEach(c -> point(c, tags));
            case "LineString" -> line(coordinates, tags);
            case "MultiLineString" -> coordinates.getValuesAs(JsonArray.class).forEach(c -> line(c, tags));
            case "Polygon" -> polygons(List.of(coordinates), tags);
            case "MultiPolygon" -> polygons(coordinates.getValuesAs(JsonArray.class), tags);
            default -> {
            }
        }
    }

    private void point(JsonArray coordinate, Map<String, String> tags) {
        LatLon coord = latLon(coordinate);
        if (filter != null && !filter.contains(coord)) {
            return;
        }
        Node node = new Node(coord);
        node.setKeys(tags);
        ds.addPrimitive(node);
    }

    private void line(JsonArray coordinates, Map<String, String> tags) {
        List<LatLon> coords = latLons(coordinates);
        if (coords.size() < 2 || !intersects(List.of(coords))) {
            return;
        }
        Way way = way(coords);
        if (way != null) {
            way.setKeys(tags);
        }
    }

    private void polygons(List<JsonArray> polygons, Map<String, String> tags) {
        List<List<List<LatLon>>> parsed = new ArrayList<>();
        List<List<LatLon>> outers = new ArrayList<>();
        int rings = 0;
        for (JsonArray polygon : polygons) {
            List<List<LatLon>> polygonRings = new ArrayList<>();
            for (JsonArray ring : polygon.getValuesAs(JsonArray.class)) {
                List<LatLon> coords = latLons(ring);
                if (coords.size() >= 4) {
                    polygonRings.add(coords);
                } else if (polygonRings.isEmpty()) {
                    break;
                }
            }
            if (!polygonRings.isEmpty()) {
                parsed.add(polygonRings);
                outers.add(polygonRings.get(0));
                rings += polygonRings.size();
            }
        }
        if (parsed.isEmpty() || !intersects(outers)) {
            return;
        }

        if (rings == 1) {
            Way way = way(outers.get(0));
            if (way != null) {
                way.setKeys(tags);
            }
            return;
        }

        List<RelationMember> members = new ArrayList<>();
        for (List<List<LatLon>> polygonRings : parsed) {
            for (int i = 0; i < polygonRings.size(); i++) {
                Way way = way(polygonRings.get(i));
                if (way != null) {
                    members.add(new RelationMember(i == 0 ? "outer" : "inner", way));
                }
            }
        }
        if (members.isEmpty()) {
            return;
        }
        Relation relation = new Relation();
        relation.setKeys(tags);
        relation.put("type", "multipolygon");
        relation.setMembers(members);
        ds.addPrimitive(relation);
    }

    private Way way(List<LatLon> coords) {
        List<Node> nodes = new ArrayList<>(coords.size());
        Node previous = null;
        for (LatLon coord : coords) {
            Node node = vertices.computeIfAbsent(coord, ll -> {
                Node n = new Node(ll);
                ds.addPrimitive(n);
                return n;
            });
            if (node != previous) {
                nodes.add(node);
            }
            previous = node;
        }
        if (nodes.size() < 2) {
            return null;
        }
        Way way = new Way();
        way.setNodes(nodes);
        ds.addPrimitive(way);
        return way;
    }

    private boolean intersects(List<List<LatLon>> parts) {
        if (filter == null) {
            return true;
        }
        Bounds envelope = new Bounds(parts.get(0).get(0));
        for (List<LatLon> part : parts) {
            for (LatLon coord : part) {
                envelope.extend(coord);
            }
        }
        return filter.intersects(envelope);
    }

    private static List<LatLon> latLons(JsonArray coordinates) {
        List<LatLon> coords = new ArrayList<>(coordinates.size());
        for (JsonArray c : coordinates.getValuesAs(JsonArray.class)) {
            coords.add(latLon(c));
        }
        return coords;
    }

    private static LatLon latLon(JsonArray c) {
        return new LatLon(c.getJsonNumber(1).doubleValue(), c.getJsonNumber(0).doubleValue());
    }

    private static Map<String, String> tags(JsonObject properties) {
        Map<String, String> tags = new HashMap<>();
        if (properties == null) {
            return tags;
        }
        for (Map.Entry<String, JsonValue> entry : properties.entrySet()) {
            String value = text(entry.getValue());
            if (!value.isEmpty()) {
                tags.put(entry.getKey(), value.length() > MAX_TAG_LENGTH ? value.substring(0, MAX_TAG_LENGTH) : value);
            }
        }
        return tags;
    }

    private static String text(JsonValue value) {
        return switch (value.getValueType()) {
            case STRING -> ((JsonString) value).getString().trim();
            case NUMBER, TRUE, FALSE -> value.toString();
            case ARRAY -> value.asJsonArray().stream()
                .map(FeatureReader::text)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("; "));
            case OBJECT -> {
                JsonObject object = value.asJsonObject();
                yield object.containsKey("name") ? text(object.get("name")) : object.toString();
            }
            default -> "";
        };
    }

    static boolean isEmpty(DataSet ds) {
        return ds.getPrimitives(OsmPrimitive::isTagged).isEmpty() && ds.getWays().isEmpty();
    }
}
