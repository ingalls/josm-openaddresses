package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.tr;

import jakarta.json.JsonObject;

/**
 * A dataset as listed by the OpenAddresses batch API.
 */
public record Source(long id, long job, Layer layer, String source, String name, String updated, long size) {
    static Source fromJson(JsonObject o, Layer layer) {
        return new Source(
            o.getJsonNumber("id").longValue(),
            o.getJsonNumber("job").longValue(),
            layer,
            o.getString("source"),
            o.getString("name"),
            o.getString("updated", ""),
            o.containsKey("size") && !o.isNull("size") ? o.getJsonNumber("size").longValue() : 0
        );
    }

    String layerName() {
        return "OpenAddresses: " + source + " " + layer.id() + " (" + name + ")";
    }

    String updatedDate() {
        return updated.length() >= 10 ? updated.substring(0, 10) : updated;
    }

    String sizeText() {
        return size >= 1_000_000
            ? String.format("%.1f MB", size / 1_000_000.0)
            : String.format("%d kB", Math.max(1, size / 1_000));
    }

    @Override
    public String toString() {
        return String.format("%s – %s – %s – %s – %s", source, tr(layer.label()), name, updatedDate(), sizeText());
    }
}
