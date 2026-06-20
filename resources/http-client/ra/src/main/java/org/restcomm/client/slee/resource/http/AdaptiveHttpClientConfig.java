package org.restcomm.client.slee.resource.http;

/**
 * Global HTTP client pool configuration set by {@link HttpClientResourceAdaptor#raConfigure}.
 */
public final class AdaptiveHttpClientConfig {

    public static final String FACTORY_CLASS = AdaptiveOkHttpClientFactory.class.getName();

    public static final int DEFAULT_MAX_TOTAL = 3000;
    public static final int DEFAULT_MIN_PER_HOST = 32;
    public static final int DEFAULT_MAX_PER_HOST = 512;
    public static final int DEFAULT_ACQUIRE_TIMEOUT_MS = 500;
    public static final int DEFAULT_REBALANCE_INTERVAL_MS = 5000;
    public static final boolean DEFAULT_HEALTH_HTTP_ENABLED = true;

    private static volatile AdaptiveHttpClientConfig instance = new AdaptiveHttpClientConfig();

    private int maxTotal = DEFAULT_MAX_TOTAL;
    private int minPerHost = DEFAULT_MIN_PER_HOST;
    private int maxPerHost = DEFAULT_MAX_PER_HOST;
    private long acquireTimeoutMs = DEFAULT_ACQUIRE_TIMEOUT_MS;
    private long rebalanceIntervalMs = DEFAULT_REBALANCE_INTERVAL_MS;
    private boolean healthHttpEnabled = DEFAULT_HEALTH_HTTP_ENABLED;

    public static AdaptiveHttpClientConfig get() {
        return instance;
    }

    public static void set(AdaptiveHttpClientConfig config) {
        instance = config;
    }

    public static boolean isAdaptiveFactory(String factoryClassName) {
        return FACTORY_CLASS.equals(factoryClassName)
                || AdaptiveOkHttpClientFactory.class.getName().equals(factoryClassName);
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    public int getMinPerHost() {
        return minPerHost;
    }

    public void setMinPerHost(int minPerHost) {
        this.minPerHost = minPerHost;
    }

    public int getMaxPerHost() {
        return maxPerHost;
    }

    public void setMaxPerHost(int maxPerHost) {
        this.maxPerHost = maxPerHost;
    }

    public long getAcquireTimeoutMs() {
        return acquireTimeoutMs;
    }

    public void setAcquireTimeoutMs(long acquireTimeoutMs) {
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    public long getRebalanceIntervalMs() {
        return rebalanceIntervalMs;
    }

    public void setRebalanceIntervalMs(long rebalanceIntervalMs) {
        this.rebalanceIntervalMs = rebalanceIntervalMs;
    }

    public boolean isHealthHttpEnabled() {
        return healthHttpEnabled;
    }

    public void setHealthHttpEnabled(boolean healthHttpEnabled) {
        this.healthHttpEnabled = healthHttpEnabled;
    }
}
