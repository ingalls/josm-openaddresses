package org.openstreetmap.josm.plugins.openaddresses;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TagMapperTest {
    @Test
    void addresses() {
        Map<String, String> tags = new TagMapper(Layer.ADDRESSES, "us/co/mesa").map(Map.of(
            "hash", "abc", "number", "123", "street", "MAIN ST", "unit", "4B", "city", "FRUITA",
            "district", "MESA", "region", "CO", "postcode", "81521", "accuracy", "1"));

        assertEquals(Map.of(
            "addr:housenumber", "123", "addr:street", "MAIN ST", "addr:unit", "4B", "addr:city", "FRUITA",
            "addr:county", "MESA", "addr:state", "CO", "addr:postcode", "81521",
            "oa:hash", "abc", "oa:accuracy", "1"), tags);

        Map<String, String> canada = new TagMapper(Layer.ADDRESSES, "ca/bc/province").map(Map.of(
            "district", "CAPITAL", "region", "BC"));
        assertEquals(Map.of("addr:district", "CAPITAL", "addr:province", "BC"), canada);
    }

    @Test
    void buildings() {
        assertEquals(
            Map.of("building", "yes", "height", "7.5", "building:levels", "2"),
            new TagMapper(Layer.BUILDINGS, "us/co/mesa").map(Map.of("height", "7.50", "levels", "2.0")));
        assertEquals(Map.of("building", "yes"), new TagMapper(Layer.BUILDINGS, "us/co/mesa").map(Map.of()));
    }

    @Test
    void parcels() {
        assertEquals(
            Map.of("ref", "2945-123", "oa:owners", "A SMITH; B JONES"),
            new TagMapper(Layer.PARCELS, "us/co/mesa").map(Map.of("pid", "2945-123", "owners", "A SMITH; B JONES")));
    }

    @Test
    void roads() {
        Map<String, String> tags = new TagMapper(Layer.ROADS, "us/co/mesa").map(Map.ofEntries(
            Map.entry("id", "{3A4E}"), Map.entry("name", "PATRICIA CT"), Map.entry("oneway", "no"),
            Map.entry("speed", "40"), Map.entry("classification", "residential"), Map.entry("surface", "paved"),
            Map.entry("addr_from_left", "917.0"), Map.entry("addr_to_left", "919.0"),
            Map.entry("addr_from_right", "0"), Map.entry("addr_to_right", "0"),
            Map.entry("zip_left", "81521"), Map.entry("zip_right", "81521")));

        assertEquals(Map.of(
            "highway", "residential", "name", "PATRICIA CT", "maxspeed", "25 mph", "surface", "paved",
            "postal_code", "81521", "oa:id", "{3A4E}", "oa:addr_from_left", "917", "oa:addr_to_left", "919"), tags);
    }

    @Test
    void roadsOutsideSchemaValues() {
        Map<String, String> tags = new TagMapper(Layer.ROADS, "no/countrywide").map(Map.of(
            "oneway", "reverse", "speed", "80", "classification", "ferry", "surface", "gravel",
            "zip_left", "0150", "zip_right", "0151"));

        assertEquals(Map.of(
            "highway", "road", "oneway", "-1", "maxspeed", "80",
            "postal_code:left", "0150", "postal_code:right", "0151",
            "oa:classification", "ferry", "oa:surface", "gravel"), tags);
    }
}
