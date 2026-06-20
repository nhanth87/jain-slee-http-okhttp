package org.restcomm.client.slee.resource.http;

import java.util.ArrayList;
import java.util.List;

/**
 * JMX MBean implementation for pool health metrics.
 */
public class HttpClientPoolHealth implements HttpClientPoolHealthMBean {

    private final AdaptiveHostLimiter limiter;

    public HttpClientPoolHealth(AdaptiveHostLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public String getOverallStatus() {
        return limiter.getSnapshot().getOverallStatus();
    }

    @Override
    public String getSnapshotJson() {
        return limiter.getSnapshot().toJson();
    }

    @Override
    public String[] listHosts() {
        List<String> keys = new ArrayList<String>(limiter.listHosts());
        return keys.toArray(new String[keys.size()]);
    }

    @Override
    public String getHostMetrics(String hostKey) {
        HostMetrics metrics = limiter.getHostMetrics(hostKey);
        if (metrics == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"hostKey\":\"").append(metrics.getHostKey()).append('"');
        sb.append(",\"rate\":").append(String.format("%.4f", metrics.getRateEwma()));
        sb.append(",\"rttEwmaMs\":").append(String.format("%.2f", metrics.getRttEwmaMs()));
        sb.append(",\"rttP99Ms\":").append(metrics.getRttP99Ms());
        sb.append(",\"inFlight\":").append(metrics.getInFlight());
        sb.append(",\"limit\":").append(metrics.getLimit());
        sb.append(",\"utilization\":").append(String.format("%.4f", metrics.getUtilization()));
        sb.append(",\"status\":\"").append(metrics.resolveStatus().name()).append('"');
        sb.append(",\"acquireWaitAvgMs\":").append(String.format("%.2f", metrics.getAcquireWaitAvgMs()));
        sb.append(",\"acquireTimeouts\":").append(metrics.getAcquireTimeouts());
        sb.append(",\"errors\":").append(metrics.getErrors());
        sb.append('}');
        return sb.toString();
    }
}
