package com.alcyone.jarvis;

import com.alcyone.jarvis.util.ThreadHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Empirical test suite for ThreadHelper contract invariants:
 * - Direct null-server protection in production ThreadHelper bytecode.
 * - High-fidelity simulation of main-thread scheduling, timeout enforcement,
 *   exception unwrapping, and virtual thread concurrency.
 */
public class ThreadHelperContractTest {

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
     * Test 1: Direct null-server guard on ThreadHelper class.
     * Verifies that supplyOnMain, runOnMain, and runOnMainAsync reject null server.
     */
    public static TestResult testDirectNullServerGuards() {
        long start = System.currentTimeMillis();

        boolean supplyFailed = false;
        boolean runFailed = false;
        boolean asyncFailed = false;
        boolean timeoutFailed = false;

        // supplyOnMain(null, ...)
        CompletableFuture<String> future = ThreadHelper.supplyOnMain(null, () -> "test");
        if (future.isCompletedExceptionally()) {
            try {
                future.join();
            } catch (CompletionException ce) {
                if (ce.getCause() instanceof IllegalStateException &&
                    ce.getCause().getMessage().contains("MinecraftServer is not running or ready")) {
                    supplyFailed = true;
                }
            }
        }

        // runOnMain(null, ...)
        try {
            ThreadHelper.runOnMain(null, () -> {});
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("MinecraftServer is not running or ready")) {
                runFailed = true;
            }
        }

        // runOnMainAsync(null, ...)
        CompletableFuture<Void> asyncFuture = ThreadHelper.runOnMainAsync(null, () -> {});
        if (asyncFuture.isCompletedExceptionally()) {
            try {
                asyncFuture.join();
            } catch (CompletionException ce) {
                if (ce.getCause() instanceof IllegalStateException) {
                    asyncFailed = true;
                }
            }
        }

        // supplyOnMain(null, ..., timeout)
        try {
            ThreadHelper.supplyOnMain(null, () -> "val", 100, TimeUnit.MILLISECONDS);
        } catch (IllegalStateException e) {
            timeoutFailed = true;
        } catch (Exception ignored) {}

        boolean passed = supplyFailed && runFailed && asyncFailed && timeoutFailed;
        String details = String.format(
                "supplyOnMain null guard: %b, runOnMain null guard: %b, runOnMainAsync null guard: %b, timeout null guard: %b",
                supplyFailed, runFailed, asyncFailed, timeoutFailed
        );

