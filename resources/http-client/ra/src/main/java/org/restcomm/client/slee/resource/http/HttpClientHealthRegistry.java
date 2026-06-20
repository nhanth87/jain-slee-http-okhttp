package org.restcomm.client.slee.resource.http;

/**
 * Registry bridge for HTTP health servlet and JMX.
 */
public final class HttpClientHealthRegistry {

    private static volatile AdaptiveHostLimiter limiter;

    private HttpClientHealthRegistry() {
    }

    public static void register(AdaptiveHostLimiter activeLimiter) {
        limiter = activeLimiter;
    }

    public static void clear(AdaptiveHostLimiter activeLimiter) {
        if (limiter == activeLimiter) {
            limiter = null;
        }
    }

    public static AdaptiveHostLimiter getLimiter() {
        return limiter;
    }

    public static HttpClientPoolHealthSnapshot getSnapshot() {
        AdaptiveHostLimiter active = limiter;
        return active == null ? emptySnapshot() : active.getSnapshot();
    }

    private static HttpClientPoolHealthSnapshot emptySnapshot() {
        return new HttpClientPoolHealthSnapshot("DOWN", 0, 0, 0d, 0d,
                java.util.Collections.<HttpClientPoolHealthSnapshot.HostEntry>emptyList(), System.currentTimeMillis());
    }
}
