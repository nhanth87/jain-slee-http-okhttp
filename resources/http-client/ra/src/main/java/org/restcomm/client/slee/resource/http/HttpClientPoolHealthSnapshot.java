package org.restcomm.client.slee.resource.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable health snapshot for JMX/HTTP exposure.
 */
public final class HttpClientPoolHealthSnapshot {

    public static final class HostEntry {
        public final String hostKey;
        public final double rate;
        public final double rttEwmaMs;
        public final long rttP99Ms;
        public final int inFlight;
        public final int limit;
        public final double utilization;
        public final String status;
        public final double acquireWaitAvgMs;
        public final long acquireTimeouts;
        public final long errors;

        public HostEntry(String hostKey, double rate, double rttEwmaMs, long rttP99Ms, int inFlight, int limit,
                double utilization, String status, double acquireWaitAvgMs, long acquireTimeouts, long errors) {
            this.hostKey = hostKey;
            this.rate = rate;
            this.rttEwmaMs = rttEwmaMs;
            this.rttP99Ms = rttP99Ms;
            this.inFlight = inFlight;
            this.limit = limit;
            this.utilization = utilization;
            this.status = status;
            this.acquireWaitAvgMs = acquireWaitAvgMs;
            this.acquireTimeouts = acquireTimeouts;
            this.errors = errors;
        }
    }

    private final String overallStatus;
    private final int maxTotal;
    private final int inFlight;
    private final double utilization;
    private final double acquireTimeoutRate;
    private final List<HostEntry> hosts;
    private final long timestampMs;

    public HttpClientPoolHealthSnapshot(String overallStatus, int maxTotal, int inFlight, double utilization,
            double acquireTimeoutRate, List<HostEntry> hosts, long timestampMs) {
        this.overallStatus = overallStatus;
        this.maxTotal = maxTotal;
        this.inFlight = inFlight;
        this.utilization = utilization;
        this.acquireTimeoutRate = acquireTimeoutRate;
        this.hosts = Collections.unmodifiableList(new ArrayList<HostEntry>(hosts));
        this.timestampMs = timestampMs;
    }

    public String getOverallStatus() {
        return overallStatus;
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public int getInFlight() {
        return inFlight;
    }

    public double getUtilization() {
        return utilization;
    }

    public double getAcquireTimeoutRate() {
        return acquireTimeoutRate;
    }

    public List<HostEntry> getHosts() {
        return hosts;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public int getHttpStatusCode() {
        if ("UP".equals(overallStatus)) {
            return 200;
        }
        if ("DEGRADED".equals(overallStatus)) {
            return 503;
        }
        return 503;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"status\":\"").append(overallStatus).append('"');
        sb.append(",\"maxTotal\":").append(maxTotal);
        sb.append(",\"inFlight\":").append(inFlight);
        sb.append(",\"utilization\":").append(String.format("%.4f", utilization));
        sb.append(",\"acquireTimeoutRate\":").append(String.format("%.6f", acquireTimeoutRate));
        sb.append(",\"timestampMs\":").append(timestampMs);
        sb.append(",\"hosts\":[");
        for (int i = 0; i < hosts.size(); i++) {
            HostEntry h = hosts.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"hostKey\":\"").append(h.hostKey).append('"');
            sb.append(",\"rate\":").append(String.format("%.4f", h.rate));
            sb.append(",\"rttEwmaMs\":").append(String.format("%.2f", h.rttEwmaMs));
            sb.append(",\"rttP99Ms\":").append(h.rttP99Ms);
            sb.append(",\"inFlight\":").append(h.inFlight);
            sb.append(",\"limit\":").append(h.limit);
            sb.append(",\"utilization\":").append(String.format("%.4f", h.utilization));
            sb.append(",\"status\":\"").append(h.status).append('"');
            sb.append(",\"acquireWaitAvgMs\":").append(String.format("%.2f", h.acquireWaitAvgMs));
            sb.append(",\"acquireTimeouts\":").append(h.acquireTimeouts);
            sb.append(",\"errors\":").append(h.errors);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}
