package org.openstreetmap.josm.plugins.openaddresses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.Action;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.openaddresses.conflate.CompareRoadsAction;

/**
 * Upload-blocked reference layer holding one OpenAddresses source.
 */
public class OpenAddressesLayer extends OsmDataLayer {
    private final Source source;

    public OpenAddressesLayer(DataSet data, Source source) {
        super(data, source.layerName(), null);
        this.source = source;
        setUploadDiscouraged(true);
    }

    public Source getSource() {
        return source;
    }

    public boolean isRoads() {
        return source.layer() == Layer.ROADS;
    }

    @Override
    public Action[] getMenuEntries() {
        List<Action> entries = new ArrayList<>();
        if (isRoads()) {
            entries.add(new CompareRoadsAction(this));
            entries.add(SeparatorLayerAction.INSTANCE);
        }
        entries.addAll(Arrays.asList(super.getMenuEntries()));
        return entries.toArray(new Action[0]);
    }
}
