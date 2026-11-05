package com.stockguard.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Releases all tasks through one gate so they really overlap, and measures that they did. */
public final class Concurrent {

    private Concurrent() {
    }

    public static <T> Run<T> run(int count, Task<T> task) {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    gate.await();
                    peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        return task.call(index);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }));
            }
            ready.await();
            long startedAt = System.nanoTime();
            gate.countDown();
            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return new Run<>(results, peakInFlight.get(), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (ExecutionException ex) {
            throw new AssertionError("A concurrent task failed: " + ex.getCause(), ex.getCause());
        }
    }

    @FunctionalInterface
    public interface Task<T> {
        T call(int index) throws Exception;
    }

    /** @param peakInFlight how many tasks were running at the busiest moment; proves the tasks overlapped */
    public record Run<T>(List<T> results, int peakInFlight, long durationMillis) {
    }
}
