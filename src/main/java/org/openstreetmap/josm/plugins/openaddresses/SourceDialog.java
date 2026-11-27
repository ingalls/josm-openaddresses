package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;

/**
 * Source picker with layer-type toggles and a name filter.
 */
final class SourceDialog {
    static final String PREF_LAYERS = "openaddresses.layers";
    static final String PREF_CONVERT = "openaddresses.convert-tags";

    record Selection(Source source, boolean clip, boolean convert) {
    }

    private final List<Source> sources;
    private final Map<Layer, JToggleButton> toggles = new EnumMap<>(Layer.class);
    private final JosmTextField search = new JosmTextField(20);
    private final DefaultListModel<Source> model = new DefaultListModel<>();
    private final JList<Source> list = new JList<>(model);
    private final JLabel count = new JLabel();
    private final JCheckBox convert = new JCheckBox(tr("Convert attributes to OSM tags"));
    private final JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

    private SourceDialog(List<Source> sources) {
        this.sources = sources;
    }

    /**
     * @param inView whether the sources were limited to the current map view
     * @return the chosen source, or null if cancelled
     */
    static Selection show(List<Source> sources, boolean inView) {
        return new SourceDialog(sources).open(inView);
    }

    private Selection open(boolean inView) {
        JCheckBox clip = controls(inView);

        PickerDialog dialog = new PickerDialog();
        dialog.setButtonIcons("download", "cancel");
        dialog.setContent(panel(inView, clip), false);
        dialog.setDefaultButton(1);
        dialog.setupDialog();

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    dialog.accept();
                }
            }
        });

        if (dialog.showDialog().getValue() != 1 || list.getSelectedValue() == null) {
            return null;
        }

        List<String> remember = new ArrayList<>();
        toggles.forEach((layer, toggle) -> {
            if (toggle.isSelected() || !toggle.isEnabled()) {
                remember.add(layer.id());
            }
        });
        Config.getPref().putList(PREF_LAYERS, remember);

        Config.getPref().putBoolean(PREF_CONVERT, convert.isSelected());

        return new Selection(list.getSelectedValue(), clip.isSelected(), convert.isSelected());
    }

    private JCheckBox controls(boolean inView) {
        List<String> enabled = Config.getPref().getList(PREF_LAYERS, null);

        for (Layer layer : Layer.values()) {
            long total = sources.stream().filter(s -> s.layer() == layer).count();
            JToggleButton toggle = new JToggleButton(tr(layer.label()) + " (" + total + ")");
            toggle.setSelected(total > 0 && (enabled == null || enabled.contains(layer.id())));
            toggle.setEnabled(total > 0);
            toggle.setFocusPainted(false);
            toggle.addActionListener(e -> refresh());
            toggles.put(layer, toggle);
            filters.add(toggle);
        }

        search.setHint(tr("Filter by source name"));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SourceRenderer());

        JCheckBox clip = new JCheckBox(tr("Only load data in the current view"), inView);
        clip.setEnabled(inView);
        convert.setSelected(Config.getPref().getBoolean(PREF_CONVERT, true));

        refresh();
        return clip;
    }

    private static final class PickerDialog extends ExtendedDialog {
        PickerDialog() {
            super(MainApplication.getMainFrame(), tr("Download OpenAddresses data"), tr("Download"), tr("Cancel"));
        }

        void accept() {
            buttonAction(0, null);
        }
    }

    private JPanel panel(boolean inView, JCheckBox clip) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(inView
            ? tr("OpenAddresses data covering the current view")
            : tr("All OpenAddresses data")), GBC.eol().insets(0, 0, 0, 8));
        panel.add(filters, GBC.eol().fill(GBC.HORIZONTAL).insets(-4, 0, 0, 6));
        panel.add(search, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 0, 0, 6));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 280));
        panel.add(scroll, GBC.eol().fill());
        panel.add(clip, GBC.std().insets(0, 8, 0, 0));
        panel.add(count, GBC.eol().anchor(GBC.EAST).insets(0, 8, 0, 0));
        panel.add(convert, GBC.eol());
        return panel;
    }

    private void refresh() {
        Source selected = list.getSelectedValue();
        String text = search.getText().trim().toLowerCase(Locale.ROOT);

        model.clear();
        for (Source source : sources) {
            if (toggles.get(source.layer()).isSelected()
                    && (text.isEmpty() || source.source().toLowerCase(Locale.ROOT).contains(text))) {
                model.addElement(source);
            }
        }

        if (selected != null && model.contains(selected)) {
            list.setSelectedValue(selected, true);
        } else if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
        count.setText(tr("{0} of {1}", model.size(), sources.size()));
    }

    static String html(Source source) {
        return "<html><b>" + escape(source.source()) + "</b> &nbsp;"
            + escape(tr(source.layer().label())) + " &middot; "
            + escape(source.name()) + " &middot; "
            + escape(source.updatedDate()) + " &middot; "
            + escape(source.sizeText()) + "</html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class SourceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            label.setText(html((Source) value));
            label.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return label;
        }
    }
}
