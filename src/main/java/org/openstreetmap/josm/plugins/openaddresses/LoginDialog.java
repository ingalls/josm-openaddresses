package org.openstreetmap.josm.plugins.openaddresses;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.io.IOException;
import java.net.URL;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmPasswordField;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.gui.widgets.UrlLabel;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;

public final class LoginDialog {
    private static final int LOGO_SIZE = 128;

    private record Credentials(String username, char[] password) {
    }

    private LoginDialog() {
    }

    /**
     * Returns a session token, prompting for credentials when none is stored.
     * Must be called off the EDT.
     *
     * @param force discard any stored token and prompt again
     * @return the token, or null if the user cancelled
     */
    public static String ensureToken(boolean force) {
        if (force) {
            Config.getPref().put(OpenAddressesApi.PREF_TOKEN, null);
        }
        String token = Config.getPref().get(OpenAddressesApi.PREF_TOKEN, "");
        if (!token.isBlank()) {
            return token;
        }

        String error = force ? tr("Your session has expired, please log in again.") : null;
        while (true) {
            String message = error;
            Credentials credentials = GuiHelper.runInEDTAndWaitAndReturn(() -> prompt(message));
            if (credentials == null) {
                return null;
            }
            try {
                token = OpenAddressesApi.login(credentials.username(), new String(credentials.password()));
                Config.getPref().put(OpenAddressesApi.PREF_USERNAME, credentials.username());
                Config.getPref().put(OpenAddressesApi.PREF_TOKEN, token);
                return token;
            } catch (IOException e) {
                Logging.warn(e);
                error = e.getMessage();
            } finally {
                java.util.Arrays.fill(credentials.password(), '\0');
            }
        }
    }

    private static Credentials prompt(String error) {
        JosmTextField username = new JosmTextField(Config.getPref().get(OpenAddressesApi.PREF_USERNAME, ""), 24);
        JosmPasswordField password = new JosmPasswordField(24);

        ExtendedDialog dialog = new ExtendedDialog(
            MainApplication.getMainFrame(),
            tr("OpenAddresses login"),
            tr("Log in"), tr("Cancel")
        );
        dialog.setButtonIcons("ok", "cancel");
        dialog.setContent(panel(username, password, error), false);
        dialog.setDefaultButton(1);
        dialog.setFocusOnDefaultButton(false);
        dialog.setupDialog();
        (username.getText().isEmpty() ? username : password).requestFocusInWindow();

        if (dialog.showDialog().getValue() != 1 || username.getText().isBlank()) {
            return null;
        }
        return new Credentials(username.getText().trim(), password.getPassword());
    }

    static JPanel panel(JosmTextField username, JosmPasswordField password, String error) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(logo(), GBC.eol().anchor(GBC.CENTER).insets(0, 8, 0, 8));

        JLabel title = new JLabel(tr("Log in to OpenAddresses"), SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 4f));
        panel.add(title, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 0, 0, 12));

        if (error != null) {
            JLabel errorLabel = new JLabel(error, SwingConstants.CENTER);
            errorLabel.setForeground(new Color(0xC0, 0x39, 0x2B));
            panel.add(errorLabel, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 0, 0, 12));
        }

        panel.add(new JLabel(tr("Username or email:")), GBC.std().insets(0, 0, 8, 6));
        panel.add(username, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 0, 0, 6));
        panel.add(new JLabel(tr("Password:")), GBC.std().insets(0, 0, 8, 6));
        panel.add(password, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 0, 0, 6));

        JPanel links = new JPanel();
        links.add(new UrlLabel(OpenAddressesApi.siteUrl() + "/register", tr("Create an account")));
        links.add(new UrlLabel(OpenAddressesApi.siteUrl() + "/login/forgot", tr("Forgot password")));
        panel.add(links, GBC.eol().anchor(GBC.CENTER).insets(0, 8, 0, 0));
        return panel;
    }

    private static JLabel logo() {
        URL url = LoginDialog.class.getResource("/images/openaddresses.jpg");
        if (url == null) {
            return new JLabel();
        }
        Image image = new ImageIcon(url).getImage().getScaledInstance(LOGO_SIZE, LOGO_SIZE, Image.SCALE_SMOOTH);
        return new JLabel(new ImageIcon(image));
    }
}
