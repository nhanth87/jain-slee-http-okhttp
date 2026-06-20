package org.restcomm.client.slee.resource.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class HttpClientPoolHealthSnapshotTest {

    @Test
    public void overallStatusDownWhenAcquireTimeoutRateHigh() {
        HttpClientPoolHealthSnapshot snapshot = new HttpClientPoolHealthSnapshot(
                "DOWN", 100, 90, 0.9d, 0.06d,
                Collections.<HttpClientPoolHealthSnapshot.HostEntry>emptyList(), System.currentTimeMillis());
        assertEquals(503, snapshot.getHttpStatusCode());
        assertTrue(snapshot.toJson().contains("\"status\":\"DOWN\""));
    }

    @Test
    public void overallStatusUpReturns200() {
        HttpClientPoolHealthSnapshot snapshot = new HttpClientPoolHealthSnapshot(
                "UP", 100, 10, 0.1d, 0.0d,
                Collections.<HttpClientPoolHealthSnapshot.HostEntry>emptyList(), System.currentTimeMillis());
        assertEquals(200, snapshot.getHttpStatusCode());
    }
}
