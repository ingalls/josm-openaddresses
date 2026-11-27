package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.List;

import org.openstreetmap.josm.data.osm.Way;

/**
 * One finding from comparing OpenAddresses roads with OSM.
 *
 * @param oaWays the OpenAddresses segments involved
 * @param osmWays the matched OSM ways, empty when OSM has nothing there
 * @param oaName OpenAddresses name, may be empty
 * @param length metres of OpenAddresses road involved
 * @param value  coverage fraction for missing roads, mean offset in metres for geometry
 */
public record Issue(Type type, List<Way> oaWays, List<Way> osmWays, String oaName, double length, double value, String note) {
    public enum Type {
        MISSING(marktr("Missing from OSM")),
        PARTIAL(marktr("Partly missing from OSM")),
        NAME_MISSING(marktr("Name missing in OSM")),
        NAME_DIFFERENT(marktr("Name differs")),
        GEOMETRY(marktr("Geometry differs"));

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public String osmName() {
        return osmWays.isEmpty() || osmWays.get(0).get("name") == null ? "" : osmWays.get(0).get("name");
    }

    private String displayName() {
        return oaName.isEmpty() ? tr("(unnamed)") : oaName;
    }

    @Override
    public String toString() {
        String text = switch (type) {
            case MISSING -> tr("{0} – {1} m", displayName(), Math.round(length));
            case PARTIAL -> tr("{0} – {1} m, {2}% in OSM", displayName(), Math.round(length), Math.round(value * 100));
            case NAME_MISSING -> tr("{0} – unnamed {1}", displayName(), osmWays.get(0).get("highway"));
            case NAME_DIFFERENT -> tr("{0} – OSM: {1}", displayName(), osmName());
            case GEOMETRY -> tr("{0} – {1} m apart on average",
                oaName.isEmpty() ? (osmName().isEmpty() ? tr("(unnamed)") : osmName()) : oaName,
                String.format("%.1f", value));
        };
        return note.isEmpty() ? text : text + " (" + note + ")";
    }
}
