package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.tools.Logging;

import javax.swing.JOptionPane;

public class DownloadTask extends PleaseWaitRunnable {
    private final Source source;
    private final String token;
    private final Bounds filter;
    private final boolean convert;

    private volatile boolean canceled;
    private boolean authRejected;
    private IOException error;
    private DataSet result;

    public DownloadTask(Source source, String token, Bounds filter, boolean convert) {
        super(tr("Downloading OpenAddresses data"));
        this.source = source;
        this.token = token;
        this.filter = filter;
        this.convert = convert;
    }

    @Override
    protected void realRun() {
        try {
            load();
        } catch (OpenAddressesApi.AuthRequiredException e) {
            authRejected = true;
        } catch (IOException e) {
            Logging.warn(e);
            error = e;
        }
    }

    private void load() throws IOException {
        progressMonitor.indeterminateSubTask(tr("Downloading {0}", source.source()));
        Path file = OpenAddressesApi.fetchOutput(source, token, progressMonitor.createSubTaskMonitor(0, false));
        if (canceled) {
            return;
        }

        progressMonitor.indeterminateSubTask(tr("Reading {0}", source.layer().id()));
        progressMonitor.setCustomText("");
        try (InputStream in = Files.newInputStream(file)) {
            result = FeatureReader.read(in, filter, convert ? new TagMapper(source) : null, progressMonitor);
        }
    }

    @Override
    protected void finish() {
        if (authRejected && !canceled) {
            DownloadAction.download(source, filter, convert, true);
            return;
        }
        if (error != null && !canceled) {
            DownloadAction.message(error.getMessage(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (canceled || result == null) {
            return;
        }
        if (FeatureReader.isEmpty(result)) {
            new Notification(tr("{0} has no {1} in the current view", source.source(), source.layer().id()))
                .setIcon(JOptionPane.INFORMATION_MESSAGE)
                .show();
            return;
        }

        MainApplication.getLayerManager().addLayer(new OpenAddressesLayer(result, source));
    }

    @Override
    protected void cancel() {
        canceled = true;
    }
}
