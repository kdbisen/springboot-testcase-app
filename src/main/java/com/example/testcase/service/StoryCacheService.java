package com.example.testcase.service;

import com.example.testcase.model.StoryDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Service
public class StoryCacheService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path cacheDir;

    @Value("${story.cache.enabled:false}")
    private boolean enabled;

    public StoryCacheService() {
        this.cacheDir = Path.of(".story_cache");
    }

    public boolean isEnabled() { return enabled; }

    public void cacheStory(String storyKey, StoryDetails details) {
        if (!enabled) return;
        try {
            Files.createDirectories(cacheDir);
            File f = cacheDir.resolve(sanitize(storyKey) + ".json").toFile();
            JSON.writeValue(f, Map.of("key", storyKey, "details", details));
        } catch (Exception ignored) {}
    }

    public StoryDetails getCachedStory(String storyKey) {
        if (!enabled) return null;
        try {
            File f = cacheDir.resolve(sanitize(storyKey) + ".json").toFile();
            if (!f.exists()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = JSON.readValue(f, Map.class);
            return JSON.convertValue(data.get("details"), StoryDetails.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitize(String key) {
        return key.toUpperCase().replaceAll("[^A-Z0-9_-]", "_");
    }
}
