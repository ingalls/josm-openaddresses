package org.openstreetmap.josm.plugins.openaddresses.conflate;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.openaddresses.OpenAddressesLayer;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Side panel listing the differences found between OpenAddresses roads and OSM.
 */
public class ConflationDialog extends ToggleDialog implements MapViewPaintable {
    private static final Color HIGHLIGHT = new Color(255, 0, 200, 150);

    private final transient MapView mapView;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);

    private final List<Issue> issues = new ArrayList<>();
    private transient OpenAddressesLayer oaLayer;
    private transient OsmDataLayer osmLayer;

    private final Action zoom = action(tr("Zoom"), "dialogs/autoscale/selection",
        tr("Zoom to the selected road and select it"), e -> zoomToSelected());
    private final Action copy = action(tr("Copy to OSM"), "copy",
        tr("Copy the OpenAddresses road into the OSM layer as a new, unconnected way"), e -> copySelected());
    private final Action rename = action(tr("Apply name"), "dialogs/edit",
        tr("Set the OSM name from the OpenAddresses name"), e -> renameSelected());
    private final Action dismiss = action(tr("Dismiss"), "dialogs/delete",
        tr("Remove the selected entry from this list"), e -> remove(selected()));

    public ConflationDialog(MapFrame frame) {
        this(frame.mapView);
    }

    ConflationDialog(MapView mapView) {
        super(tr("OpenAddresses roads"), "validator",
            tr("Differences between OpenAddresses roads and OSM"), null, 220);
        this.mapView = mapView;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> selectionChanged());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selected() != null) {
                    zoomToSelected();
                }
            }
        });

        createLayout(new JScrollPane(tree), false, Arrays.asList(
            new SideButton(zoom), new SideButton(copy), new SideButton(rename), new SideButton(dismiss)));

        if (mapView != null) {
            mapView.addTemporaryLayer(this);
        }
        rebuild();
    }

    private interface Handler {
        void run(ActionEvent e);
    }

    private static Action action(String name, String icon, String tooltip, Handler handler) {
        AbstractAction action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.run(e);
            }
        };
        new ImageProvider(icon).getResource().attachImageIcon(action, true);
        action.putValue(Action.SHORT_DESCRIPTION, tooltip);
        return action;
    }

    public void setResult(RoadMatcher.Result result, OpenAddressesLayer oa, OsmDataLayer osm) {
        this.oaLayer = oa;
        this.osmLayer = osm;
        issues.clear();
        issues.addAll(result.issues());
        rebuild();
        setTitle(tr("OpenAddresses roads: {0} of {1} segments matched", result.matched(), result.compared()));
    }

    static DefaultMutableTreeNode buildTree(DefaultMutableTreeNode root, List<Issue> issues) {
        root.removeAllChildren();
        Map<Issue.Type, DefaultMutableTreeNode> groups = new EnumMap<>(Issue.Type.class);
        for (Issue issue : issues) {
            groups.computeIfAbsent(issue.type(), type -> {
                DefaultMutableTreeNode group = new DefaultMutableTreeNode();
                root.add(group);
                return group;
            }).add(new DefaultMutableTreeNode(issue));
        }
        groups.forEach((type, group) -> group.setUserObject(
            tr(type.label()) + " (" + group.getChildCount() + ")"));
        if (issues.isEmpty()) {
            root.add(new DefaultMutableTreeNode(tr("No differences to show")));
        }
        return root;
    }

    private void rebuild() {
        List<String> expanded = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(i);
            if (tree.isExpanded(new TreePath(group.getPath()))) {
                expanded.add(prefix(group));
            }
        }
        buildTree(root, issues);
        model.reload();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(i);
            if (expanded.contains(prefix(group)) || root.getChildCount() == 1) {
                tree.expandPath(new TreePath(group.getPath()));
            }
        }
        selectionChanged();
    }

    private static String prefix(DefaultMutableTreeNode group) {
        return String.valueOf(group.getUserObject()).replaceAll(" \\(\\d+\\)$", "");
    }

    private Issue selected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof Issue issue ? issue : null;
    }

    private boolean layersAlive() {
        return oaLayer != null && osmLayer != null
            && MainApplication.getLayerManager().containsLayer(oaLayer)
            && MainApplication.getLayerManager().containsLayer(osmLayer);
    }

    private void selectionChanged() {
        Issue issue = selected();
        boolean alive = issue != null && layersAlive();
        zoom.setEnabled(alive);
        dismiss.setEnabled(issue != null);
        copy.setEnabled(alive && issue.osmWays().isEmpty());
        rename.setEnabled(alive && (issue.type() == Issue.Type.NAME_MISSING || issue.type() == Issue.Type.NAME_DIFFERENT));
        if (alive) {
            select(issue);
        }
        if (mapView != null) {
            mapView.repaint();
        }
    }

    private void select(Issue issue) {
        List<Way> osm = issue.osmWays().stream().filter(Way::isUsable).toList();
        if (!osm.isEmpty()) {
            osmLayer.getDataSet().setSelected(osm);
        }
        oaLayer.getDataSet().setSelected(issue.oaWays().stream().filter(Way::isUsable).toList());
    }

    private void zoomToSelected() {
        Issue issue = selected();
        if (issue == null || !layersAlive()) {
            return;
        }
        select(issue);
        List<OsmPrimitive> targets = new ArrayList<>(issue.oaWays());
        targets.addAll(issue.osmWays());
        targets.removeIf(p -> !p.isUsable());
        if (!targets.isEmpty()) {
            AutoScaleAction.zoomTo(targets);
        }
    }

    private void copySelected() {
        Issue issue = selected();
        if (issue == null || !layersAlive()) {
            return;
        }
        Command command = Fixes.copyToOsm(issue, osmLayer.getDataSet());
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
            MainApplication.getLayerManager().setActiveLayer(osmLayer);
        }
        remove(issue);
    }

    private void renameSelected() {
        Issue issue = selected();
        if (issue == null || !layersAlive()) {
            return;
        }
        int count = issue.osmWays().size();
        Object answer = JOptionPane.showInputDialog(
            MainApplication.getMainFrame(),
            trn("Name for {0} OSM way (OpenAddresses: {1}, OSM: {2})", "Name for {0} OSM ways (OpenAddresses: {1}, OSM: {2})",
                count, count, issue.oaName(), issue.osmName().isEmpty() ? tr("none") : issue.osmName()),
            tr("Apply name"), JOptionPane.QUESTION_MESSAGE, null, null, RoadNames.suggest(issue.oaName()));
        if (answer == null || answer.toString().isBlank()) {
            return;
        }
        Command command = Fixes.applyName(issue, answer.toString().trim());
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
        remove(issue);
    }

    private void remove(Issue issue) {
        if (issue == null) {
            return;
        }
        int row = tree.getMinSelectionRow();
        issues.remove(issue);
        rebuild();
        if (row >= 0 && tree.getRowCount() > 0) {
            tree.setSelectionRow(Math.min(row, tree.getRowCount() - 1));
        }
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        Issue issue = selected();
        if (issue == null || !isDialogShowing()) {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(HIGHLIGHT);
        g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Way way : issue.oaWays()) {
            if (!way.isUsable()) {
                continue;
            }
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (Node node : way.getNodes()) {
                Point p = mv.getPoint(node);
                if (first) {
                    path.moveTo(p.x, p.y);
                    first = false;
                } else {
                    path.lineTo(p.x, p.y);
                }
            }
            g.draw(path);
        }
    }

    @Override
    public void destroy() {
        if (mapView != null) {
            mapView.removeTemporaryLayer(this);
        }
        super.destroy();
    }
}