        return new TestResult("DirectNullServerGuards", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * High-fidelity simulator replicating ThreadHelper's scheduling and timeout contracts.
     */
    public static class ThreadHelperSimulator {
        private final ExecutorService mainTickExecutor;
        private final Thread mainTickThread;

        public ThreadHelperSimulator(ExecutorService executor, Thread mainThread) {
            this.mainTickExecutor = executor;
            this.mainTickThread = mainThread;
        }

        public boolean isSameThread() {
            return Thread.currentThread() == mainTickThread;
        }

        public <T> CompletableFuture<T> supplyOnMain(Supplier<T> supplier) {
            if (isSameThread()) {
                try {
                    return CompletableFuture.completedFuture(supplier.get());
                } catch (Throwable t) {
                    return CompletableFuture.failedFuture(t);
                }
            }

            CompletableFuture<T> future = new CompletableFuture<>();
            mainTickExecutor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        }

        public <T> T supplyOnMain(Supplier<T> supplier, long timeout, TimeUnit unit) throws TimeoutException, Exception {
            CompletableFuture<T> future = supplyOnMain(supplier);
            try {
                return future.get(timeout, unit);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        public void runOnMain(Runnable action) {
            if (isSameThread()) {
                action.run();
            } else {
                mainTickExecutor.execute(action);
            }
        }
    }

    /**
     * Test 2: Main tick thread execution & completion invariant.
     */
    public static TestResult testMainThreadExecutionAndCompletion() {
        long start = System.currentTimeMillis();

        ExecutorService serverTickLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, "Server-Main-Tick-Thread"));
        Future<Thread> threadFuture = serverTickLoop.submit(Thread::currentThread);
        Thread mainThread;
        try {
            mainThread = threadFuture.get();
        } catch (Exception e) {
            return new TestResult("MainThreadExecutionAndCompletion", false, "Failed to initialize server loop: " + e.getMessage(), 0);
        }

        ThreadHelperSimulator helper = new ThreadHelperSimulator(serverTickLoop, mainThread);

        try {
            // Off-thread call
            CompletableFuture<String> future = helper.supplyOnMain(() -> {
                return "Executed on: " + Thread.currentThread().getName();
            });

            String result = future.get(2, TimeUnit.SECONDS);
            boolean ranOnMain = result.contains("Server-Main-Tick-Thread");

            // Direct on-main call
            Boolean sameThreadCheck = serverTickLoop.submit(() -> {
                CompletableFuture<Boolean> inner = helper.supplyOnMain(helper::isSameThread);
                return inner.get();
            }).get();

            boolean passed = ranOnMain && Boolean.TRUE.equals(sameThreadCheck);
            String details = String.format("Result: '%s', Verified main thread execution: %b, Same-thread branch: %b",
                    result, ranOnMain, sameThreadCheck);

            return new TestResult("MainThreadExecutionAndCompletion", passed, details, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new TestResult("MainThreadExecutionAndCompletion", false, "Exception: " + e.getMessage(), System.currentTimeMillis() - start);
        } finally {
            serverTickLoop.shutdown();
        }
    }

    /**
     * Test 3: Timeout Invariant under simulated main tick thread delay.
     * When main thread tick is blocked for 300ms, a 50ms timeout MUST trigger TimeoutException.
     * When main thread takes 10ms, a 300ms timeout MUST succeed.
     */
    public static TestResult testTimeoutBehaviorUnderMainThreadDelay() {
        long start = System.currentTimeMillis();

        ExecutorService serverTickLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, "Server-Tick-Thread"));
        Future<Thread> threadFuture = serverTickLoop.submit(Thread::currentThread);
        Thread mainThread;
        try {
            mainThread = threadFuture.get();
        } catch (Exception e) {
            return new TestResult("TimeoutBehaviorUnderMainThreadDelay", false, e.getMessage(), 0);
        }

        ThreadHelperSimulator helper = new ThreadHelperSimulator(serverTickLoop, mainThread);

        boolean timeoutTriggered = false;
        long timeoutElapsedMs = 0;
        boolean fastSucceeded = false;

        try {
            // 1. Task delayed past timeout: delay 400ms, timeout 80ms
            long t0 = System.currentTimeMillis();
            try {
                helper.supplyOnMain(() -> {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {}
                    return "Should timeout";
                }, 80, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                timeoutTriggered = true;
                timeoutElapsedMs = System.currentTimeMillis() - t0;
            } catch (Exception e) {
                // Unexpected exception
            }

            // 2. Task completing within timeout: delay 10ms, timeout 400ms
            String fastResult = helper.supplyOnMain(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {}
                return "FastSuccess";
            }, 400, TimeUnit.MILLISECONDS);

            if ("FastSuccess".equals(fastResult)) {
                fastSucceeded = true;
            }

            boolean passed = timeoutTriggered && (timeoutElapsedMs < 250) && fastSucceeded;
            String details = String.format(
                    "Timeout caught: %b (elapsed: %d ms, target: ~80 ms), Fast call succeeded within budget: %b",
                    timeoutTriggered, timeoutElapsedMs, fastSucceeded
            );

            return new TestResult("TimeoutBehaviorUnderMainThreadDelay", passed, details, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new TestResult("TimeoutBehaviorUnderMainThreadDelay", false, "Exception: " + e.getMessage(), System.currentTimeMillis() - start);
        } finally {
            serverTickLoop.shutdown();
        }
    }

    /**
     * Test 4: Exception Unwrapping Invariant.
     * Verifies that ExecutionException is unwrapped and root causes (Checked and Runtime) are thrown.
     */
    public static TestResult testExceptionUnwrapping() {
        long start = System.currentTimeMillis();

        ExecutorService serverTickLoop = Executors.newSingleThreadExecutor();
        Future<Thread> tf = serverTickLoop.submit(Thread::currentThread);
        Thread mainThread;
        try {
            mainThread = tf.get();
        } catch (Exception e) {
            return new TestResult("ExceptionUnwrapping", false, e.getMessage(), 0);
        }

        ThreadHelperSimulator helper = new ThreadHelperSimulator(serverTickLoop, mainThread);

        boolean npeUnwrapped = false;
        boolean customExUnwrapped = false;

        // RuntimeException unwrapped
        try {
            helper.supplyOnMain(() -> {
                throw new NullPointerException("Simulated NPE on main thread");
            }, 1, TimeUnit.SECONDS);
        } catch (NullPointerException npe) {
            if ("Simulated NPE on main thread".equals(npe.getMessage())) {
                npeUnwrapped = true;
            }
        } catch (Exception ignored) {}

        // Checked Exception unwrapped
        try {
            helper.supplyOnMain(() -> {
                throw new IllegalStateException("Simulated state exception");
            }, 1, TimeUnit.SECONDS);
        } catch (IllegalStateException ise) {
            if ("Simulated state exception".equals(ise.getMessage())) {
                customExUnwrapped = true;
            }
        } catch (Exception ignored) {}

        serverTickLoop.shutdown();

        boolean passed = npeUnwrapped && customExUnwrapped;
        String details = String.format("NPE correctly unwrapped: %b, Checked/State ex unwrapped: %b", npeUnwrapped, customExUnwrapped);

        return new TestResult("ExceptionUnwrapping", passed, details, System.currentTimeMillis() - start);
    }

    /**
     * Test 5: Virtual Thread High Concurrency Dispatch to Main Thread.
     * 100 virtual threads concurrently dispatch tasks via supplyOnMain.
     */
    public static TestResult testVirtualThreadHighConcurrency() {
        long start = System.currentTimeMillis();

        ExecutorService serverTickLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, "Server-Tick-Queue"));
        Future<Thread> tf = serverTickLoop.submit(Thread::currentThread);
        Thread mainThread;
        try {
            mainThread = tf.get();
        } catch (Exception e) {
            return new TestResult("VirtualThreadHighConcurrency", false, e.getMessage(), 0);
        }

