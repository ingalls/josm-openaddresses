package org.openstreetmap.josm.plugins.openaddresses;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.HttpClient;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

public final class OpenAddressesApi {
    public static final String PREF_URL = "openaddresses.url";
    public static final String PREF_TOKEN = "openaddresses.token";
    public static final String PREF_USERNAME = "openaddresses.username";
    public static final String DEFAULT_URL = "https://batch.openaddresses.io/api";

    private OpenAddressesApi() {
    }

    static String baseUrl() {
        return Config.getPref().get(PREF_URL, DEFAULT_URL);
    }

    static String siteUrl() {
        return baseUrl().replaceFirst("/api/?$", "");
    }

    public static final class AuthRequiredException extends IOException {
        AuthRequiredException(String message) {
            super(message);
        }
    }

    /**
     * Exchange credentials for a session token.
     */
    public static String login(String username, String password) throws IOException {
        String body = Json.createObjectBuilder()
            .add("username", username)
            .add("password", password)
            .build()
            .toString();

        HttpClient.Response res = HttpClient.create(URI.create(baseUrl() + "/login").toURL(), "POST")
            .setHeader("Content-Type", "application/json")
            .setAccept("application/json")
            .setRequestBody(body.getBytes(StandardCharsets.UTF_8))
            .setLogAtDebug(true)
            .connect();
        try {
            String content = res.fetchContent();
            if (res.getResponseCode() != 200) {
                throw new IOException(apiMessage(content, "Login failed (" + res.getResponseCode() + ")"));
            }
            try (JsonReader reader = Json.createReader(new StringReader(content))) {
                String token = reader.readObject().getString("token", "");
                if (token.isEmpty()) {
                    throw new IOException("Login response did not include a token");
                }
                return token;
            }
        } finally {
            res.disconnect();
        }
    }

    /**
     * List sources of every supported layer, optionally limited to those covering a point.
     */
    public static List<Source> listSources(ILatLon point) throws IOException {
        String url = baseUrl() + "/data";
        if (point != null) {
            url += String.format(Locale.ROOT, "?point=%.6f,%.6f", point.lon(), point.lat());
        }

        HttpClient.Response res = HttpClient.create(URI.create(url).toURL())
            .setAccept("application/json")
            .connect();
        try {
            String body = res.fetchContent();
            if (res.getResponseCode() != 200) {
                throw new IOException(errorMessage(res.getResponseCode(), body));
            }
            return parseSources(new StringReader(body));
        } finally {
            res.disconnect();
        }
    }

    static List<Source> parseSources(Reader json) {
        List<Source> sources = new ArrayList<>();
        try (JsonReader reader = Json.createReader(json)) {
            JsonArray items = reader.readArray();
            for (JsonObject item : items.getValuesAs(JsonObject.class)) {
                JsonObject output = item.getJsonObject("output");
                Layer layer = Layer.fromId(item.getString("layer", ""));
                if (layer != null && output != null && output.getBoolean("output", false)) {
                    sources.add(Source.fromJson(item, layer));
                }
            }
        }
        sources.sort(Comparator.comparing(Source::source).thenComparing(Source::layer).thenComparing(Source::name));
        return sources;
    }

    /**
     * Fetch a job's processed output into the JOSM cache, reusing an earlier download.
     * Job outputs are immutable so the cache never needs invalidating.
     */
    public static Path fetchOutput(Source source, String token, ProgressMonitor monitor) throws IOException {
        Path cached = cachePath(source);
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        Path dir = cached.getParent();
        Files.createDirectories(dir);

        String url = baseUrl() + "/job/" + source.job() + "/output/source.geojson.gz";
        HttpClient client = HttpClient.create(URI.create(url).toURL());
        if (token != null && !token.isBlank()) {
            client.setHeader("Authorization", "Bearer " + token.trim());
        }

        HttpClient.Response res = client.connect(monitor);
        Path partial = Files.createTempFile(dir, source.job() + "-", ".part");
        try {
            int code = res.getResponseCode();
            if (code == 401 || code == 403) {
                throw new AuthRequiredException(apiMessage(res.fetchContent(), "Authentication required"));
            }
            if (code != 200) {
                throw new IOException(errorMessage(code, res.fetchContent()));
            }
            try (InputStream in = res.getContent()) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } finally {
            Files.deleteIfExists(partial);
            res.disconnect();
        }
    }

    static Path cachePath(Source source) {
        return Config.getDirs().getCacheDirectory(true).toPath()
            .resolve("openaddresses")
            .resolve(source.job() + ".geojson.gz");
    }

    static boolean isCached(Source source) {
        return Files.isRegularFile(cachePath(source));
    }

    static String errorMessage(int code, String body) {
        return apiMessage(body, "OpenAddresses API error " + code);
    }

    static String apiMessage(String body, String fallback) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject().getString("message", fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
