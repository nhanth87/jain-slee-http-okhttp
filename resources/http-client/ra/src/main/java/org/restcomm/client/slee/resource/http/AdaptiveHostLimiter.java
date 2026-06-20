package org.restcomm.client.slee.resource.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.client.methods.HttpUriRequest;

/**
 * Weighted fair-share host limiter for dynamic outbound URLs.
 */
public class AdaptiveHostLimiter {

    private static final long CIRCUIT_OPEN_MS = 30_000L;
    private static final double SMOOTH_OLD = 0.7d;
    private static final double SMOOTH_NEW = 0.3d;

    private final AdaptiveHttpClientConfig config;
    private final ConcurrentHashMap<String, HostSlot> slots = new ConcurrentHashMap<String, HostSlot>();
    private final AtomicLong globalAcquireTimeouts = new AtomicLong();
    private final AtomicLong globalCompleted = new AtomicLong();
    private volatile HttpClientPoolHealthSnapshot lastSnapshot;
    private volatile ScheduledExecutorService rebalancer;
    private volatile boolean started;

    public AdaptiveHostLimiter(AdaptiveHttpClientConfig config) {
        this.config = config;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        rebalancer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HttpClient-AdaptiveRebalance");
            t.setDaemon(true);
            return t;
        });
        rebalancer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                rebalance();
            }
        }, config.getRebalanceIntervalMs(), config.getRebalanceIntervalMs(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        started = false;
        if (rebalancer != null) {
            rebalancer.shutdownNow();
            rebalancer = null;
        }
        slots.clear();
        HttpClientHealthRegistry.clear(this);
    }

    public boolean tryAcquire(String hostKey) throws InterruptedException {
        HostSlot slot = slotFor(hostKey);
        if (slot.getMetrics().isCircuitOpen()) {
            globalAcquireTimeouts.incrementAndGet();
            return false;
        }
        boolean acquired = slot.tryAcquire(config.getAcquireTimeoutMs());
        if (!acquired) {
            globalAcquireTimeouts.incrementAndGet();
        }
        return acquired;
    }

    public void release(String hostKey) {
        HostSlot slot = slots.get(hostKey);
        if (slot != null) {
            slot.release();
        }
    }

    public void recordSuccess(String hostKey, long rttMs) {
        HostSlot slot = slots.get(hostKey);
        if (slot != null) {
            slot.getMetrics().recordCompletion(rttMs);
        }
        globalCompleted.incrementAndGet();
        refreshSnapshot();
    }

    public void recordFailure(String hostKey) {
        HostSlot slot = slots.get(hostKey);
        if (slot != null) {
            HostMetrics metrics = slot.getMetrics();
            metrics.recordError();
            if (metrics.getErrors() >= 5 && metrics.getCompleted() >= 10
                    && metrics.getErrors() > metrics.getCompleted() / 2) {
                metrics.openCircuit(CIRCUIT_OPEN_MS);
            }
        }
        globalCompleted.incrementAndGet();
        refreshSnapshot();
    }

    public HttpClientPoolHealthSnapshot getSnapshot() {
        HttpClientPoolHealthSnapshot snapshot = lastSnapshot;
        if (snapshot == null) {
            refreshSnapshot();
            snapshot = lastSnapshot;
        }
        return snapshot;
    }

    public Collection<String> listHosts() {
        return Collections.unmodifiableSet(slots.keySet());
    }

    public HostMetrics getHostMetrics(String hostKey) {
        HostSlot slot = slots.get(hostKey);
        return slot == null ? null : slot.getMetrics();
    }

    public static String hostKeyFromRequest(HttpUriRequest request) {
        URI uri = request.getURI();
        String host = uri.getHost();
        if (host == null) {
            host = "unknown";
        }
        host = normalizeHost(host);
        int port = uri.getPort();
        if (port < 0) {
            String scheme = uri.getScheme();
            port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }
        return host + ":" + port;
    }

    static String normalizeHost(String host) {
        if ("127.0.0.1".equals(host)) {
            return "localhost";
        }
        try {
            if (InetAddress.getByName(host).isLoopbackAddress()) {
                return "localhost";
            }
        } catch (UnknownHostException ignored) {
            // keep original host
        }
        return host.toLowerCase();
    }

    private HostSlot slotFor(String hostKey) {
        HostSlot slot = slots.get(hostKey);
        if (slot != null) {
            return slot;
        }
        HostSlot created = new HostSlot(hostKey, config.getMinPerHost());
        HostSlot existing = slots.putIfAbsent(hostKey, created);
        return existing != null ? existing : created;
    }

    private void rebalance() {
        if (slots.isEmpty()) {
            refreshSnapshot();
            return;
        }

        double totalDemand = 0d;
        List<HostSlot> active = new ArrayList<HostSlot>(slots.values());
        for (HostSlot slot : active) {
            HostMetrics metrics = slot.getMetrics();
            double demand = Math.max(metrics.getRateEwma(), 0.001d) * (metrics.getRttEwmaMs() / 1000d);
            totalDemand += demand;
        }
        if (totalDemand <= 0d) {
            totalDemand = active.size();
        }

        for (HostSlot slot : active) {
            HostMetrics metrics = slot.getMetrics();
            double demand = Math.max(metrics.getRateEwma(), 0.001d) * (metrics.getRttEwmaMs() / 1000d);
            double share = demand / totalDemand;
            int target = (int) Math.round(config.getMaxTotal() * share);
            target = clamp(target, config.getMinPerHost(), config.getMaxPerHost());

            HostMetrics.HostStatus status = metrics.resolveStatus();
            if (status == HostMetrics.HostStatus.SATURATED) {
                target = (int) Math.max(config.getMinPerHost(), target * 0.9d);
            } else if (status == HostMetrics.HostStatus.HEALTHY && metrics.getUtilization() < 0.5d) {
                target = clamp((int) Math.round(target * 1.1d), config.getMinPerHost(), config.getMaxPerHost());
            }

            int smoothed = (int) Math.round((SMOOTH_OLD * metrics.getLimit()) + (SMOOTH_NEW * target));
            smoothed = clamp(smoothed, config.getMinPerHost(), config.getMaxPerHost());
            slot.resizeLimit(smoothed);
        }
        refreshSnapshot();
    }

    private void refreshSnapshot() {
        int inFlight = 0;
        int limitSum = 0;
        List<HttpClientPoolHealthSnapshot.HostEntry> hosts = new ArrayList<HttpClientPoolHealthSnapshot.HostEntry>();
        for (HostSlot slot : slots.values()) {
            HostMetrics metrics = slot.getMetrics();
            inFlight += metrics.getInFlight();
            limitSum += metrics.getLimit();
            hosts.add(new HttpClientPoolHealthSnapshot.HostEntry(
                    metrics.getHostKey(),
                    metrics.getRateEwma(),
                    metrics.getRttEwmaMs(),
                    metrics.getRttP99Ms(),
                    metrics.getInFlight(),
                    metrics.getLimit(),
                    metrics.getUtilization(),
                    metrics.resolveStatus().name(),
                    metrics.getAcquireWaitAvgMs(),
                    metrics.getAcquireTimeouts(),
                    metrics.getErrors()));
        }
        double util = config.getMaxTotal() <= 0 ? 0d : (double) inFlight / config.getMaxTotal();
        long completed = globalCompleted.get();
        long acquireTimeouts = globalAcquireTimeouts.get();
        double acquireTimeoutRate = completed == 0 ? 0d : (double) acquireTimeouts / completed;
        String overall = resolveOverallStatus(util, acquireTimeoutRate, hosts);
        lastSnapshot = new HttpClientPoolHealthSnapshot(
                overall,
                config.getMaxTotal(),
                inFlight,
                util,
                acquireTimeoutRate,
                hosts,
                System.currentTimeMillis());
    }

    private static String resolveOverallStatus(double util, double acquireTimeoutRate,
            List<HttpClientPoolHealthSnapshot.HostEntry> hosts) {
        if (acquireTimeoutRate > 0.05d) {
            return "DOWN";
        }
        if (util >= 0.85d || acquireTimeoutRate > 0.001d) {
            return "DEGRADED";
        }
        for (HttpClientPoolHealthSnapshot.HostEntry host : hosts) {
            if ("SATURATED".equals(host.status) || "SLOW".equals(host.status) || "UNREACHABLE".equals(host.status)) {
                return "DEGRADED";
            }
        }
        return "UP";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
