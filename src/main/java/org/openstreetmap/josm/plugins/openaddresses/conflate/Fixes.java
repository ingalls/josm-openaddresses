package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.AddPrimitivesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.NodeData;
import org.openstreetmap.josm.data.osm.PrimitiveData;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WayData;
import org.openstreetmap.josm.plugins.openaddresses.TagMapper;

/**
 * Undoable edits to the OSM layer that resolve an {@link Issue}.
 */
public final class Fixes {
    private Fixes() {
    }

    /**
     * Copies the issue's OpenAddresses ways into the OSM dataset as new, unconnected ways.
     * {@code oa:} tags are dropped and the name is rewritten in OSM style.
     */
    public static Command copyToOsm(Issue issue, DataSet osm) {
        List<PrimitiveData> data = new ArrayList<>();
        List<PrimitiveData> ways = new ArrayList<>();
        Set<PrimitiveId> seen = new HashSet<>();

        for (Way way : issue.oaWays()) {
            if (!way.isUsable() || osm.getPrimitiveById(way) != null) {
                continue;
            }
            for (Node node : way.getNodes()) {
                if (seen.add(node.getPrimitiveId()) && osm.getPrimitiveById(node) == null) {
                    NodeData nodeData = node.save();
                    nodeData.removeAll();
                    data.add(nodeData);
                }
            }
            WayData wayData = way.save();
            wayData.setKeys(osmTags(way.getKeys()));
            ways.add(wayData);
        }
        if (ways.isEmpty()) {
            return null;
        }
        data.addAll(ways);
        return new AddPrimitivesCommand(data, ways, osm) {
            @Override
            public String getDescriptionText() {
                return tr("Copy OpenAddresses road {0}", issue.oaName());
            }
        };
    }

    static Map<String, String> osmTags(Map<String, String> oaTags) {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        oaTags.forEach((key, value) -> {
            if (!key.startsWith(TagMapper.PREFIX)) {
                tags.put(key, "name".equals(key) ? RoadNames.suggest(value) : value);
            }
        });
        tags.putIfAbsent("highway", "road");
        return tags;
    }

    public static Command applyName(Issue issue, String name) {
        List<Way> ways = issue.osmWays().stream().filter(Way::isUsable).toList();
        return ways.isEmpty() ? null : new ChangePropertyCommand(ways, "name", name);
    }
}
