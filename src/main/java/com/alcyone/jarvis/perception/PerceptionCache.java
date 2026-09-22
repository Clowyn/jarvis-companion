package com.alcyone.jarvis.perception;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory perception cache with 500ms TTL.
 * Prevents redundant world scans during rapid polling by validating
 * time elapsed, radius match, and origin coordinate displacement (< 1.0 block).
 */
public class PerceptionCache {

    public static final long DEFAULT_TTL_MS = 500L;

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public static class CacheEntry {
        private final long timestamp;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final int radius;
        private final JsonObject result;

        public CacheEntry(long timestamp, double originX, double originY, double originZ, int radius, JsonObject result) {
            this.timestamp = timestamp;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.radius = radius;
            this.result = result;
        }

        public boolean isValid(double currentX, double currentY, double currentZ, int targetRadius, long ttlMs) {
            if (this.radius != targetRadius) {
                return false;
            }
            if (System.currentTimeMillis() - this.timestamp > ttlMs) {
                return false;
            }
            double dx = currentX - this.originX;
            double dy = currentY - this.originY;
            double dz = currentZ - this.originZ;
            return (dx * dx + dy * dy + dz * dz) <= 1.0;
        }

        public JsonObject getResult() {
            return result;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * Retrieves a valid cached perception result if not expired and within displacement threshold.
     */
    public static JsonObject get(String key, double currentX, double currentY, double currentZ, int radius) {
        if (key == null) {
            return null;
        }
        CacheEntry entry = CACHE.get(key);
        if (entry != null && entry.isValid(currentX, currentY, currentZ, radius, DEFAULT_TTL_MS)) {
            return entry.getResult().deepCopy();
        }
        return null;
    }

    /**
     * Stores a perception result in the cache.
     */
    public static void put(String key, double originX, double originY, double originZ, int radius, JsonObject result) {
        if (key == null || result == null) {
            return;
        }
        CACHE.put(key, new CacheEntry(System.currentTimeMillis(), originX, originY, originZ, radius, result.deepCopy()));
    }

    /**
     * Invalidates a specific cache entry.
     */
    public static void invalidate(String key) {
        if (key != null) {
            CACHE.remove(key);
        }
    }

    /**
     * Clears the entire perception cache.
     */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Returns the number of cached entries.
     */
    public static int size() {
        return CACHE.size();
    }
}
