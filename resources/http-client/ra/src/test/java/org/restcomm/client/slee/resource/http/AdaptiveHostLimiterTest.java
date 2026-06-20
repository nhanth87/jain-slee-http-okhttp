package org.restcomm.client.slee.resource.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AdaptiveHostLimiterTest {

    private AdaptiveHostLimiter limiter;

    @Before
    public void setUp() {
        AdaptiveHttpClientConfig config = new AdaptiveHttpClientConfig();
        config.setMaxTotal(100);
        config.setMinPerHost(5);
        config.setMaxPerHost(50);
        config.setAcquireTimeoutMs(100);
        config.setRebalanceIntervalMs(3600000);
        AdaptiveHttpClientConfig.set(config);
        limiter = new AdaptiveHostLimiter(config);
        limiter.start();
    }

    @After
    public void tearDown() {
        if (limiter != null) {
            limiter.stop();
        }
    }

    @Test
    public void weightedHostsReceiveDistinctLimitsAfterRebalance() throws Exception {
        for (int i = 0; i < 70; i++) {
            assertTrue(limiter.tryAcquire("fast:80"));
            limiter.recordSuccess("fast:80", 50);
            limiter.release("fast:80");
        }
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire("medium:80"));
            limiter.recordSuccess("medium:80", 200);
            limiter.release("medium:80");
        }
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("slow:80"));
            limiter.recordSuccess("slow:80", 800);
            limiter.release("slow:80");
        }

        java.lang.reflect.Method rebalance = AdaptiveHostLimiter.class.getDeclaredMethod("rebalance");
        rebalance.setAccessible(true);
        rebalance.invoke(limiter);
        rebalance.invoke(limiter);

        HostMetrics fast = limiter.getHostMetrics("fast:80");
        HostMetrics medium = limiter.getHostMetrics("medium:80");
        HostMetrics slow = limiter.getHostMetrics("slow:80");

        assertTrue("fast=" + fast.getLimit() + " medium=" + medium.getLimit() + " slow=" + slow.getLimit(),
                slow.getLimit() >= medium.getLimit());
        assertTrue(slow.getLimit() >= fast.getLimit());
    }

    @Test
    public void normalizeHostDedupsLoopback() {
        assertEquals("localhost:8080", AdaptiveHostLimiter.hostKeyFromRequest(
                new org.apache.http.client.methods.HttpGet("http://127.0.0.1:8080/ussd")));
    }
}
