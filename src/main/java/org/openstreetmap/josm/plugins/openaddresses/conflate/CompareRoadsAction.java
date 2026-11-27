package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.openaddresses.OpenAddressesLayer;
import org.openstreetmap.josm.plugins.openaddresses.OpenAddressesPlugin;

/**
 * Compares an OpenAddresses roads layer with the OSM data layer and lists the differences.
 */
public class CompareRoadsAction extends JosmAction {
    private final transient OpenAddressesLayer fixedLayer;

    public CompareRoadsAction() {
        this(null);
    }

    public CompareRoadsAction(OpenAddressesLayer layer) {
        super(
            tr("Compare OpenAddresses roads with OSM"),
            "dialogs/validator",
            tr("Find roads that are missing, named differently or drawn differently in OSM"),
            null,
            layer == null,
            "openaddresses/compare-roads",
            false
        );
        this.fixedLayer = layer;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        OpenAddressesLayer oa = fixedLayer != null ? fixedLayer : findRoadsLayer();
        if (oa == null) {
            message(tr("Download an OpenAddresses roads layer first."));
            return;
        }
        OsmDataLayer osm = findOsmLayer();
        if (osm == null) {
            message(tr("Download OSM data for the area first."));
            return;
        }
        MainApplication.worker.submit(new CompareTask(oa, osm));
    }

    static OpenAddressesLayer findRoadsLayer() {
        Layer active = MainApplication.getLayerManager().getActiveLayer();
        if (active instanceof OpenAddressesLayer layer && layer.isRoads()) {
            return layer;
        }
        return MainApplication.getLayerManager().getLayersOfType(OpenAddressesLayer.class).stream()
            .filter(OpenAddressesLayer::isRoads).findFirst().orElse(null);
    }

    static OsmDataLayer findOsmLayer() {
        OsmDataLayer edit = MainApplication.getLayerManager().getEditLayer();
        if (edit != null && !(edit instanceof OpenAddressesLayer)) {
            return edit;
        }
        List<OsmDataLayer> layers = MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class);
        return layers.stream().filter(l -> !(l instanceof OpenAddressesLayer)).findFirst().orElse(null);
    }

    private static void message(String text) {
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), text, tr("OpenAddresses"),
            JOptionPane.INFORMATION_MESSAGE);
    }

    private static final class CompareTask extends PleaseWaitRunnable {
        private final OpenAddressesLayer oa;
        private final OsmDataLayer osm;
        private RoadMatcher.Result result;

        CompareTask(OpenAddressesLayer oa, OsmDataLayer osm) {
            super(tr("Comparing OpenAddresses roads with OSM"));
            this.oa = oa;
            this.osm = osm;
        }

        @Override
        protected void realRun() {
            progressMonitor.indeterminateSubTask(tr("Matching roads"));
            result = RoadMatcher.match(oa.getDataSet().getWays(), osm.getDataSet(), progressMonitor);
        }

        @Override
        protected void finish() {
            if (result == null) {
                return;
            }
            ConflationDialog dialog = OpenAddressesPlugin.getConflationDialog();
            if (dialog != null) {
                dialog.setResult(result, oa, osm);
                dialog.showDialog();
                dialog.unfurlDialog();
            }
        }

        @Override
        protected void cancel() {
            // the matcher polls the progress monitor
        }
    }
}
