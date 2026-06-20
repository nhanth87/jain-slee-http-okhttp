package org.restcomm.client.slee.resource.http;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Lazy per-host concurrency slot backed by a resizeable semaphore.
 */
public class HostSlot {

    private final HostMetrics metrics;
    private volatile Semaphore semaphore;

    public HostSlot(String hostKey, int initialLimit) {
        this.metrics = new HostMetrics(hostKey, initialLimit);
        this.semaphore = new Semaphore(initialLimit, true);
    }

    public HostMetrics getMetrics() {
        return metrics;
    }

    public boolean tryAcquire(long timeoutMs) throws InterruptedException {
        if (metrics.isCircuitOpen()) {
            return false;
        }
        long waitStart = System.nanoTime();
        boolean acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        long waitMs = (System.nanoTime() - waitStart) / 1_000_000L;
        if (waitMs > 0) {
            metrics.recordAcquireWait(waitMs);
        }
        if (acquired) {
            metrics.incrementInFlight();
        } else {
            metrics.recordAcquireTimeout();
        }
        return acquired;
    }

    public void release() {
        metrics.decrementInFlight();
        semaphore.release();
    }

    public void resizeLimit(int newLimit) {
        int oldLimit = metrics.getLimit();
        if (newLimit == oldLimit) {
            return;
        }
        metrics.setLimit(newLimit);
        int delta = newLimit - oldLimit;
        if (delta > 0) {
            semaphore.release(delta);
        } else if (delta < 0) {
            semaphore.tryAcquire(-delta);
        }
    }
}
