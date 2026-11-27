package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.marktr;

public enum Layer {
    ADDRESSES("addresses", marktr("Addresses")),
    PARCELS("parcels", marktr("Parcels")),
    ROADS("centerlines", marktr("Roads")),
    BUILDINGS("buildings", marktr("Buildings"));

    private final String id;
    private final String label;

    Layer(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    static Layer fromId(String id) {
        for (Layer layer : values()) {
            if (layer.id.equals(id)) {
                return layer;
            }
        }
        return null;
    }
}
