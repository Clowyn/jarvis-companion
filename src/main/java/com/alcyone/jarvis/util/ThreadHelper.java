package com.alcyone.jarvis.util;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Utility for scheduling tasks safely on Minecraft's main server tick thread.
 */
public class ThreadHelper {

    /**
     * Schedules a value-producing task on the main server thread, returning a CompletableFuture.
     *
     * @param server   The MinecraftServer instance.
     * @param supplier The task returning a value.
     * @param <T>      The return type.
     * @return CompletableFuture completing with the supplier result.
     */
    public static <T> CompletableFuture<T> supplyOnMain(MinecraftServer server, Supplier<T> supplier) {
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("MinecraftServer is not running or ready"));
        }

        if (server.isSameThread()) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Schedules a task on the main server thread and waits synchronously for the result up to a timeout.
     * Unwraps ExecutionException to its root cause.
     *
     * @param server   The MinecraftServer instance.
     * @param supplier The task returning a value.
     * @param timeout  Timeout duration.
     * @param unit     Timeout time unit.
     * @param <T>      The return type.
     * @return The computed value.
     * @throws TimeoutException If execution times out.
     * @throws Exception        If the main thread execution fails with an exception.
     */
    public static <T> T supplyOnMain(MinecraftServer server, Supplier<T> supplier, long timeout, TimeUnit unit)
            throws TimeoutException, Exception {
        CompletableFuture<T> future = supplyOnMain(server, supplier);
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

    /**
     * Executes a runnable on the main server thread asynchronously.
     *
     * @param server The MinecraftServer instance.
     * @param action The runnable action.
     */
    public static void runOnMain(MinecraftServer server, Runnable action) {
        if (server == null) {
            throw new IllegalStateException("MinecraftServer is not running or ready");
        }

        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    /**
     * Executes a runnable on the main server thread and returns a CompletableFuture for completion tracking.
     *
     * @param server The MinecraftServer instance.
     * @param action The runnable action.
     * @return CompletableFuture completing when the action completes.
     */
    public static CompletableFuture<Void> runOnMainAsync(MinecraftServer server, Runnable action) {
        return supplyOnMain(server, () -> {
            action.run();
            return null;
        });
    }
}
