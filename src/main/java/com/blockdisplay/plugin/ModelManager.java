package com.blockdisplay.plugin;

import com.google.gson.Gson;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ModelManager {
    private final Map<String, ModelData> cache = new HashMap<>();
    private final HttpClient httpClient;
    private final Gson gson;
    private final BlockDisplayPlugin plugin;
    private final File libraryDir;

    public ModelManager(BlockDisplayPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.gson = new Gson();

        File cacheDir = new File(plugin.getDataFolder(), "cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        this.libraryDir = new File(plugin.getDataFolder(), "library");
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }
    }

    public CompletableFuture<ModelData> fetchModel(String modelId) {
        if (cache.containsKey(modelId)) {
            return CompletableFuture.completedFuture(cache.get(modelId));
        }

        File cacheFile = new File(plugin.getDataFolder() + File.separator + "cache", modelId + ".json");
        if (cacheFile.exists()) {
            try (FileReader reader = new FileReader(cacheFile)) {
                ModelData data = gson.fromJson(reader, ModelData.class);
                if (data != null && data.hasPassengers()) {
                    cache.put(modelId, data);
                    return CompletableFuture.completedFuture(data);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to read cache for model " + modelId + ", re-fetching.");
                cacheFile.delete();
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://block-display.com/server-api/?id=" + modelId))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body -> {
                    try {
                        // Check for error response
                        if (body.contains("\"error\"")) {
                            plugin.getLogger().warning("API error for model " + modelId + ": " + body.trim());
                            return null;
                        }

                        ModelData data = gson.fromJson(body, ModelData.class);
                        if (data != null && data.hasPassengers()) {
                            cache.put(modelId, data);
                            try (FileWriter writer = new FileWriter(cacheFile)) {
                                writer.write(body);
                            } catch (Exception writeEx) {
                                plugin.getLogger().warning("Could not save cache file for " + modelId);
                            }
                            return data;
                        } else {
                            plugin.getLogger().warning("Failed to parse model " + modelId + " - no passengers found.");
                            return null;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error parsing JSON for model " + modelId + ": " + e.getMessage());
                        return null;
                    }
                });
    }

    public void invalidateCache(String modelId) {
        cache.remove(modelId);
        File cacheFile = new File(plugin.getDataFolder() + File.separator + "cache", modelId + ".json");
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }

    public void clearCache() {
        cache.clear();
        File cacheDir = new File(plugin.getDataFolder(), "cache");
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    // ---- Model Library ----

    public void saveToLibrary(String libraryName, ModelData data) {
        File file = new File(libraryDir, libraryName + ".json");
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save model to library '" + libraryName + "': " + e.getMessage());
        }
    }

    public ModelData loadFromLibrary(String libraryName) {
        File file = new File(libraryDir, libraryName + ".json");
        if (!file.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(file)) {
            return gson.fromJson(reader, ModelData.class);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load model from library '" + libraryName + "': " + e.getMessage());
            return null;
        }
    }

    public boolean deleteFromLibrary(String libraryName) {
        File file = new File(libraryDir, libraryName + ".json");
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }

    public List<String> getLibraryNames() {
        List<String> names = new ArrayList<>();
        File[] files = libraryDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                names.add(name.substring(0, name.length() - 5));
            }
        }
        return names;
    }

    public boolean libraryHas(String libraryName) {
        return new File(libraryDir, libraryName + ".json").exists();
    }
}
