package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;

/**
 * Compares OpenAddresses road segments with OSM highways by sampling points along each
 * OpenAddresses segment and looking for an OSM road alongside running the same direction.
 */
public final class RoadMatcher {
    static final double NEAR = 15;
    static final double FAR = 40;
    static final double NEAR_ANGLE = 30;
    static final double FAR_ANGLE = 20;
    static final double STEP = 10;
    static final double MIN_LENGTH = 20;
    static final double MISSING_BELOW = 0.2;
    static final double MATCHED_FROM = 0.8;
    static final double GEOMETRY_OFFSET = 5;
    static final int GEOMETRY_MIN_SAMPLES = 5;
    static final int NAME_MIN_SAMPLES = 3;

    private static final double CELL = 50;
    private static final Set<String> DRIVABLE = Set.of(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service",
        "track", "road", "living_street", "busway",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link");

    public record Result(List<Issue> issues, int compared, int matched) {
    }

    private record Segment(Way way, double ax, double ay, double bx, double by, double bearing) {
    }

    private record Hit(Way way, double distance) {
    }

    private static final class OsmStats {
        int samples;
        double distanceSum;
        double distanceMax;
        final Map<String, Integer> nameVotes = new HashMap<>();
        final Map<String, String> nameSpelling = new HashMap<>();
        final List<Way> oaWays = new ArrayList<>();
        double oaLength;
        int oaVertices;
    }

    private record Unmatched(Way way, String name, double length, double coverage) {
    }

    private final double originLat;
    private final double originLon;
    private final double metresPerLon;
    private static final double METRES_PER_LAT = 110_540;
    private final Map<Long, List<Segment>> grid = new HashMap<>();
    private final List<Bounds> downloaded;

    private RoadMatcher(ILatLon origin, List<Bounds> downloaded) {
        this.originLat = origin.lat();
        this.originLon = origin.lon();
        this.metresPerLon = 111_320 * Math.cos(Math.toRadians(origin.lat()));
        this.downloaded = downloaded;
    }

    public static Result match(Collection<Way> oaWays, DataSet osm, ProgressMonitor monitor) {
        Node first = oaWays.stream().filter(w -> w.isUsable() && w.getNodesCount() > 0)
            .map(Way::firstNode).findFirst().orElse(null);
        if (first == null) {
            return new Result(List.of(), 0, 0);
        }

        osm.getReadLock().lock();
        try {
            RoadMatcher matcher = new RoadMatcher(first, osm.getDataSourceBounds());
            matcher.index(osm);
            return matcher.run(oaWays, monitor);
        } finally {
            osm.getReadLock().unlock();
        }
    }

    static boolean isRoad(Way way) {
        return way.isUsable() && way.getNodesCount() > 1
            && way.hasKey("highway") && DRIVABLE.contains(way.get("highway")) && !"yes".equals(way.get("area"));
    }

    private double x(ILatLon ll) {
        return (ll.lon() - originLon) * metresPerLon;
    }

    private double y(ILatLon ll) {
        return (ll.lat() - originLat) * METRES_PER_LAT;
    }

    private static long cell(double x, double y) {
        return (((long) Math.floor(x / CELL)) << 32) ^ (((long) Math.floor(y / CELL)) & 0xffffffffL);
    }

    private static double bearing(double ax, double ay, double bx, double by) {
        double degrees = Math.toDegrees(Math.atan2(by - ay, bx - ax)) % 180;
        return degrees < 0 ? degrees + 180 : degrees;
    }

    private static double angleBetween(double a, double b) {
        double d = Math.abs(a - b) % 180;
        return Math.min(d, 180 - d);
    }

    private void index(DataSet osm) {
        for (Way way : osm.getWays()) {
            if (!isRoad(way)) {
                continue;
            }
            List<Node> nodes = way.getNodes();
            for (int i = 1; i < nodes.size(); i++) {
                if (!nodes.get(i - 1).isLatLonKnown() || !nodes.get(i).isLatLonKnown()) {
                    continue;
                }
                double ax = x(nodes.get(i - 1));
                double ay = y(nodes.get(i - 1));
                double bx = x(nodes.get(i));
                double by = y(nodes.get(i));
                Segment segment = new Segment(way, ax, ay, bx, by, bearing(ax, ay, bx, by));
                for (double gx = Math.floor(Math.min(ax, bx) / CELL) - 1; gx <= Math.floor(Math.max(ax, bx) / CELL) + 1; gx++) {
                    for (double gy = Math.floor(Math.min(ay, by) / CELL) - 1; gy <= Math.floor(Math.max(ay, by) / CELL) + 1; gy++) {
                        grid.computeIfAbsent(cell(gx * CELL, gy * CELL), k -> new ArrayList<>()).add(segment);
                    }
                }
            }
        }
    }

    private static double distance(double px, double py, Segment s) {
        double dx = s.bx - s.ax;
        double dy = s.by - s.ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - s.ax) * dx + (py - s.ay) * dy) / lengthSquared));
        return Math.hypot(px - s.ax - t * dx, py - s.ay - t * dy);
    }

    private Hit nearest(double px, double py, double bearing, String oaName) {
        Hit near = null;
        Hit far = null;
        for (Segment segment : grid.getOrDefault(cell(px, py), List.of())) {
            double distance = distance(px, py, segment);
            if (distance > FAR) {
                continue;
            }
            double angle = angleBetween(bearing, segment.bearing);
            if (distance <= NEAR && angle <= NEAR_ANGLE) {
                if (near == null || distance < near.distance) {
                    near = new Hit(segment.way, distance);
                }
            } else if (near == null && angle <= FAR_ANGLE && !oaName.isEmpty()
                    && (far == null || distance < far.distance)
                    && RoadNames.compare(oaName, segment.way) == RoadNames.Comparison.SAME) {
                far = new Hit(segment.way, distance);
            }
        }
        return near != null ? near : far;
    }

    private boolean inDownloadedArea(double lat, double lon) {
        if (downloaded.isEmpty()) {
            return true;
        }
        LatLon ll = new LatLon(lat, lon);
        for (Bounds bounds : downloaded) {
            if (bounds.contains(ll)) {
                return true;
            }
        }
        return false;
    }

    private Result run(Collection<Way> oaWays, ProgressMonitor monitor) {
        Map<Way, OsmStats> stats = new LinkedHashMap<>();
        List<Unmatched> unmatched = new ArrayList<>();
        int compared = 0;
        int matched = 0;

        for (Way oa : oaWays) {
            if (monitor != null && monitor.isCanceled()) {
                return null;
            }
            if (!oa.isUsable() || oa.getNodesCount() < 2 || oa.isClosed() && oa.hasKey("building")) {
                continue;
            }
            String name = oa.get("name") == null ? "" : oa.get("name").trim();

            int inside = 0;
            int hits = 0;
            double insideLength = 0;
            Map<Way, Integer> perWay = new HashMap<>();
            Map<Way, double[]> perWayDistance = new HashMap<>();
            List<Node> nodes = oa.getNodes();
            for (int i = 1; i < nodes.size(); i++) {
                double ax = x(nodes.get(i - 1));
                double ay = y(nodes.get(i - 1));
                double bx = x(nodes.get(i));
                double by = y(nodes.get(i));
                double length = Math.hypot(bx - ax, by - ay);
                int pieces = Math.max(1, (int) (length / STEP));
                double bearing = bearing(ax, ay, bx, by);
                for (int p = 0; p < pieces; p++) {
                    double t = (p + 0.5) / pieces;
                    double px = ax + t * (bx - ax);
                    double py = ay + t * (by - ay);
                    if (!inDownloadedArea(originLat + py / METRES_PER_LAT, originLon + px / metresPerLon)) {
                        continue;
                    }
                    inside++;
                    insideLength += length / pieces;
                    Hit hit = nearest(px, py, bearing, name);
                    if (hit != null) {
                        hits++;
                        perWay.merge(hit.way, 1, Integer::sum);
                        double[] d = perWayDistance.computeIfAbsent(hit.way, k -> new double[2]);
                        d[0] += hit.distance;
                        d[1] = Math.max(d[1], hit.distance);
                    }
                }
            }

            if (inside < 2 || insideLength < MIN_LENGTH) {
                continue;
            }
            compared++;
            double coverage = (double) hits / inside;
            if (coverage < MATCHED_FROM) {
                unmatched.add(new Unmatched(oa, name, insideLength * (1 - coverage), coverage));
                continue;
            }
            matched++;

            Way majority = perWay.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            for (Map.Entry<Way, Integer> entry : perWay.entrySet()) {
                OsmStats s = stats.computeIfAbsent(entry.getKey(), k -> new OsmStats());
                double[] d = perWayDistance.get(entry.getKey());
                s.samples += entry.getValue();
                s.distanceSum += d[0];
                s.distanceMax = Math.max(s.distanceMax, d[1]);
                if (entry.getKey() == majority) {
                    s.oaWays.add(oa);
                    s.oaLength += insideLength;
                    s.oaVertices += nodes.size();
                    if (!name.isEmpty()) {
                        String key = RoadNames.normalize(name);
                        s.nameVotes.merge(key, entry.getValue(), Integer::sum);
                        s.nameSpelling.putIfAbsent(key, name);
                    }
                }
            }
        }

        List<Issue> issues = new ArrayList<>(group(unmatched));
        List<Issue> names = new ArrayList<>();
        stats.forEach((osm, s) -> {
            nameIssue(osm, s, names);
            geometryIssue(osm, s, issues);
        });
        issues.addAll(groupNames(names));
        issues.sort(Comparator.comparing(Issue::type)
            .thenComparing(Comparator.comparingDouble(Issue::length).reversed()));
        return new Result(issues, compared, matched);
    }

    /**
     * Joins unmatched segments of the same road that touch each other into one issue.
     */
    private static List<Issue> group(List<Unmatched> unmatched) {
        Map<Way, Way> parent = new HashMap<>();
        Map<String, Way> byNodeAndName = new HashMap<>();
        for (Unmatched u : unmatched) {
            parent.put(u.way, u.way);
        }
        for (Unmatched u : unmatched) {
            String kind = (u.coverage < MISSING_BELOW ? "M|" : "P|") + RoadNames.normalize(u.name) + "|";
            for (Node end : List.of(u.way.firstNode(), u.way.lastNode())) {
                Way other = byNodeAndName.putIfAbsent(kind + end.getUniqueId(), u.way);
                if (other != null) {
                    parent.put(find(parent, u.way), find(parent, other));
                }
            }
        }

        Map<Way, List<Unmatched>> groups = new LinkedHashMap<>();
        for (Unmatched u : unmatched) {
            groups.computeIfAbsent(find(parent, u.way), k -> new ArrayList<>()).add(u);
        }

        List<Issue> issues = new ArrayList<>();
        for (List<Unmatched> members : groups.values()) {
            double length = members.stream().mapToDouble(Unmatched::length).sum();
            double coverage = members.stream().mapToDouble(Unmatched::coverage).average().orElse(0);
            Unmatched head = members.get(0);
            issues.add(new Issue(
                head.coverage < MISSING_BELOW ? Issue.Type.MISSING : Issue.Type.PARTIAL,
                members.stream().map(Unmatched::way).toList(), List.of(), head.name, length, coverage, ""));
        }
        return issues;
    }

    /**
     * Joins name issues for connected OSM ways that share the same pair of names.
     */
    private static List<Issue> groupNames(List<Issue> names) {
        Map<Way, Way> parent = new HashMap<>();
        Map<String, Way> byNodeAndName = new HashMap<>();
        for (Issue issue : names) {
            Way osm = issue.osmWays().get(0);
            parent.put(osm, osm);
        }
        for (Issue issue : names) {
            Way osm = issue.osmWays().get(0);
            String kind = issue.type() + "|" + RoadNames.normalize(issue.oaName()) + "|" + issue.osmName() + "|";
            for (Node node : osm.getNodes()) {
                Way other = byNodeAndName.putIfAbsent(kind + node.getUniqueId(), osm);
                if (other != null) {
                    parent.put(find(parent, osm), find(parent, other));
                }
            }
        }

        Map<Way, List<Issue>> groups = new LinkedHashMap<>();
        for (Issue issue : names) {
            groups.computeIfAbsent(find(parent, issue.osmWays().get(0)), k -> new ArrayList<>()).add(issue);
        }

        List<Issue> merged = new ArrayList<>();
        for (List<Issue> members : groups.values()) {
            Issue head = members.get(0);
            merged.add(new Issue(
                head.type(),
                members.stream().flatMap(i -> i.oaWays().stream()).distinct().toList(),
                members.stream().map(i -> i.osmWays().get(0)).toList(),
                head.oaName(), members.stream().mapToDouble(Issue::length).sum(), 0, head.note()));
        }
        return merged;
    }

    private static Way find(Map<Way, Way> parent, Way way) {
        Way root = way;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        parent.put(way, root);
        return root;
    }

    private static void nameIssue(Way osm, OsmStats s, List<Issue> issues) {
        if (s.nameVotes.isEmpty() || osm.get("highway").endsWith("_link") || "yes".equals(osm.get("noname"))) {
            return;
        }
        Map.Entry<String, Integer> top = s.nameVotes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        int total = s.nameVotes.values().stream().mapToInt(Integer::intValue).sum();
        if (top.getValue() < NAME_MIN_SAMPLES || top.getValue() * 2 < total) {
            return;
        }
        String oaName = s.nameSpelling.get(top.getKey());
        boolean osmNamed = !RoadNames.osmNames(osm).isEmpty();

        String route = RoadNames.routeNumber(oaName);
        if (route != null) {
            if (!osmNamed && osm.get("ref") == null) {
                issues.add(new Issue(Issue.Type.NAME_MISSING, s.oaWays, List.of(osm), oaName, s.oaLength, 0, tr("route label")));
            }
            return;
        }
        if (!osmNamed) {
            issues.add(new Issue(Issue.Type.NAME_MISSING, s.oaWays, List.of(osm), oaName, s.oaLength, 0, ""));
            return;
        }
        RoadNames.Comparison comparison = RoadNames.compare(oaName, osm);
        if (comparison != RoadNames.Comparison.SAME) {
            issues.add(new Issue(Issue.Type.NAME_DIFFERENT, s.oaWays, List.of(osm), oaName, s.oaLength, 0,
                comparison == RoadNames.Comparison.DIRECTION_ONLY ? tr("direction only") : ""));
        }
    }

    private static void geometryIssue(Way osm, OsmStats s, List<Issue> issues) {
        if (s.samples < GEOMETRY_MIN_SAMPLES || s.oaWays.isEmpty()) {
            return;
        }
        double mean = s.distanceSum / s.samples;
        if (mean <= GEOMETRY_OFFSET) {
            return;
        }
        double osmLength = osm.getLength();
        double osmDensity = osmLength > 0 ? osm.getNodesCount() / osmLength : 0;
        double oaDensity = s.oaLength > 0 ? s.oaVertices / s.oaLength : 0;

        List<String> notes = new ArrayList<>();
        notes.add(tr("max {0} m", Math.round(s.distanceMax)));
        if (oaDensity > osmDensity * 1.5) {
            notes.add(tr("OpenAddresses more detailed"));
        } else if (osmDensity > oaDensity * 1.5) {
            notes.add(tr("OSM more detailed"));
        }
        if ("no".equals(osm.get("tiger:reviewed"))) {
            notes.add(tr("unreviewed TIGER"));
        }
        String name = s.nameSpelling.values().stream().findFirst().orElse("");
        issues.add(new Issue(Issue.Type.GEOMETRY, s.oaWays, List.of(osm), name, s.oaLength, mean, String.join(", ", notes)));
    }
}
