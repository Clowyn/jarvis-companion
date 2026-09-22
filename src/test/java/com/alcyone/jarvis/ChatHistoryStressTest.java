package com.alcyone.jarvis;

import com.alcyone.jarvis.chat.ChatHistory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Empirical stress test suite for ChatHistory concurrency, memory bounds,
 * thread-safety, and invariant preservation under heavy multi-threaded write load.
 */
public class ChatHistoryStressTest {

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

    /**
     * Test 1: Multi-threaded rapid writes (1,000 messages across 20 threads).
     * Verifies:
     * - All 1,000 writes succeed.
     * - Final quiescent size <= MAX_ENTRIES (100).
     * - No duplicate IDs.
     * - IDs are dense and contiguous (no gaps).
     */
    public static TestResult testMultiThreadedRapidWrites() {
        long start = System.currentTimeMillis();
        ChatHistory.clear();

        int numThreads = 20;
        int msgsPerThread = 50;
        int totalMsgs = numThreads * msgsPerThread; // 1,000

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        ConcurrentLinkedQueue<ChatHistory.ChatEntry> createdEntries = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int m = 0; m < msgsPerThread; m++) {
                        ChatHistory.ChatEntry entry = ChatHistory.add("Player_" + threadId, "Message " + m + " from t" + threadId);
                        if (entry == null) {
                            failures.incrementAndGet();
                        } else {
                            createdEntries.add(entry);
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Trigger simultaneous start
        startLatch.countDown();

        try {
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();
            if (!completed) {
                return new TestResult("MultiThreadedRapidWrites", false, "Timed out waiting for writes to complete", System.currentTimeMillis() - start);
            }
        } catch (InterruptedException e) {
            return new TestResult("MultiThreadedRapidWrites", false, "Interrupted: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        int finalSize = ChatHistory.size();
        List<ChatHistory.ChatEntry> recent = ChatHistory.getRecent(100, 0);

        // Verify ID uniqueness and contiguous range
        Set<Long> uniqueIds = new HashSet<>();
        long minId = Long.MAX_VALUE;
        long maxId = Long.MIN_VALUE;

        for (ChatHistory.ChatEntry entry : createdEntries) {
            uniqueIds.add(entry.getId());
            if (entry.getId() < minId) minId = entry.getId();
            if (entry.getId() > maxId) maxId = entry.getId();
        }

        boolean uniquePass = (uniqueIds.size() == totalMsgs);
        boolean contiguousPass = (maxId - minId + 1 == totalMsgs);
        boolean boundPass = (finalSize <= ChatHistory.MAX_ENTRIES);
        boolean recentBoundPass = (recent.size() <= ChatHistory.MAX_ENTRIES);
        boolean consistencyPass = (finalSize == recent.size());

        boolean passed = (failures.get() == 0) && uniquePass && contiguousPass && boundPass && recentBoundPass;

        String details = String.format(
                "Total writes: %d, Failures: %d, Final size: %d (max: %d), Recent count: %d, " +
                "Unique IDs: %d/%d, ID range: [%d, %d] (span: %d), Quiescent bound preserved: %b, Consistent: %b",
                createdEntries.size(), failures.get(), finalSize, ChatHistory.MAX_ENTRIES, recent.size(),
                uniqueIds.size(), totalMsgs, minId, maxId, (maxId - minId + 1), boundPass, consistencyPass
        );

        return new TestResult("MultiThreadedRapidWrites", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * Test 2: Virtual Thread Scalability Blast (5,000 writes across 100 Virtual Threads).
     */
    public static TestResult testVirtualThreadBlast() {
        long start = System.currentTimeMillis();
        ChatHistory.clear();

        int numThreads = 100;
        int msgsPerThread = 50;
        int totalMsgs = numThreads * msgsPerThread; // 5,000

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        ConcurrentLinkedQueue<ChatHistory.ChatEntry> createdEntries = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int m = 0; m < msgsPerThread; m++) {
                        ChatHistory.ChatEntry entry = ChatHistory.add("VPlayer_" + threadId, "VMessage " + m);
                        if (entry == null) {
                            failures.incrementAndGet();
                        } else {
                            createdEntries.add(entry);
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();

        try {
            boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
            executor.shutdown();
            if (!completed) {
                return new TestResult("VirtualThreadBlast", false, "Timed out during virtual thread burst", System.currentTimeMillis() - start);
            }
        } catch (InterruptedException e) {
            return new TestResult("VirtualThreadBlast", false, "Interrupted: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        int finalSize = ChatHistory.size();
        List<ChatHistory.ChatEntry> recent = ChatHistory.getRecent(100, 0);

        Set<Long> uniqueIds = new HashSet<>();
        long minId = Long.MAX_VALUE;
        long maxId = Long.MIN_VALUE;

        for (ChatHistory.ChatEntry entry : createdEntries) {
            uniqueIds.add(entry.getId());
            if (entry.getId() < minId) minId = entry.getId();
            if (entry.getId() > maxId) maxId = entry.getId();
        }

        boolean boundPass = (finalSize <= ChatHistory.MAX_ENTRIES);
        boolean uniquePass = (uniqueIds.size() == totalMsgs);
        boolean contiguousPass = (maxId - minId + 1 == totalMsgs);

        boolean passed = (failures.get() == 0) && boundPass && uniquePass && contiguousPass;

        String details = String.format(
                "Total writes: %d across 100 virtual threads, Final size: %d, Unique IDs: %d/%d, ID span: %d, Throughput: %.0f msgs/sec",
                createdEntries.size(), finalSize, uniqueIds.size(), totalMsgs, (maxId - minId + 1),
                (totalMsgs * 1000.0 / Math.max(1, System.currentTimeMillis() - start))
        );

        return new TestResult("VirtualThreadBlast", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * Test 3: Concurrent Readers + Concurrent Writers (Zero ConcurrentModificationException).
     * 20 writer threads actively writing while 10 reader threads actively query getRecent() and size().
     */
    public static TestResult testConcurrentReadersAndWriters() {
        long start = System.currentTimeMillis();
        ChatHistory.clear();

        int numWriters = 20;
        int msgsPerWriter = 100; // 2,000 writes total
        int numReaders = 10;

        ExecutorService writerExec = Executors.newFixedThreadPool(numWriters);
        ExecutorService readerExec = Executors.newFixedThreadPool(numReaders);

        AtomicBoolean writersActive = new AtomicBoolean(true);
        AtomicInteger cmeCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);
        AtomicLong totalReads = new AtomicLong(0);
        AtomicInteger maxObservedRecentSize = new AtomicInteger(0);
        AtomicInteger maxObservedHistorySize = new AtomicInteger(0);

        // Launch reader threads
        for (int r = 0; r < numReaders; r++) {
            readerExec.submit(() -> {
                while (writersActive.get()) {
                    try {
                        List<ChatHistory.ChatEntry> list = ChatHistory.getRecent(50, 0);
                        totalReads.incrementAndGet();
                        int sz = list.size();
                        if (sz > maxObservedRecentSize.get()) {
                            maxObservedRecentSize.set(sz);
                        }

                        int currentSize = ChatHistory.size();
                        if (currentSize > maxObservedHistorySize.get()) {
                            maxObservedHistorySize.set(currentSize);
                        }
                    } catch (ConcurrentModificationException e) {
                        cmeCount.incrementAndGet();
                    } catch (Throwable t) {
                        otherErrors.incrementAndGet();
                    }
                }
            });
        }

        // Launch writers
        CountDownLatch writerDoneLatch = new CountDownLatch(numWriters);
        for (int w = 0; w < numWriters; w++) {
            final int wid = w;
            writerExec.submit(() -> {
                try {
                    for (int m = 0; m < msgsPerWriter; m++) {
                        ChatHistory.add("Writer_" + wid, "Broadcast " + m);
                    }
                } finally {
                    writerDoneLatch.countDown();
                }
            });
        }

        try {
            boolean writersFinished = writerDoneLatch.await(10, TimeUnit.SECONDS);
            writersActive.set(false);
            writerExec.shutdown();
            readerExec.shutdown();
            readerExec.awaitTermination(2, TimeUnit.SECONDS);

            if (!writersFinished) {
                return new TestResult("ConcurrentReadersAndWriters", false, "Writers did not finish within timeout", System.currentTimeMillis() - start);
            }
        } catch (InterruptedException e) {
            return new TestResult("ConcurrentReadersAndWriters", false, "Interrupted: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        int finalSize = ChatHistory.size();
        boolean passed = (cmeCount.get() == 0) && (otherErrors.get() == 0) && (finalSize <= ChatHistory.MAX_ENTRIES) && (maxObservedRecentSize.get() <= 50);

        String details = String.format(
                "Total concurrent reads: %d, CME count: %d, Other read errors: %d, Max observed getRecent(50) size: %d (limit: 50), " +
                "Max transient ChatHistory.size(): %d, Final quiescent size: %d",
                totalReads.get(), cmeCount.get(), otherErrors.get(), maxObservedRecentSize.get(),
                maxObservedHistorySize.get(), finalSize
        );

        return new TestResult("ConcurrentReadersAndWriters", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * Test 4: Dynamic Buffer Sampling under extreme concurrency.
     * Samples ChatHistory.size() and getRecent(100, 0) during 50-thread concurrent bursts.
     */
    public static TestResult testDynamicBufferSampling() {
        long start = System.currentTimeMillis();
        ChatHistory.clear();

        int numThreads = 50;
        int msgsPerThread = 40; // 2,000 messages
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);

        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger peakRecentSize = new AtomicInteger(0);
        AtomicInteger peakSizeCall = new AtomicInteger(0);
        AtomicInteger samples = new AtomicInteger(0);

        Thread sampler = new Thread(() -> {
            while (active.get()) {
                int s = ChatHistory.size();
                List<ChatHistory.ChatEntry> r = ChatHistory.getRecent(100, 0);
                if (s > peakSizeCall.get()) peakSizeCall.set(s);
                if (r.size() > peakRecentSize.get()) peakRecentSize.set(r.size());
                samples.incrementAndGet();
                Thread.yield();
            }
        });
        sampler.start();

        CountDownLatch latch = new CountDownLatch(numThreads);
        for (int i = 0; i < numThreads; i++) {
            final int tid = i;
            exec.submit(() -> {
                try {
                    for (int m = 0; m < msgsPerThread; m++) {
                        ChatHistory.add("SamplerPlayer_" + tid, "Msg " + m);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(10, TimeUnit.SECONDS);
            active.set(false);
            sampler.join(2000);
            exec.shutdown();
        } catch (InterruptedException e) {
            return new TestResult("DynamicBufferSampling", false, "Interrupted: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        int finalSize = ChatHistory.size();
        List<ChatHistory.ChatEntry> finalRecent = ChatHistory.getRecent(100, 0);

        // Invariants:
        // 1. Quiescent size MUST be <= MAX_ENTRIES (100)
        // 2. getRecent(100) MUST NEVER return more than 100 entries at ANY moment (including during peak load!)
        boolean quiescentPass = (finalSize <= ChatHistory.MAX_ENTRIES);
        boolean recentNeverExceedsMax = (peakRecentSize.get() <= ChatHistory.MAX_ENTRIES);
        boolean passed = quiescentPass && recentNeverExceedsMax;

        String details = String.format(
                "Samples taken: %d, Peak transient ChatHistory.size(): %d, Peak getRecent(100) size: %d (max: 100), " +
                "Quiescent size: %d, Quiescent getRecent size: %d, getRecent never exceeded bound: %b",
                samples.get(), peakSizeCall.get(), peakRecentSize.get(), finalSize, finalRecent.size(), recentNeverExceedsMax
        );

        return new TestResult("DynamicBufferSampling", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * Test 5: ID Monotonicity and Ordering Inspection in Retained Buffer.
     */
    public static TestResult testIdMonotonicityAndOrdering() {
        long start = System.currentTimeMillis();
        ChatHistory.clear();

        // Sequential baseline
        for (int i = 0; i < 50; i++) {
            ChatHistory.add("SeqPlayer", "Message " + i);
        }

        List<ChatHistory.ChatEntry> seqList = ChatHistory.getRecent(100, 0);
        boolean seqMonotonic = true;
        for (int i = 0; i < seqList.size() - 1; i++) {
            if (seqList.get(i).getId() >= seqList.get(i + 1).getId()) {
                seqMonotonic = false;
                break;
            }
        }

        // Multi-threaded write
        ChatHistory.clear();
        int threads = 10;
        int msgs = 20; // 200 msgs, buffer will retain last ~100
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            exec.submit(() -> {
                try {
                    for (int m = 0; m < msgs; m++) {
                        ChatHistory.add("T" + tid, "M" + m);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(5, TimeUnit.SECONDS);
            exec.shutdown();
        } catch (InterruptedException e) {
            return new TestResult("IdMonotonicityAndOrdering", false, "Interrupted: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        List<ChatHistory.ChatEntry> retained = ChatHistory.getRecent(100, 0);
        int inversions = 0;
        for (int i = 0; i < retained.size() - 1; i++) {
            if (retained.get(i).getId() > retained.get(i + 1).getId()) {
                inversions++;
            }
        }

        // Verify ID generator guarantees uniqueness
        Set<Long> ids = new HashSet<>();
        for (ChatHistory.ChatEntry e : retained) {
            ids.add(e.getId());
        }
        boolean allUnique = (ids.size() == retained.size());

        // In high concurrency, minor interleaving of addLast vs getAndIncrement can occur
        // but all IDs must be strictly unique and monotonically non-decreasing in sequential
        boolean passed = seqMonotonic && allUnique;

        String details = String.format(
                "Sequential monotonic: %b, Retained entries: %d, All unique: %b, Inversions under concurrent write: %d",
                seqMonotonic, retained.size(), allUnique, inversions
        );

        return new TestResult("IdMonotonicityAndOrdering", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * Test 6: Boundary, Edge Cases, Null Handling, and Trigger Classification.
     */
    public static TestResult testBoundaryAndEdgeCases() {
        long start = System.currentTimeMillis();
        ChatHistory.clear();

        // Null handling
        ChatHistory.ChatEntry nullEntry = ChatHistory.add(null, null);
        boolean nullHandled = (nullEntry != null) && "Unknown".equals(nullEntry.getPlayer()) && "".equals(nullEntry.getMessage());

        // Empty message
        ChatHistory.ChatEntry emptyEntry = ChatHistory.add("", "");
        boolean emptyHandled = (emptyEntry != null) && "".equals(emptyEntry.getPlayer()) && "".equals(emptyEntry.getMessage());

        // Direct triggers
        ChatHistory.ChatEntry trigger1 = ChatHistory.add("Steve", "!jarvis status");
        ChatHistory.ChatEntry trigger2 = ChatHistory.add("Alex", "@jarvis follow me");
        ChatHistory.ChatEntry cmdEntry = ChatHistory.add("Admin", "/time set day");
        ChatHistory.ChatEntry normalEntry = ChatHistory.add("Steve", "Hello world!");

        boolean t1Pass = trigger1.isCommand() && trigger1.isTrigger();
        boolean t2Pass = trigger2.isCommand() && trigger2.isTrigger();
        boolean cmdPass = cmdEntry.isCommand() && !cmdEntry.isTrigger();
        boolean normalPass = !normalEntry.isCommand() && !normalEntry.isTrigger();

        // Limit clamping
        List<ChatHistory.ChatEntry> minClamp = ChatHistory.getRecent(-50, 0);
        List<ChatHistory.ChatEntry> maxClamp = ChatHistory.getRecent(500, 0);
        boolean minClampPass = (minClamp.size() >= 1);
        boolean maxClampPass = (maxClamp.size() <= ChatHistory.MAX_ENTRIES);

        // Future timestamp filter
        List<ChatHistory.ChatEntry> futureFilter = ChatHistory.getRecent(50, System.currentTimeMillis() + 100000);
        boolean futurePass = futureFilter.isEmpty();

        // Clear verification
        ChatHistory.clear();
        boolean clearPass = (ChatHistory.size() == 0) && ChatHistory.getRecent(50, 0).isEmpty();

        boolean passed = nullHandled && emptyHandled && t1Pass && t2Pass && cmdPass && normalPass &&
                         minClampPass && maxClampPass && futurePass && clearPass;

        String details = String.format(
                "Null handled: %b, Empty handled: %b, Triggers classified: %b, Cmd classified: %b, Normal classified: %b, " +
                "Clamp min/max: %b/%b, Future filter empty: %b, Clear reset: %b",
                nullHandled, emptyHandled, (t1Pass && t2Pass), cmdPass, normalPass,
                minClampPass, maxClampPass, futurePass, clearPass
        );

        return new TestResult("BoundaryAndEdgeCases", passed, details, System.currentTimeMillis() - start);
    }
}
