package org.restcomm.client.slee.resource.http;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-host traffic metrics with EWMA rate/RTT and a small RTT sample ring for p99.
 */
public class HostMetrics {

    public enum HostStatus {
        HEALTHY, SATURATED, SLOW, UNREACHABLE, CIRCUIT_OPEN
    }

    private static final double RATE_ALPHA = 0.2d;
    private static final double RTT_ALPHA = 0.2d;
    private static final int RTT_RING_SIZE = 128;

    private final String hostKey;
    private volatile int limit;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong acquireTimeouts = new AtomicLong();
    private final AtomicLong acquireWaitTotalMs = new AtomicLong();
    private final AtomicLong acquireWaitSamples = new AtomicLong();

    private volatile double rateEwma;
    private volatile double rttEwmaMs = 200d;
    private volatile long lastSampleNanos = System.nanoTime();
    private volatile long circuitOpenUntil;

    private final long[] rttRing = new long[RTT_RING_SIZE];
    private int rttRingIndex;
    private int rttRingCount;

    public HostMetrics(String hostKey, int initialLimit) {
        this.hostKey = hostKey;
        this.limit = initialLimit;
    }

    public synchronized void recordCompletion(long rttMs) {
        completed.incrementAndGet();
        long now = System.nanoTime();
        double elapsedSec = Math.max((now - lastSampleNanos) / 1_000_000_000d, 0.001d);
        double instantRate = 1d / elapsedSec;
        rateEwma = rateEwma == 0d ? instantRate : (RATE_ALPHA * instantRate) + ((1d - RATE_ALPHA) * rateEwma);
        rttEwmaMs = (RTT_ALPHA * rttMs) + ((1d - RTT_ALPHA) * rttEwmaMs);
        lastSampleNanos = now;
        recordRttSample(rttMs);
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public void recordAcquireTimeout() {
        acquireTimeouts.incrementAndGet();
    }

    public void recordAcquireWait(long waitMs) {
        acquireWaitTotalMs.addAndGet(waitMs);
        acquireWaitSamples.incrementAndGet();
    }

    public void openCircuit(long durationMs) {
        circuitOpenUntil = System.currentTimeMillis() + durationMs;
    }

    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntil;
    }

    public int incrementInFlight() {
        return inFlight.incrementAndGet();
    }

    public int decrementInFlight() {
        return inFlight.decrementAndGet();
    }

    public int getInFlight() {
        return inFlight.get();
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public String getHostKey() {
        return hostKey;
    }

    public double getRateEwma() {
        return rateEwma;
    }

    public double getRttEwmaMs() {
        return rttEwmaMs;
    }

    public long getCompleted() {
        return completed.get();
    }

    public long getErrors() {
        return errors.get();
    }

    public long getAcquireTimeouts() {
        return acquireTimeouts.get();
    }

    public double getAcquireWaitAvgMs() {
        long samples = acquireWaitSamples.get();
        return samples == 0 ? 0d : (double) acquireWaitTotalMs.get() / samples;
    }

    public double getUtilization() {
        int currentLimit = limit;
        return currentLimit <= 0 ? 0d : (double) inFlight.get() / currentLimit;
    }

    public long getRttP99Ms() {
        synchronized (this) {
            if (rttRingCount == 0) {
                return (long) rttEwmaMs;
            }
            long[] copy = Arrays.copyOf(rttRing, rttRingCount);
            Arrays.sort(copy);
            int idx = (int) Math.ceil(rttRingCount * 0.99d) - 1;
            idx = Math.max(0, Math.min(idx, copy.length - 1));
            return copy[idx];
        }
    }

    public HostStatus resolveStatus() {
        if (isCircuitOpen()) {
            return HostStatus.CIRCUIT_OPEN;
        }
        long done = completed.get();
        long err = errors.get();
        if (done >= 10 && err > done / 2) {
            return HostStatus.UNREACHABLE;
        }
        if (getUtilization() >= 0.9d) {
            return HostStatus.SATURATED;
        }
        if (rttEwmaMs > 2000d) {
            return HostStatus.SLOW;
        }
        return HostStatus.HEALTHY;
    }

    private synchronized void recordRttSample(long rttMs) {
        rttRing[rttRingIndex] = rttMs;
        rttRingIndex = (rttRingIndex + 1) % RTT_RING_SIZE;
        if (rttRingCount < RTT_RING_SIZE) {
            rttRingCount++;
        }
    }
}
