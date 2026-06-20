package org.restcomm.client.slee.resource.http;

/**
 * JMX interface for adaptive HTTP client pool health.
 */
public interface HttpClientPoolHealthMBean {

    String getOverallStatus();

    String getSnapshotJson();

    String[] listHosts();

    String getHostMetrics(String hostKey);
}
