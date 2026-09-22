package com.alcyone.jarvis;

import com.alcyone.jarvis.perception.PerceptionCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical stress test suite for PerceptionCache:
 * 1. Consecutive calls within 500ms TTL.
 * 2. TTL expiry after 500ms triggering cache refresh.
 * 3. Displacement invalidation (>1.0 block Euclidean threshold) across 3D axes and diagonals.
 * 4. Multi-threaded concurrency stress test (zero race conditions, CME, NPE, or deadlocks).
 * 5. Virtual thread high-throughput blast test.
 * 6. Defensive deep-copy and payload isolation verification.
 * 7. Boundary conditions, extreme coordinates, and null safety.
 */
public class PerceptionCacheStressTest {

    public static class TestResult {
        public final String testName;
        public final boolean passed;
        public final String details;
        public final long durationMs;

        public TestResult(String testName, boolean passed, String details, long durationMs) {
            this.testName = testName;
            this.passed = passed;
            this.details = details;
            this.durationMs = durationMs;
        }
    }

    private static JsonObject createSamplePayload(String label, int blockCount) {
        JsonObject root = new JsonObject();
        JsonObject origin = new JsonObject();
        origin.addProperty("x", 100.5);
        origin.addProperty("y", 64.0);
        origin.addProperty("z", -200.5);
        root.add("origin", origin);
        root.addProperty("radius", 16);
        root.addProperty("label", label);

        JsonArray blocks = new JsonArray();
        for (int i = 0; i < blockCount; i++) {
            JsonObject b = new JsonObject();
            b.addProperty("block", "minecraft:diamond_ore");
            b.addProperty("type", "ore");
            b.addProperty("distance", 1.0 + i * 0.5);
            blocks.add(b);
        }
        root.add("blocks", blocks);
        root.add("entities", new JsonArray());
        return root;
    }

    /**
     * Test 1: Consecutive calls within 500ms return cached payload without scanning.
     */
    public static TestResult testConsecutiveCallsWithin500ms() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        String key = "player_consecutive:16";
        double x = 150.0, y = 70.0, z = -300.0;
        int radius = 16;

        JsonObject original = createSamplePayload("cached_v1", 5);
        PerceptionCache.put(key, x, y, z, radius, original);

        int iterations = 1000;
        int hits = 0;
        boolean contentIntact = true;

        for (int i = 0; i < iterations; i++) {
            JsonObject cached = PerceptionCache.get(key, x, y, z, radius);
            if (cached != null) {
                hits++;
                if (!cached.has("label") || !"cached_v1".equals(cached.get("label").getAsString())) {
                    contentIntact = false;
                }
                if (!cached.has("blocks") || cached.getAsJsonArray("blocks").size() != 5) {
                    contentIntact = false;
                }
            }
        }

