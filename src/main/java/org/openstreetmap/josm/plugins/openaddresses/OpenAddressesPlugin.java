package org.openstreetmap.josm.plugins.openaddresses;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.openaddresses.conflate.CompareRoadsAction;
import org.openstreetmap.josm.plugins.openaddresses.conflate.ConflationDialog;

public class OpenAddressesPlugin extends Plugin {
    private static ConflationDialog conflationDialog;

    public OpenAddressesPlugin(PluginInformation info) {
        super(info);
        MainMenu.add(MainApplication.getMenu().dataMenu, new DownloadAction());
        MainMenu.add(MainApplication.getMenu().dataMenu, new CompareRoadsAction());
        if (MainApplication.getMap() != null) {
            mapFrameInitialized(null, MainApplication.getMap());
        }
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame == null) {
            setConflationDialog(null);
        } else {
            ConflationDialog dialog = new ConflationDialog(newFrame);
            newFrame.addToggleDialog(dialog);
            setConflationDialog(dialog);
        }
    }

    private static void setConflationDialog(ConflationDialog dialog) {
        conflationDialog = dialog;
    }

    public static ConflationDialog getConflationDialog() {
        return conflationDialog;
    }
}
