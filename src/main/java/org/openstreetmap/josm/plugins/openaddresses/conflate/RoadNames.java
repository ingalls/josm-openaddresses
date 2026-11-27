package org.openstreetmap.josm.plugins.openaddresses.conflate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.osm.Tagged;

/**
 * Street name comparison between OpenAddresses (upper case, abbreviated) and OSM (spelled out).
 */
public final class RoadNames {
    static final String[] OSM_NAME_KEYS = {
        "name", "alt_name", "old_name", "official_name", "short_name", "loc_name", "name_1", "name_2"};

    private static final Map<String, String> DIRECTIONS = Map.of(
        "N", "NORTH", "S", "SOUTH", "E", "EAST", "W", "WEST",
        "NE", "NORTHEAST", "NW", "NORTHWEST", "SE", "SOUTHEAST", "SW", "SOUTHWEST");

    private static final Set<String> DIRECTION_WORDS = Set.copyOf(DIRECTIONS.values());

    private static final Map<String, String> SUFFIXES = Map.ofEntries(
        Map.entry("ST", "STREET"), Map.entry("AVE", "AVENUE"), Map.entry("AV", "AVENUE"),
        Map.entry("RD", "ROAD"), Map.entry("DR", "DRIVE"), Map.entry("CT", "COURT"),
        Map.entry("LN", "LANE"), Map.entry("BLVD", "BOULEVARD"), Map.entry("PL", "PLACE"),
        Map.entry("CIR", "CIRCLE"), Map.entry("HWY", "HIGHWAY"), Map.entry("PKWY", "PARKWAY"),
        Map.entry("TRL", "TRAIL"), Map.entry("TER", "TERRACE"), Map.entry("SQ", "SQUARE"),
        Map.entry("ALY", "ALLEY"), Map.entry("XING", "CROSSING"), Map.entry("EXPY", "EXPRESSWAY"),
        Map.entry("FWY", "FREEWAY"), Map.entry("PT", "POINT"), Map.entry("RDG", "RIDGE"),
        Map.entry("HTS", "HEIGHTS"), Map.entry("CRK", "CREEK"), Map.entry("CYN", "CANYON"),
        Map.entry("MTN", "MOUNTAIN"), Map.entry("MT", "MOUNT"), Map.entry("FT", "FORT"),
        Map.entry("VW", "VIEW"), Map.entry("VLY", "VALLEY"), Map.entry("HL", "HILL"),
        Map.entry("HLS", "HILLS"), Map.entry("LK", "LAKE"), Map.entry("MDW", "MEADOW"),
        Map.entry("MDWS", "MEADOWS"), Map.entry("SPG", "SPRING"), Map.entry("SPGS", "SPRINGS"),
        Map.entry("EST", "ESTATE"), Map.entry("ESTS", "ESTATES"), Map.entry("GLN", "GLEN"),
        Map.entry("GRV", "GROVE"), Map.entry("JCT", "JUNCTION"), Map.entry("PLZ", "PLAZA"),
        Map.entry("RTE", "ROUTE"), Map.entry("CV", "COVE"), Map.entry("BND", "BEND"),
        Map.entry("CRES", "CRESCENT"), Map.entry("CTR", "CENTER"), Map.entry("TRCE", "TRACE"),
        Map.entry("CO", "COUNTY"), Map.entry("CORD", "COUNTY ROAD"), Map.entry("CR", "COUNTY ROAD"),
        Map.entry("STHY", "STATE HIGHWAY"), Map.entry("USHY", "US HIGHWAY"));

    private static final Pattern ROUTE = Pattern.compile(
        "^(?:(?:N|S|E|W|NORTH|SOUTH|EAST|WEST) )?(?:CORD|CO RD|CR|COUNTY ROAD|COUNTY RD|STHY|SH|SR|STATE HIGHWAY|STATE HWY|STATE ROUTE|STATE RTE"
            + "|USHY|US|US HIGHWAY|US HWY|HIGHWAY|HWY|I|INTERSTATE|RTE|ROUTE|FR|FSR|FOREST ROAD|FOREST RD)"
            + " ?([0-9]+[A-Z]?)$");

    private static final Pattern ORDINAL = Pattern.compile("^[0-9]+(ST|ND|RD|TH)$");

