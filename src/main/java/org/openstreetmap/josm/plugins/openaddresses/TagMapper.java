package org.openstreetmap.josm.plugins.openaddresses;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts OpenAddresses schema properties into OSM tags.
 * Properties without an OSM equivalent are kept under the {@code oa:} prefix.
 */
public final class TagMapper {
    public static final String PREFIX = "oa:";

    private static final double KPH_PER_MPH = 1.60934;
    private static final Set<String> MPH_COUNTRIES = Set.of("us", "gb", "pr", "vi", "gu", "as", "mp", "lr", "mm");
    private static final Set<String> STATE_COUNTRIES = Set.of("us", "au", "br", "mx", "in", "my", "ng", "ve");
    private static final Set<String> HIGHWAY_CLASSES = Set.of(
        "motorway", "trunk", "primary", "secondary", "tertiary", "residential", "service", "unclassified");
    private static final Set<String> SURFACES = Set.of("paved", "unpaved");

    private final Layer layer;
    private final String country;

    public TagMapper(Source source) {
        this(source.layer(), source.source());
    }

    TagMapper(Layer layer, String sourceName) {
        this.layer = layer;
        int slash = sourceName.indexOf('/');
        this.country = (slash < 0 ? sourceName : sourceName.substring(0, slash)).toLowerCase(java.util.Locale.ROOT);
    }

    public Map<String, String> map(Map<String, String> properties) {
        Map<String, String> raw = new LinkedHashMap<>(properties);
        Map<String, String> tags = new LinkedHashMap<>();

        switch (layer) {
            case ADDRESSES -> addresses(raw, tags);
            case BUILDINGS -> buildings(raw, tags);
            case PARCELS -> parcels(raw, tags);
            case ROADS -> roads(raw, tags);
            default -> {
            }
        }

        raw.forEach((key, value) -> tags.put(PREFIX + key, value));
        return tags;
    }

    private void addresses(Map<String, String> raw, Map<String, String> tags) {
        move(raw, tags, "number", "addr:housenumber");
        move(raw, tags, "street", "addr:street");
        move(raw, tags, "unit", "addr:unit");
        move(raw, tags, "city", "addr:city");
        move(raw, tags, "district", "us".equals(country) ? "addr:county" : "addr:district");
        move(raw, tags, "region", STATE_COUNTRIES.contains(country) ? "addr:state" : "addr:province");
        move(raw, tags, "postcode", "addr:postcode");
    }

    private void buildings(Map<String, String> raw, Map<String, String> tags) {
        tags.put("building", "yes");
        String height = number(raw.remove("height"));
        if (height != null) {
            tags.put("height", height);
        }
        String levels = number(raw.remove("levels"));
        if (levels != null) {
            tags.put("building:levels", levels);
        }
    }

    private void parcels(Map<String, String> raw, Map<String, String> tags) {
        move(raw, tags, "pid", "ref");
    }

    private void roads(Map<String, String> raw, Map<String, String> tags) {
        String classification = raw.remove("classification");
        if (classification != null && HIGHWAY_CLASSES.contains(classification)) {
            tags.put("highway", classification);
        } else {
            tags.put("highway", "road");
            if (classification != null) {
                raw.put("classification", classification);
            }
        }

        move(raw, tags, "name", "name");

        String oneway = raw.remove("oneway");
        if ("yes".equals(oneway)) {
            tags.put("oneway", "yes");
        } else if ("reverse".equals(oneway)) {
            tags.put("oneway", "-1");
        } else if (oneway != null && !"no".equals(oneway)) {
            raw.put("oneway", oneway);
        }

        String speed = maxspeed(raw.get("speed"));
        if (speed != null) {
            raw.remove("speed");
            tags.put("maxspeed", speed);
        }

        String surface = raw.get("surface");
        if (surface != null && SURFACES.contains(surface)) {
            raw.remove("surface");
            tags.put("surface", surface);
        }

        String left = raw.remove("zip_left");
        String right = raw.remove("zip_right");
        if (left != null && left.equals(right)) {
            tags.put("postal_code", left);
        } else {
            if (left != null) {
                tags.put("postal_code:left", left);
            }
            if (right != null) {
                tags.put("postal_code:right", right);
            }
        }

        for (String key : new String[] {"addr_from_left", "addr_to_left", "addr_from_right", "addr_to_right"}) {
            String value = number(raw.remove(key));
            if (value != null && !"0".equals(value)) {
                raw.put(key, value);
            }
        }
    }

    private String maxspeed(String value) {
        Double kph = parse(value);
        if (kph == null || kph <= 0) {
            return null;
        }
        if (MPH_COUNTRIES.contains(country)) {
            long mph = Math.round(kph / KPH_PER_MPH);
            if (mph > 0 && Math.round(mph * KPH_PER_MPH) == Math.round(kph)) {
                return mph + " mph";
            }
        }
        return Long.toString(Math.round(kph));
    }

    private static void move(Map<String, String> raw, Map<String, String> tags, String from, String to) {
        String value = raw.remove(from);
        if (value != null) {
            tags.put(to, value);
        }
    }

    private static Double parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String number(String value) {
        Double number = parse(value);
        if (number == null) {
            return value;
        }
        if (number == Math.rint(number)) {
            return Long.toString(number.longValue());
        }
        return java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }
}