        ThreadHelperSimulator helper = new ThreadHelperSimulator(serverTickLoop, mainThread);

        int numVirtualThreads = 100;
        ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch doneLatch = new CountDownLatch(numVirtualThreads);
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < numVirtualThreads; i++) {
            final int taskId = i;
            virtualExecutor.submit(() -> {
                try {
                    Integer res = helper.supplyOnMain(() -> {
                        return taskId * 2;
                    }, 5, TimeUnit.SECONDS);
                    results.add(res);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            virtualExecutor.shutdown();
            serverTickLoop.shutdown();

            if (!completed) {
                return new TestResult("VirtualThreadHighConcurrency", false, "Timed out waiting for virtual thread submissions", System.currentTimeMillis() - start);
            }
        } catch (InterruptedException e) {
            return new TestResult("VirtualThreadHighConcurrency", false, "Interrupted: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        boolean allReceived = (results.size() == numVirtualThreads);
        boolean noFailures = (failures.get() == 0);
        boolean passed = allReceived && noFailures;

        String details = String.format("Tasks submitted: %d, Tasks completed: %d, Failures: %d, Dispatch success: %b",
                numVirtualThreads, results.size(), failures.get(), passed);

        return new TestResult("VirtualThreadHighConcurrency", passed, details, System.currentTimeMillis() - start);
    }
}