    private RoadNames() {
    }

    private static List<String> tokens(String name) {
        List<String> tokens = new ArrayList<>();
        String cleaned = name.toUpperCase(Locale.ROOT).replaceAll("['\u2019]", "").replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (String token : cleaned.trim().split(" ")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static List<String> expand(String name) {
        List<String> tokens = tokens(name);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("ST".equals(token) && i == 0) {
                tokens.set(i, "SAINT");
            } else if (DIRECTIONS.containsKey(token)) {
                tokens.set(i, DIRECTIONS.get(token));
            } else if (SUFFIXES.containsKey(token)) {
                tokens.set(i, SUFFIXES.get(token));
            }
        }
        return tokens;
    }

    /**
     * Canonical form used for comparison only.
     */
    public static String normalize(String name) {
        return name == null ? "" : String.join(" ", expand(name));
    }

    /**
     * The route number when the name is a route label like {@code CORD 210} or {@code STHY 13}, else null.
     */
    public static String routeNumber(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = ROUTE.matcher(String.join(" ", tokens(name)));
        return matcher.matches() ? matcher.group(1) : null;
    }

    static boolean hasRouteNumber(Tagged osm, String number) {
        for (String key : new String[] {"ref", "name", "alt_name", "official_name", "old_ref", "loc_ref", "unsigned_ref"}) {
            String value = osm.get(key);
            if (value != null && tokens(value).contains(number)) {
                return true;
            }
        }
        return false;
    }

    static List<String> osmNames(Tagged osm) {
        List<String> names = new ArrayList<>();
        for (String key : OSM_NAME_KEYS) {
            String value = osm.get(key);
            if (value != null) {
                for (String part : value.split(";")) {
                    if (!part.isBlank()) {
                        names.add(part.trim());
                    }
                }
            }
        }
        return names;
    }

    public enum Comparison { SAME, DIRECTION_ONLY, DIFFERENT }

    public static Comparison compare(String oaName, Tagged osm) {
        String oa = normalize(oaName);
        Comparison best = Comparison.DIFFERENT;
        for (String name : osmNames(osm)) {
            String other = normalize(name);
            if (oa.replace(" ", "").equals(other.replace(" ", ""))) {
                return Comparison.SAME;
            }
            if (directionOnly(oa, other)) {
                best = Comparison.DIRECTION_ONLY;
            }
        }
        return best;
    }

    static boolean directionOnly(String a, String b) {
        Set<String> left = new HashSet<>(List.of(a.split(" ")));
        Set<String> right = new HashSet<>(List.of(b.split(" ")));
        Set<String> onlyLeft = new HashSet<>(left);
        onlyLeft.removeAll(right);
        Set<String> onlyRight = new HashSet<>(right);
        onlyRight.removeAll(left);
        left.retainAll(right);
        return !left.isEmpty() && DIRECTION_WORDS.containsAll(onlyLeft) && DIRECTION_WORDS.containsAll(onlyRight);
    }

    /**
     * OSM-style spelling of an OpenAddresses name: abbreviations expanded and title case.
     * A suggestion for a person to review, not a guaranteed correct name.
     */
    public static String suggest(String oaName) {
        List<String> tokens = tokens(oaName);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean edge = i == 0 || i == tokens.size() - 1;
            if ("ST".equals(token) && i == 0) {
                token = "SAINT";
            } else if (edge && DIRECTIONS.containsKey(token) && tokens.size() > 1) {
                token = DIRECTIONS.get(token);
            } else if (SUFFIXES.containsKey(token) && (i > 0 && !"CO".equals(token) || token.length() == 4 && token.endsWith("HY") || "CORD".equals(token))) {
                token = SUFFIXES.get(token);
            }
            for (String word : token.split(" ")) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                out.append("US".equals(word) ? word : titleCase(word));
            }
        }
        return out.toString();
    }

    private static String titleCase(String token) {
        if (ORDINAL.matcher(token).matches()) {
            return token.toLowerCase(Locale.ROOT);
        }
        if (token.length() > 2 && token.startsWith("MC")) {
            return "Mc" + token.charAt(2) + token.substring(3).toLowerCase(Locale.ROOT);
        }
        return token.charAt(0) + token.substring(1).toLowerCase(Locale.ROOT);
    }
}
