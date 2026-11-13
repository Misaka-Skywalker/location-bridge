package org.locationbridge.source;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded callback executor; receipt clock is captured before queueing. */
final class GnssDispatch implements Executor, AutoCloseable {
    private static final long MAX_QUEUE_AGE = 2_000_000_000L;
    private final LongSupplier clock;
    private final ThreadLocal<Long> receipt = new ThreadLocal<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.DiscardOldestPolicy());
    GnssDispatch(LongSupplier clock) { this.clock=clock; }
    @Override public void execute(Runnable command) {
        if (worker.isShutdown()) return;
        long received = clock.getAsLong();
        worker.execute(() -> {
            long now = clock.getAsLong();
            if (worker.isShutdown() || received <= 0 || now < received || now - received > MAX_QUEUE_AGE) return;
            receipt.set(received);
            try { command.run(); } finally { receipt.remove(); }
        });
    }
    long receiptNanos() { Long value=receipt.get(); return value == null ? 0 : value; }
    int queuedCount() { return worker.getQueue().size(); }
    @Override public void close() { worker.shutdownNow(); }
}
