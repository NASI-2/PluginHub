package org2.example.pluginHub;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Scanner;

public class AutoUpdater {
    private static final String GITHUB_OWNER = "NASI-2";
    private static final String GITHUB_REPO = "PluginHub";
    private final JavaPlugin plugin;

    public AutoUpdater(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateOnShutdownIfEnabled() {
        // Handle plugin self-update
        if (plugin.getConfig().getBoolean("auto-plugin-update.enabled", false)) {
            updatePlugin();
        }
        
        // Handle registry update
        if (plugin.getConfig().getBoolean("registry.auto-registry-update.enabled", false)) {
            updateRegistry();
        }
    }

    private void updatePlugin() {
        try {
            Path pluginsDir = plugin.getDataFolder().getParentFile().toPath();
            Path pluginJar = pluginsDir.resolve("PluginHub.jar");
            
            // Delete old PluginHub.jar
            if (Files.exists(pluginJar)) {
                Files.delete(pluginJar);
                plugin.getLogger().info("Deleted old PluginHub.jar");
            }
            
            // Download latest PluginHub.jar from GitHub
            String downloadUrl = getLatestReleaseDownloadUrl(GITHUB_OWNER, GITHUB_REPO, "PluginHub.jar");
            download(downloadUrl, pluginJar);
            plugin.getLogger().info("PluginHub auto-update completed: downloaded latest PluginHub.jar");
        } catch (Exception e) {
            plugin.getLogger().warning("PluginHub auto-update failed: " + e.getMessage());
        }
    }

    private void updateRegistry() {
        try {
            // Check if external registry URL is configured
            String externalRegistryUrl = plugin.getConfig().getString("registry.url", "");
            if (externalRegistryUrl != null && !externalRegistryUrl.trim().isEmpty()) {
                plugin.getLogger().info("External registry URL is configured, skipping auto-registry-update");
                return;
            }
            
            Path pluginDataDir = plugin.getDataFolder().toPath();
            Path registryFile = pluginDataDir.resolve("default-registry.json");
            
            // Delete old default-registry.json
            if (Files.exists(registryFile)) {
                Files.delete(registryFile);
                plugin.getLogger().info("Deleted old default-registry.json");
            }
            
            // Download latest default-registry.json from GitHub
            String downloadUrl = getLatestReleaseDownloadUrl(GITHUB_OWNER, GITHUB_REPO, "default-registry.json");
            download(downloadUrl, registryFile);
            plugin.getLogger().info("Registry auto-update completed: downloaded latest default-registry.json");
        } catch (Exception e) {
            plugin.getLogger().warning("Registry auto-update failed: " + e.getMessage());
        }
    }

    private String getLatestReleaseDownloadUrl(String owner, String repo, String assetName) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";
        HttpURLConnection c = (HttpURLConnection) new URL(apiUrl).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/vnd.github.v3+json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        
        if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("GitHub API returned HTTP " + c.getResponseCode());
        }
        
        String response;
        try (Scanner scanner = new Scanner(c.getInputStream(), "UTF-8")) {
            response = scanner.useDelimiter("\\A").next();
        }
        
        // Parse JSON to find the asset with the specified name
        String assetPattern = "\"name\":\"" + assetName + "\"";
        int assetIndex = response.indexOf(assetPattern);
        if (assetIndex == -1) {
            throw new IllegalStateException("Asset '" + assetName + "' not found in latest release");
        }
        
        // Find the browser_download_url for this asset
        int urlStart = response.lastIndexOf("\"browser_download_url\":\"", assetIndex);
        if (urlStart == -1) {
            throw new IllegalStateException("browser_download_url not found for asset '" + assetName + "'");
        }
        urlStart += "\"browser_download_url\":\"".length();
        
        int urlEnd = response.indexOf("\"", urlStart);
        if (urlEnd == -1) {
            throw new IllegalStateException("Invalid browser_download_url format");
        }
        
        return response.substring(urlStart, urlEnd).replace("\\/", "/");
    }

    private void download(String urlString, Path output) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("HTTP " + c.getResponseCode());
        }
        try (InputStream in = c.getInputStream();
             OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
    }
}
