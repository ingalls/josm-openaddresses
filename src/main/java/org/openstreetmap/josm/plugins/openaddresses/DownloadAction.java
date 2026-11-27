package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.tools.Logging;

public class DownloadAction extends JosmAction {
    public DownloadAction() {
        super(
            tr("Download OpenAddresses data..."),
            "download",
            tr("Load OpenAddresses addresses, parcels, roads or buildings as a reference layer"),
            null,
            true,
            "openaddresses/download",
            true
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Bounds view = MainApplication.isDisplayingMapView()
            ? MainApplication.getMap().mapView.getRealBounds()
            : null;

        MainApplication.worker.submit(() -> {
            if (LoginDialog.ensureToken(false) == null) {
                return;
            }
            try {
                List<Source> sources = OpenAddressesApi.listSources(view == null ? null : view.getCenter());
                GuiHelper.runInEDT(() -> choose(sources, view));
            } catch (IOException | RuntimeException ex) {
                Logging.error(ex);
                GuiHelper.runInEDT(() -> message(ex.getMessage(), JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private static void choose(List<Source> sources, Bounds view) {
        if (sources.isEmpty()) {
            message(tr("OpenAddresses has no data covering the centre of the current view."),
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        SourceDialog.Selection selection = SourceDialog.show(sources, view != null);
        if (selection != null) {
            download(selection.source(), selection.clip() ? view : null, selection.convert(), false);
        }
    }

    /**
     * Log in if needed, then download and load the source.
     *
     * @param convert map OpenAddresses properties to OSM tags
     * @param relogin the stored session was rejected, prompt for credentials again
     */
    static void download(Source source, Bounds filter, boolean convert, boolean relogin) {
        MainApplication.worker.submit(() -> {
            String token = "";
            if (relogin || !OpenAddressesApi.isCached(source)) {
                token = LoginDialog.ensureToken(relogin);
                if (token == null) {
                    return;
                }
            }
            new DownloadTask(source, token, filter, convert).run();
        });
    }

    static void message(String text, int type) {
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), text, tr("OpenAddresses"), type);
    }
}