        boolean passed = (hits == iterations) && contentIntact;
        long duration = System.currentTimeMillis() - start;
        String details = String.format("Iterations: %d, Hits: %d, ContentIntact: %b, Elapsed: %d ms",
                iterations, hits, contentIntact, duration);
        return new TestResult("ConsecutiveCallsWithin500ms", passed, details, duration);
    }

    /**
     * Test 2: Calls after 500ms TTL trigger a cache miss / expiry.
     */
    public static TestResult testTtlExpiryAfter500ms() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        String key = "player_ttl:16";
        double x = 100.0, y = 64.0, z = 200.0;
        int radius = 16;

        JsonObject v1 = createSamplePayload("version_1", 3);
        PerceptionCache.put(key, x, y, z, radius, v1);

        // Immediate query: must hit
        JsonObject immediate = PerceptionCache.get(key, x, y, z, radius);
        boolean immediateHit = (immediate != null && "version_1".equals(immediate.get("label").getAsString()));

        // Sleep for 550ms to exceed 500ms TTL
        try {
            Thread.sleep(550);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Query after 550ms: must return null (expired)
        JsonObject expiredResult = PerceptionCache.get(key, x, y, z, radius);
        boolean expiredMiss = (expiredResult == null);

        // Put fresh data (cache refresh simulation)
        JsonObject v2 = createSamplePayload("version_2_refreshed", 8);
        PerceptionCache.put(key, x, y, z, radius, v2);

        JsonObject refreshed = PerceptionCache.get(key, x, y, z, radius);
        boolean refreshedHit = (refreshed != null && "version_2_refreshed".equals(refreshed.get("label").getAsString())
                && refreshed.getAsJsonArray("blocks").size() == 8);

        boolean passed = immediateHit && expiredMiss && refreshedHit;
        long duration = System.currentTimeMillis() - start;
        String details = String.format("ImmediateHit: %b, ExpiredAfter550msMiss: %b, RefreshedHit: %b, TotalDuration: %d ms",
                immediateHit, expiredMiss, refreshedHit, duration);
        return new TestResult("TtlExpiryAfter500ms", passed, details, duration);
    }

    /**
     * Test 3: Calls with displacement >1.0 block trigger cache invalidation.
     * Evaluates Euclidean distances along X, Y, Z axes, compound diagonal vectors,
     * boundary values (dx^2 + dy^2 + dz^2 <= 1.0 vs > 1.0), and radius changes.
     */
    public static TestResult testDisplacementInvalidation() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        String key = "player_disp:16";
        double ox = 100.0, oy = 64.0, oz = 200.0;
        int radius = 16;

        JsonObject payload = createSamplePayload("disp_test", 2);
        PerceptionCache.put(key, ox, oy, oz, radius, payload);

        List<String> failedCases = new ArrayList<>();

        // 1. Within 1.0 block on X axis
        if (PerceptionCache.get(key, ox + 0.5, oy, oz, radius) == null) failedCases.add("X+0.5 should HIT");
        if (PerceptionCache.get(key, ox + 1.0, oy, oz, radius) == null) failedCases.add("X+1.0 (boundary) should HIT");
        if (PerceptionCache.get(key, ox - 0.9, oy, oz, radius) == null) failedCases.add("X-0.9 should HIT");
        if (PerceptionCache.get(key, ox - 1.0, oy, oz, radius) == null) failedCases.add("X-1.0 (boundary) should HIT");

        // 2. Beyond 1.0 block on X axis
        if (PerceptionCache.get(key, ox + 1.001, oy, oz, radius) != null) failedCases.add("X+1.001 should MISS");
        if (PerceptionCache.get(key, ox + 2.0, oy, oz, radius) != null) failedCases.add("X+2.0 should MISS");
        if (PerceptionCache.get(key, ox - 1.05, oy, oz, radius) != null) failedCases.add("X-1.05 should MISS");

        // 3. Within and beyond on Y axis
        if (PerceptionCache.get(key, ox, oy + 0.8, oz, radius) == null) failedCases.add("Y+0.8 should HIT");
        if (PerceptionCache.get(key, ox, oy + 1.0, oz, radius) == null) failedCases.add("Y+1.0 should HIT");
        if (PerceptionCache.get(key, ox, oy + 1.01, oz, radius) != null) failedCases.add("Y+1.01 should MISS");
        if (PerceptionCache.get(key, ox, oy - 1.5, oz, radius) != null) failedCases.add("Y-1.5 should MISS");

        // 4. Within and beyond on Z axis
        if (PerceptionCache.get(key, ox, oy, oz + 0.99, radius) == null) failedCases.add("Z+0.99 should HIT");
        if (PerceptionCache.get(key, ox, oy, oz + 1.0, radius) == null) failedCases.add("Z+1.0 should HIT");
        if (PerceptionCache.get(key, ox, oy, oz + 1.001, radius) != null) failedCases.add("Z+1.001 should MISS");
        if (PerceptionCache.get(key, ox, oy, oz - 1.1, radius) != null) failedCases.add("Z-1.1 should MISS");

        // 5. 2D Diagonal: (0.7, 0.7, 0.0) -> 0.49 + 0.49 = 0.98 <= 1.0 -> HIT
        if (PerceptionCache.get(key, ox + 0.7, oy + 0.7, oz, radius) == null) failedCases.add("2D diag (0.7, 0.7) distSq=0.98 should HIT");
        // (0.72, 0.72, 0.0) -> 0.5184 * 2 = 1.0368 > 1.0 -> MISS
        if (PerceptionCache.get(key, ox + 0.72, oy + 0.72, oz, radius) != null) failedCases.add("2D diag (0.72, 0.72) distSq=1.0368 should MISS");

        // 6. 3D Compound Diagonal:
        // (0.57, 0.57, 0.57) -> 3 * 0.3249 = 0.9747 <= 1.0 -> HIT
        if (PerceptionCache.get(key, ox + 0.57, oy + 0.57, oz + 0.57, radius) == null) failedCases.add("3D diag (0.57, 0.57, 0.57) distSq=0.9747 should HIT");
        // (0.6, 0.6, 0.6) -> 3 * 0.36 = 1.08 > 1.0 -> MISS
        if (PerceptionCache.get(key, ox + 0.6, oy + 0.6, oz + 0.6, radius) != null) failedCases.add("3D diag (0.6, 0.6, 0.6) distSq=1.08 should MISS");

        // 7. Negative coordinate origin
        String negKey = "neg_coords:16";
        double nox = -500.25, noy = -64.5, noz = -1200.75;
        PerceptionCache.put(negKey, nox, noy, noz, radius, payload);
        if (PerceptionCache.get(negKey, nox + 0.5, noy, noz, radius) == null) failedCases.add("Neg coord +0.5 should HIT");
        if (PerceptionCache.get(negKey, nox - 0.5, noy, noz, radius) == null) failedCases.add("Neg coord -0.5 should HIT");
        if (PerceptionCache.get(negKey, nox + 1.5, noy, noz, radius) != null) failedCases.add("Neg coord +1.5 should MISS");

        // 8. Radius change invalidation
        if (PerceptionCache.get(key, ox, oy, oz, 8) != null) failedCases.add("Different radius (8 vs 16) should MISS");
        if (PerceptionCache.get(key, ox, oy, oz, 32) != null) failedCases.add("Different radius (32 vs 16) should MISS");

        boolean passed = failedCases.isEmpty();
        long duration = System.currentTimeMillis() - start;
        String details = passed ? "All 18 displacement/radius edge cases passed" : "Failures: " + String.join(", ", failedCases);
        return new TestResult("DisplacementInvalidation", passed, details, duration);
    }

    /**
     * Test 4: Concurrent multi-threaded reads/writes produce zero race conditions, NPEs, or deadlocks.
     */
    public static TestResult testMultiThreadedConcurrencyStress() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        int numThreads = 32;
        int opsPerThread = 500;
        int totalOps = numThreads * opsPerThread; // 16,000 ops

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicInteger npeCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        AtomicInteger hitCount = new AtomicInteger(0);
        AtomicInteger missCount = new AtomicInteger(0);
        AtomicInteger writeCount = new AtomicInteger(0);
        AtomicInteger invalidationCount = new AtomicInteger(0);

        int numKeys = 8;
        String[] keys = new String[numKeys];
        for (int k = 0; k < numKeys; k++) {
            keys[k] = "player_thread_" + k + ":16";
        }

        // Pre-populate some keys
        for (int k = 0; k < numKeys; k++) {
            PerceptionCache.put(keys[k], 100.0 + k * 10, 64.0, 200.0, 16, createSamplePayload("init_" + k, 3));
        }

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rand = ThreadLocalRandom.current();

                    for (int op = 0; op < opsPerThread; op++) {
                        String key = keys[rand.nextInt(numKeys)];
                        double baseCoord = 100.0 + (threadId % numKeys) * 10;
                        int action = rand.nextInt(10);

                        if (action < 5) {
                            // 50% reads with slight jitter
                            double jitter = (rand.nextDouble() - 0.5) * 1.5; // [-0.75, +0.75]
                            JsonObject result = PerceptionCache.get(key, baseCoord + jitter, 64.0, 200.0, 16);
                            if (result != null) {
                                hitCount.incrementAndGet();
                                if (!result.has("origin") || !result.has("blocks")) {
                                    exceptionCount.incrementAndGet();
                                }
                            } else {
                                missCount.incrementAndGet();
                            }
                        } else if (action < 8) {
                            // 30% writes
                            JsonObject newPayload = createSamplePayload("thread_" + threadId + "_op_" + op, rand.nextInt(10));
                            PerceptionCache.put(key, baseCoord, 64.0, 200.0, 16, newPayload);
                            writeCount.incrementAndGet();
                        } else if (action == 8) {
                            // 10% invalidations
                            PerceptionCache.invalidate(key);
                            invalidationCount.incrementAndGet();
                        } else {
                            // 10% size / clear reads
                            int size = PerceptionCache.size();
                            if (size < 0) {
                                exceptionCount.incrementAndGet();
                            }
                        }
                    }
                } catch (NullPointerException npe) {
                    npeCount.incrementAndGet();
                } catch (Throwable t1) {
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finishedInTime = false;
        try {
            finishedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();

        boolean passed = finishedInTime && (npeCount.get() == 0) && (exceptionCount.get() == 0);
        long duration = System.currentTimeMillis() - start;
        String details = String.format("TotalOps: %d, Hits: %d, Misses: %d, Writes: %d, Invalidations: %d, NPEs: %d, Exceptions: %d, FinishedInTime: %b",
                totalOps, hitCount.get(), missCount.get(), writeCount.get(), invalidationCount.get(), npeCount.get(), exceptionCount.get(), finishedInTime);
        return new TestResult("MultiThreadedConcurrencyStress", passed, details, duration);
    }

    /**
     * Test 5: Virtual thread high-throughput blast test (100 virtual threads, 10,000 ops).
     */
    public static TestResult testVirtualThreadBlast() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        int numVThreads = 100;
        int opsPerVThread = 100;
        int totalOps = numVThreads * opsPerVThread;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(numVThreads);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger totalHits = new AtomicInteger(0);

        for (int v = 0; v < numVThreads; v++) {
            final int vid = v;
            executor.submit(() -> {
                try {
                    String key = "vthread_" + (vid % 10) + ":16";
                    for (int i = 0; i < opsPerVThread; i++) {
                        if (i % 2 == 0) {
                            PerceptionCache.put(key, 100.0, 64.0, 100.0, 16, createSamplePayload("v_" + vid + "_" + i, 4));
                        } else {
                            JsonObject obj = PerceptionCache.get(key, 100.0, 64.0, 100.0, 16);
                            if (obj != null) {
                                totalHits.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = false;
        try {
            completed = latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();

        long duration = Math.max(1, System.currentTimeMillis() - start);
        long throughput = (totalOps * 1000L) / duration;
        boolean passed = completed && (errors.get() == 0);
        String details = String.format("VirtualThreads: %d, TotalOps: %d, Hits: %d, Errors: %d, Throughput: %d ops/sec",
                numVThreads, totalOps, totalHits.get(), errors.get(), throughput);
        return new TestResult("VirtualThreadBlast", passed, details, duration);
    }

    /**
     * Test 6: Defensive deep copy and payload isolation.
     * Modifying a retrieved JsonObject or modifying the input JsonObject post-put
     * must NEVER mutate the cache entry.
     */
    public static TestResult testDefensiveDeepCopyIsolation() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        String key = "isolation_test:16";
        JsonObject original = createSamplePayload("original_v1", 2);
        PerceptionCache.put(key, 100.0, 64.0, 200.0, 16, original);

        // 1. Mutate input original post-put
        original.addProperty("tampered_input", true);
        original.remove("label");

        JsonObject firstFetch = PerceptionCache.get(key, 100.0, 64.0, 200.0, 16);
        boolean inputIsolated = firstFetch != null
                && "original_v1".equals(firstFetch.get("label").getAsString())
                && !firstFetch.has("tampered_input");

        // 2. Mutate retrieved object
        firstFetch.addProperty("tampered_output", true);
        firstFetch.getAsJsonArray("blocks").remove(0);

        JsonObject secondFetch = PerceptionCache.get(key, 100.0, 64.0, 200.0, 16);
        boolean outputIsolated = secondFetch != null
                && !secondFetch.has("tampered_output")
                && secondFetch.getAsJsonArray("blocks").size() == 2;

        boolean passed = inputIsolated && outputIsolated;
        long duration = System.currentTimeMillis() - start;
        String details = String.format("InputIsolated: %b, OutputIsolated: %b", inputIsolated, outputIsolated);
        return new TestResult("DefensiveDeepCopyIsolation", passed, details, duration);
    }

    /**
     * Test 7: Boundary conditions, extreme coordinates, and null safety.
     */
    public static TestResult testBoundaryAndNullSafety() {
        long start = System.currentTimeMillis();
        PerceptionCache.clear();

        boolean nullHandled = true;
        try {
            // Null keys and values
            if (PerceptionCache.get(null, 100.0, 64.0, 200.0, 16) != null) nullHandled = false;
            PerceptionCache.put(null, 100.0, 64.0, 200.0, 16, new JsonObject());
            PerceptionCache.put("key", 100.0, 64.0, 200.0, 16, null);
            PerceptionCache.invalidate(null);
            if (PerceptionCache.size() != 0) nullHandled = false;
        } catch (Throwable t) {
            nullHandled = false;
        }

        boolean extremeCoordsHandled = true;
        try {
            // NaN coordinates
            PerceptionCache.put("nan_key", 100.0, 64.0, 200.0, 16, new JsonObject());
            if (PerceptionCache.get("nan_key", Double.NaN, 64.0, 200.0, 16) != null) extremeCoordsHandled = false;
            if (PerceptionCache.get("nan_key", 100.0, Double.NaN, 200.0, 16) != null) extremeCoordsHandled = false;
            if (PerceptionCache.get("nan_key", 100.0, 64.0, Double.NaN, 16) != null) extremeCoordsHandled = false;

            // Infinity coordinates
            if (PerceptionCache.get("nan_key", Double.POSITIVE_INFINITY, 64.0, 200.0, 16) != null) extremeCoordsHandled = false;
            if (PerceptionCache.get("nan_key", 100.0, Double.NEGATIVE_INFINITY, 200.0, 16) != null) extremeCoordsHandled = false;

            // Extreme double values
            PerceptionCache.put("extreme_key", Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, 16, new JsonObject());
            if (PerceptionCache.get("extreme_key", 0.0, 0.0, 0.0, 16) != null) extremeCoordsHandled = false;
        } catch (Throwable t) {
            extremeCoordsHandled = false;
        }

        // Cache clear functionality
        PerceptionCache.put("k1", 10.0, 10.0, 10.0, 16, new JsonObject());
        PerceptionCache.put("k2", 20.0, 20.0, 20.0, 16, new JsonObject());
        boolean sizeBefore = (PerceptionCache.size() >= 2);
        PerceptionCache.clear();
        boolean sizeAfter = (PerceptionCache.size() == 0);

        boolean passed = nullHandled && extremeCoordsHandled && sizeBefore && sizeAfter;
        long duration = System.currentTimeMillis() - start;
        String details = String.format("NullHandled: %b, ExtremeCoordsHandled: %b, SizeBeforeClear: %b, SizeAfterClear: %b",
                nullHandled, extremeCoordsHandled, sizeBefore, sizeAfter);
        return new TestResult("BoundaryAndNullSafety", passed, details, duration);
    }
}
